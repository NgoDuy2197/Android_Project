"""ScreenGrab — Windows screen capture + recording (full / region, device + mic audio, WDA-bypass try)."""
import os
import sys
import time

from PySide6.QtCore import Qt, QRect, QPoint, Signal, QTimer
from PySide6.QtGui import QPainter, QColor, QPen, QGuiApplication, QIcon
from PySide6.QtWidgets import (
    QApplication, QWidget, QMainWindow, QPushButton, QLabel, QVBoxLayout, QHBoxLayout,
    QComboBox, QSpinBox, QCheckBox, QButtonGroup, QRadioButton, QFrame,
)

from ffmpegutil import list_audio_devices
from recorder import Recorder
import screenshot as shot
import winbypass


class RegionOverlay(QWidget):
    """Fullscreen translucent overlay; drag a rectangle, emits (left, top, w, h) in screen px."""
    picked = Signal(object)

    def __init__(self):
        super().__init__()
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool)
        self.setAttribute(Qt.WA_TranslucentBackground)
        geo = QGuiApplication.primaryScreen().geometry()
        self.setGeometry(geo)
        self.setCursor(Qt.CrossCursor)
        self._start = None
        self._cur = None

    def mousePressEvent(self, e):
        self._start = e.position().toPoint()
        self._cur = self._start
        self.update()

    def mouseMoveEvent(self, e):
        self._cur = e.position().toPoint()
        self.update()

    def mouseReleaseEvent(self, e):
        if self._start and self._cur:
            r = QRect(self._start, self._cur).normalized()
            if r.width() >= 10 and r.height() >= 10:
                self.picked.emit((r.left(), r.top(), r.width(), r.height()))
        self.close()

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.close()

    def paintEvent(self, _):
        p = QPainter(self)
        p.fillRect(self.rect(), QColor(0, 0, 0, 120))
        if self._start and self._cur:
            r = QRect(self._start, self._cur).normalized()
            p.setCompositionMode(QPainter.CompositionMode_Clear)
            p.fillRect(r, Qt.transparent)
            p.setCompositionMode(QPainter.CompositionMode_SourceOver)
            pen = QPen(QColor("#EC4899"))
            pen.setWidth(2)
            p.setPen(pen)
            p.drawRect(r)
            p.setPen(QColor("#FFFFFF"))
            p.drawText(r.left(), max(14, r.top() - 6), f"{r.width()} x {r.height()}")


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("ScreenGrab")
        self.recorder = Recorder()
        self.region = None  # (l,t,w,h) or None = full
        self._overlay = None

        root = QWidget()
        self.setCentralWidget(root)
        v = QVBoxLayout(root)
        v.setContentsMargins(18, 16, 18, 16)
        v.setSpacing(10)

        title = QLabel("ScreenGrab")
        title.setStyleSheet("font-size:22px;font-weight:700;")
        v.addWidget(title)
        v.addWidget(self._dim("Chụp ảnh & quay màn hình PC — toàn màn hình hoặc vùng chọn, kèm âm thanh."))

        # primary actions
        self.btn_shot = QPushButton("📷  Chụp ảnh")
        self.btn_shot.clicked.connect(self.on_shot)
        v.addWidget(self.btn_shot)
        self.btn_rec = QPushButton("⏺  Quay màn hình")
        self.btn_rec.clicked.connect(self.on_rec)
        v.addWidget(self.btn_rec)

        v.addWidget(self._sep())

        # region
        self.rb_full = QRadioButton("Toàn màn hình")
        self.rb_region = QRadioButton("Vùng chọn")
        self.rb_full.setChecked(True)
        grp = QButtonGroup(self)
        grp.addButton(self.rb_full)
        grp.addButton(self.rb_region)
        self.rb_full.toggled.connect(self._on_region_mode)
        row = QHBoxLayout()
        row.addWidget(self.rb_full)
        row.addWidget(self.rb_region)
        btn_pick = QPushButton("✂ Chọn vùng")
        btn_pick.clicked.connect(self.on_pick_region)
        row.addWidget(btn_pick)
        v.addLayout(row)
        self.lbl_region = self._dim("Vùng: toàn màn hình")
        v.addWidget(self.lbl_region)

        v.addWidget(self._sep())

        # audio
        v.addWidget(self._dim("Âm thanh (chọn thiết bị — để trống nếu không cần):"))
        self.cmb_mic = QComboBox()
        self.cmb_loop = QComboBox()
        rowm = QHBoxLayout()
        rowm.addWidget(QLabel("Mic:"))
        rowm.addWidget(self.cmb_mic, 1)
        v.addLayout(rowm)
        rowl = QHBoxLayout()
        rowl.addWidget(QLabel("Âm thanh máy:"))
        rowl.addWidget(self.cmb_loop, 1)
        v.addLayout(rowl)
        btn_refresh = QPushButton("↻ Làm mới danh sách thiết bị")
        btn_refresh.clicked.connect(self.refresh_audio)
        v.addWidget(btn_refresh)
        v.addWidget(self._dim("“Âm thanh máy” cần thiết bị loopback (vd Stereo Mix hoặc VB-Cable) được bật trong Windows."))

        v.addWidget(self._sep())

        # quality + bypass
        rowq = QHBoxLayout()
        rowq.addWidget(QLabel("FPS:"))
        self.sp_fps = QSpinBox()
        self.sp_fps.setRange(5, 60)
        self.sp_fps.setValue(30)
        rowq.addWidget(self.sp_fps)
        rowq.addWidget(QLabel("  Bitrate (Mbps):"))
        self.sp_br = QSpinBox()
        self.sp_br.setRange(1, 80)
        self.sp_br.setValue(12)
        rowq.addWidget(self.sp_br)
        rowq.addStretch(1)
        v.addLayout(rowq)
        self.chk_bypass = QCheckBox("Thử gỡ chặn capture (Telegram/app ẩn) trước khi chụp/quay")
        v.addWidget(self.chk_bypass)

        self.status = self._dim("Sẵn sàng.")
        v.addWidget(self.status)
        v.addStretch(1)

        self.resize(460, 640)
        self._style()
        self.refresh_audio()

    # ---- actions ----
    def on_shot(self):
        self._maybe_bypass()
        self.hide()
        QApplication.processEvents()
        QTimer.singleShot(350, self._do_shot)

    def _do_shot(self):
        try:
            path = shot.capture(self.region)
            self._set_status(f"Đã lưu ảnh: {path}")
        except Exception as e:
            self._set_status(f"Chụp lỗi: {e}")
        finally:
            self.show()

    def on_rec(self):
        if self.recorder.is_recording:
            self._set_status("Đang lưu video…")
            path = self.recorder.stop()
            self.btn_rec.setText("⏺  Quay màn hình")
            self._set_status(f"Đã lưu video: {path}" if path else "Đã dừng.")
            self._enable_inputs(True)
            return
        self._maybe_bypass()
        audio = []
        if self.cmb_mic.currentData():
            audio.append(self.cmb_mic.currentData())
        if self.cmb_loop.currentData():
            audio.append(self.cmb_loop.currentData())
        try:
            path = self.recorder.start(self.region, self.sp_fps.value(), self.sp_br.value(), audio)
            self.btn_rec.setText("⏹  Dừng quay")
            self._enable_inputs(False)
            self._set_status(f"Đang quay → {path}")
        except Exception as e:
            self._set_status(f"Quay lỗi: {e}")

    def on_pick_region(self):
        self._overlay = RegionOverlay()
        self._overlay.picked.connect(self._on_region_picked)
        self.hide()
        QApplication.processEvents()
        time.sleep(0.2)
        self._overlay.showFullScreen()

    def _on_region_picked(self, reg):
        self.region = reg
        self.rb_region.setChecked(True)
        self.lbl_region.setText(f"Vùng: {reg[2]} x {reg[3]} px tại ({reg[0]},{reg[1]})")
        self.show()

    def _on_region_mode(self, _):
        if self.rb_full.isChecked():
            self.region = None
            self.lbl_region.setText("Vùng: toàn màn hình")

    def refresh_audio(self):
        devs = list_audio_devices()
        for cmb in (self.cmb_mic, self.cmb_loop):
            cmb.clear()
            cmb.addItem("(không)", None)
            for d in devs:
                cmb.addItem(d, d)

    def _maybe_bypass(self):
        if self.chk_bypass.isChecked():
            a, s = winbypass.try_unprotect_all()
            self._set_status(f"Bypass: thử {a} cửa sổ, gỡ được {s}.")

    # ---- helpers ----
    def _enable_inputs(self, on: bool):
        for w in (self.rb_full, self.rb_region, self.cmb_mic, self.cmb_loop, self.sp_fps, self.sp_br, self.btn_shot):
            w.setEnabled(on)

    def _set_status(self, s: str):
        self.status.setText(s)

    def _dim(self, t: str) -> QLabel:
        lbl = QLabel(t)
        lbl.setWordWrap(True)
        lbl.setStyleSheet("color:#9AA0C0;font-size:12px;")
        return lbl

    def _sep(self) -> QFrame:
        f = QFrame()
        f.setFrameShape(QFrame.HLine)
        f.setStyleSheet("color:#2A2E48;")
        return f

    def _style(self):
        self.setStyleSheet(
            """
            QMainWindow, QWidget { background:#0E1020; color:#F2F3FA; }
            QPushButton { background:#7C3AED; color:white; border:none; border-radius:10px; padding:11px; font-size:15px; }
            QPushButton:disabled { background:#3A3550; color:#8A8AA0; }
            QComboBox, QSpinBox { background:#1A1D33; color:#F2F3FA; border:1px solid #2A2E48; border-radius:8px; padding:6px; }
            QRadioButton, QCheckBox, QLabel { color:#F2F3FA; }
            """
        )

    def closeEvent(self, e):
        if self.recorder.is_recording:
            self.recorder.stop()
        super().closeEvent(e)


def main():
    app = QApplication(sys.argv)
    icon_png = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.png")
    if os.path.exists(icon_png):
        app.setWindowIcon(QIcon(icon_png))
    w = MainWindow()
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
