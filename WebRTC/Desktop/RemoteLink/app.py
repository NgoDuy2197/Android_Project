"""RemoteLink desktop viewer — connect to an Android phone on the same LAN and view + control it.

Three connection modes (see clients.py):
  Stream (H.264)  — the RemoteLink app's custom low-latency server.
  VNC             — the app's VNC server, or any RFB/VNC server.
  ADB (no app)    — drive the phone directly over adb (3rd capture solution; no app needed).
"""
import json
import os
import sys

from PySide6.QtCore import Qt, QRect, QPoint
from PySide6.QtGui import QImage, QPainter, QColor, QIcon
from PySide6.QtWidgets import (
    QApplication, QWidget, QMainWindow, QStackedWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QLineEdit, QPushButton, QComboBox, QFormLayout, QStatusBar, QSizePolicy,
)

import protocol as P
from clients import StreamClient, VncClient, AdbClient

CFG = os.path.join(os.path.expandvars(r"%APPDATA%"), "RemoteLink", "last.json")
ACCENT = "#6366F1"


def load_cfg():
    try:
        with open(CFG, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_cfg(d):
    try:
        os.makedirs(os.path.dirname(CFG), exist_ok=True)
        with open(CFG, "w", encoding="utf-8") as f:
            json.dump(d, f)
    except Exception:
        pass


class VideoWidget(QWidget):
    """Paints the phone frame (letterboxed) and turns mouse/keyboard into normalized input."""
    def __init__(self, client_holder):
        super().__init__()
        self.holder = client_holder            # holder.client is the active client
        self.img = None
        self.target = QRect()
        self.setMinimumSize(360, 640)
        self.setFocusPolicy(Qt.StrongFocus)
        self.setMouseTracking(False)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setStyleSheet("background:#000;")

    def set_image(self, img: QImage):
        self.img = img
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.fillRect(self.rect(), QColor("#000000"))
        if self.img is None or self.img.isNull():
            p.setPen(QColor("#9AA0C0"))
            p.drawText(self.rect(), Qt.AlignCenter, "Đang chờ khung hình…")
            return
        iw, ih = self.img.width(), self.img.height()
        ww, wh = self.width(), self.height()
        scale = min(ww / iw, wh / ih)
        tw, th = int(iw * scale), int(ih * scale)
        tx, ty = (ww - tw) // 2, (wh - th) // 2
        self.target = QRect(tx, ty, tw, th)
        p.drawImage(self.target, self.img)

    def _norm(self, pos: QPoint):
        if self.target.width() <= 0 or self.target.height() <= 0:
            return None
        xn = (pos.x() - self.target.x()) / self.target.width()
        yn = (pos.y() - self.target.y()) / self.target.height()
        if 0 <= xn <= 1 and 0 <= yn <= 1:
            return xn, yn
        return None

    def _client(self):
        return self.holder.client

    def mousePressEvent(self, e):
        n = self._norm(e.position().toPoint())
        c = self._client()
        if n and c:
            c.touch(0, n[0], n[1])
        self.setFocus()

    def mouseMoveEvent(self, e):
        if not (e.buttons() & Qt.LeftButton):
            return
        n = self._norm(e.position().toPoint())
        c = self._client()
        if n and c:
            c.touch(1, n[0], n[1])

    def mouseReleaseEvent(self, e):
        n = self._norm(e.position().toPoint())
        c = self._client()
        if n and c:
            c.touch(2, n[0], n[1])

    def keyPressEvent(self, e):
        c = self._client()
        if not c:
            return
        k = e.key()
        specials = {
            Qt.Key_Backspace: P.KEY_DEL, Qt.Key_Return: P.KEY_ENTER, Qt.Key_Enter: P.KEY_ENTER,
            Qt.Key_Tab: P.KEY_TAB, Qt.Key_Delete: P.KEY_FORWARD_DEL,
            Qt.Key_Left: P.KEY_DPAD_LEFT, Qt.Key_Right: P.KEY_DPAD_RIGHT,
            Qt.Key_Up: P.KEY_DPAD_UP, Qt.Key_Down: P.KEY_DPAD_DOWN,
        }
        if k == Qt.Key_Escape:
            c.global_action("back")
        elif k in specials:
            c.key(specials[k])
        else:
            t = e.text()
            if t and t.isprintable():
                c.text(t)


class ClientHolder:
    client = None


class ConnectPage(QWidget):
    def __init__(self, on_connect):
        super().__init__()
        cfg = load_cfg()
        form = QFormLayout()
        self.host = QLineEdit(cfg.get("host", "192.168.1."))
        self.port = QLineEdit(cfg.get("port", "5901"))
        self.proto = QComboBox()
        self.proto.addItems(["Stream (H.264)", "VNC", "ADB (không cần app)"])
        self.proto.setCurrentIndex(cfg.get("proto", 0))
        self.serial = QLineEdit(cfg.get("serial", ""))
        self.pw = QLineEdit(cfg.get("pw", ""))
        self.pw.setEchoMode(QLineEdit.Password)

        form.addRow("IP điện thoại", self.host)
        form.addRow("Cổng", self.port)
        form.addRow("Giao thức", self.proto)
        form.addRow("Serial (chỉ ADB)", self.serial)
        form.addRow("Mật khẩu", self.pw)

        btn = QPushButton("Kết nối")
        btn.setStyleSheet(f"background:{ACCENT};color:white;padding:10px;border-radius:8px;font-size:15px;")
        btn.clicked.connect(lambda: on_connect(self.values()))

        self.proto.currentIndexChanged.connect(self._sync)
        title = QLabel("RemoteLink")
        title.setStyleSheet("font-size:26px;font-weight:bold;color:#F2F3FA;")
        sub = QLabel("Xem & điều khiển điện thoại trong mạng LAN")
        sub.setStyleSheet("color:#9AA0C0;")

        lay = QVBoxLayout(self)
        lay.addStretch(1)
        lay.addWidget(title)
        lay.addWidget(sub)
        lay.addSpacing(16)
        lay.addLayout(form)
        lay.addSpacing(10)
        lay.addWidget(btn)
        lay.addStretch(2)
        lay.setContentsMargins(60, 40, 60, 40)
        self._sync()

    def _sync(self):
        is_adb = self.proto.currentIndex() == 2
        self.host.setEnabled(not is_adb)
        self.port.setEnabled(not is_adb)
        self.serial.setEnabled(is_adb)
        if not is_adb:
            self.port.setText("5901" if self.proto.currentIndex() == 0 else "5900")

    def values(self):
        return {
            "host": self.host.text().strip(),
            "port": self.port.text().strip(),
            "proto": self.proto.currentIndex(),
            "serial": self.serial.text().strip(),
            "pw": self.pw.text(),
        }


class ViewerPage(QWidget):
    def __init__(self, holder, on_disconnect):
        super().__init__()
        self.holder = holder
        self.video = VideoWidget(holder)

        bar = QHBoxLayout()
        for label, act in [("◀ Back", "back"), ("● Home", "home"), ("▣ Recents", "recents")]:
            b = QPushButton(label)
            b.clicked.connect(lambda _, a=act: self._global(a))
            b.setStyleSheet("padding:6px 10px;")
            bar.addWidget(b)
        self.cmd = QLineEdit()
        self.cmd.setPlaceholderText("Gửi lệnh (vd: input keyevent 26)…")
        self.cmd.returnPressed.connect(self._send_cmd)
        bar.addWidget(self.cmd, 1)
        dis = QPushButton("Ngắt kết nối")
        dis.setStyleSheet("background:#FF453A;color:white;padding:6px 12px;border-radius:6px;")
        dis.clicked.connect(on_disconnect)
        bar.addWidget(dis)

        lay = QVBoxLayout(self)
        lay.addLayout(bar)
        lay.addWidget(self.video, 1)
        lay.setContentsMargins(8, 8, 8, 8)

    def _global(self, a):
        if self.holder.client:
            self.holder.client.global_action(a)
        self.video.setFocus()

    def _send_cmd(self):
        s = self.cmd.text().strip()
        if s and self.holder.client:
            self.holder.client.command(s)
            self.cmd.clear()
        self.video.setFocus()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("RemoteLink")
        self.resize(520, 900)
        self.holder = ClientHolder()
        self.stack = QStackedWidget()
        self.connect_page = ConnectPage(self.do_connect)
        self.viewer_page = ViewerPage(self.holder, self.do_disconnect)
        self.stack.addWidget(self.connect_page)
        self.stack.addWidget(self.viewer_page)
        self.setCentralWidget(self.stack)
        self.setStatusBar(QStatusBar())
        self.statusBar().showMessage("Sẵn sàng")
        icon_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.png")
        if os.path.exists(icon_path):
            self.setWindowIcon(QIcon(icon_path))

    def do_connect(self, v):
        save_cfg(v)
        try:
            if v["proto"] == 0:
                client = StreamClient(v["host"], int(v["port"]), v["pw"])
            elif v["proto"] == 1:
                client = VncClient(v["host"], int(v["port"]), v["pw"])
            else:
                client = AdbClient(v["serial"])
        except Exception as e:
            self.statusBar().showMessage(f"Lỗi cấu hình: {e}")
            return
        self.holder.client = client
        client.frame.connect(self.viewer_page.video.set_image)
        client.status.connect(self.statusBar().showMessage)
        client.closed.connect(self.on_closed)
        client.start()
        self.stack.setCurrentWidget(self.viewer_page)
        self.viewer_page.video.setFocus()

    def do_disconnect(self):
        if self.holder.client:
            self.holder.client.stop()
            self.holder.client = None
        self.stack.setCurrentWidget(self.connect_page)
        self.statusBar().showMessage("Đã ngắt")

    def on_closed(self):
        self.statusBar().showMessage("Kết nối đã đóng")
        self.stack.setCurrentWidget(self.connect_page)

    def closeEvent(self, e):
        if self.holder.client:
            self.holder.client.stop()
        super().closeEvent(e)


def main():
    app = QApplication(sys.argv)
    app.setStyleSheet("""
        QWidget { background:#0E1020; color:#F2F3FA; font-size:13px; }
        QLineEdit, QComboBox { background:#1A1D33; border:1px solid #2A2E4A; border-radius:6px; padding:6px; }
        QPushButton { background:#1A1D33; border:none; border-radius:6px; color:#F2F3FA; }
        QPushButton:hover { background:#2A2E4A; }
        QStatusBar { color:#9AA0C0; }
    """)
    icon_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.png")
    if os.path.exists(icon_path):
        app.setWindowIcon(QIcon(icon_path))
    w = MainWindow()
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
