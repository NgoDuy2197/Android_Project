"""TryOn for Windows: try clothes on in real time with the PC webcam.

Same features as the mobile app: local cart, 10 body parts worn at once,
auto fit + manual size, photo / video / still-photo modes, backdrop
replacement, hide-UI view. Run: python tryon.py
"""
from __future__ import annotations

import math
import os
import sys
import time
from datetime import datetime

import cv2
import numpy as np
from PySide6.QtCore import QObject, QPoint, QRect, QSize, Qt, QThread, QTimer, Signal
from PySide6.QtGui import QAction, QColor, QFont, QIcon, QImage, QKeySequence, QPainter, QPixmap, QShortcut
from PySide6.QtWidgets import (
    QApplication, QButtonGroup, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog, QFrame,
    QGridLayout, QHBoxLayout, QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow, QMenu,
    QMessageBox, QPushButton, QSizePolicy, QSlider, QToolButton, QVBoxLayout, QWidget,
)

import engine
import imgtools
from store import PART_ITEM, PART_KEYS, PART_LABEL, PARTS, Store, resource_dir

APP = "TryOn"
OUT_DIR = os.path.join(os.path.expanduser("~"), "Pictures", "TryOn")

STYLE = """
* { font-family: 'Segoe UI'; font-size: 10pt; color: #F2F2F7; }
QMainWindow, QDialog { background: #0E0E14; }
QFrame#panel { background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.10); border-radius: 18px; }
QPushButton, QToolButton {
  background: rgba(255,255,255,0.10); border: 1px solid rgba(255,255,255,0.16);
  border-radius: 12px; padding: 7px 12px;
}
QPushButton:hover, QToolButton:hover { background: rgba(255,255,255,0.18); }
QPushButton:checked, QToolButton:checked {
  background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #FF5FA2, stop:0.5 #7C5CFF, stop:1 #34D3FF);
  border: none; font-weight: 600;
}
QPushButton#mode { background: transparent; border: none; font-weight: 700; letter-spacing: 1px; color: #FFFFFF; }
QPushButton#mode:checked { color: #FFD60A; background: transparent; }
QPushButton#danger { background: rgba(255,59,48,0.25); border-color: rgba(255,59,48,0.6); }
QListWidget { background: rgba(0,0,0,0.25); border: 1px solid rgba(255,255,255,0.10); border-radius: 12px; }
QListWidget::item { border-radius: 10px; padding: 4px; }
QListWidget::item:selected { background: rgba(124,92,255,0.55); }
QSlider::groove:horizontal { height: 4px; background: rgba(255,255,255,0.25); border-radius: 2px; }
QSlider::sub-page:horizontal { background: #FFFFFF; border-radius: 2px; }
QSlider::handle:horizontal { background: #FFFFFF; width: 16px; margin: -7px 0; border-radius: 8px; }
QLineEdit, QComboBox { background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.18);
  border-radius: 10px; padding: 6px 8px; }
QComboBox QAbstractItemView { background: #1C1C26; }
QMenu { background: #1C1C26; border: 1px solid #333; }
QMenu::item:selected { background: #7C5CFF; }
QCheckBox::indicator { width: 18px; height: 18px; }
"""


def bgr_to_qimage(img: np.ndarray) -> QImage:
    h, w = img.shape[:2]
    if img.ndim == 3 and img.shape[2] == 4:
        return QImage(img.data, w, h, 4 * w, QImage.Format.Format_ARGB32).copy()
    img = np.ascontiguousarray(img)
    return QImage(img.data, w, h, 3 * w, QImage.Format.Format_BGR888).copy()


def thumb_icon(path: str, size: int = 72) -> QIcon:
    img = imgtools.load_any(path)
    if img is None:
        return QIcon()
    h, w = img.shape[:2]
    s = size / max(h, w)
    img = cv2.resize(img, (max(1, int(w * s)), max(1, int(h * s))), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((size, size, 4), np.uint8)
    y, x = (size - img.shape[0]) // 2, (size - img.shape[1]) // 2
    canvas[y:y + img.shape[0], x:x + img.shape[1]] = img
    rgb = imgtools.over_checker(canvas)
    return QIcon(QPixmap.fromImage(bgr_to_qimage(rgb)))


# ------------------------------------------------------------------ camera

class CameraWorker(QThread):
    """Grabs webcam frames, detects the pose and composes the outfit."""

    frame = Signal(QImage)
    status = Signal(str)
    person = Signal(bool)

    def __init__(self, model_path: str):
        super().__init__()
        self.model_path = model_path
        self.cam_index = 0
        self.mirror = True
        self.items: dict[str, np.ndarray] = {}
        self.adjust = {}
        self.backdrop: np.ndarray | None = None
        self.need_mask = False
        self.focus: str | None = None
        self.paused = False
        self.last_clean: np.ndarray | None = None
        self.last_raw: np.ndarray | None = None
        self.fps = 15.0
        self._writer: cv2.VideoWriter | None = None
        self._switch = True
        self._running = True

    def set_camera(self, index: int):
        self.cam_index = index
        self._switch = True

    # recording (called from the UI thread; writer used in this thread)
    def start_recording(self, path: str) -> bool:
        frame = self.last_clean
        if frame is None:
            return False
        h, w = frame.shape[:2]
        fps = max(8.0, min(30.0, self.fps))
        writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
        if not writer.isOpened():
            return False
        self._writer = writer
        return True

    def stop_recording(self):
        w, self._writer = self._writer, None
        if w is not None:
            time.sleep(0.05)
            w.release()

    def stop(self):
        self._running = False
        self.wait(3000)

    def run(self):
        pose = engine.PoseEngine(self.model_path, video=True)
        tracker = engine.PoseTracker()
        cap = None
        t_prev = time.monotonic()
        while self._running:
            if self._switch:
                self._switch = False
                if cap is not None:
                    cap.release()
                cap = cv2.VideoCapture(self.cam_index, cv2.CAP_DSHOW)
                cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
                cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
                tracker.reset()
                if not cap.isOpened():
                    self.status.emit(f"Không mở được camera {self.cam_index}")
            if self.paused or cap is None or not cap.isOpened():
                time.sleep(0.05)
                continue
            ok, raw = cap.read()
            if not ok:
                time.sleep(0.02)
                continue
            if self.mirror:
                raw = cv2.flip(raw, 1)
            pts, mask = pose.detect(raw)
            smooth = tracker.update(pts, raw.shape[1])
            clean = engine.compose(raw, smooth, self.items, self.adjust,
                                   mask if self.need_mask else None, self.backdrop)
            self.last_raw, self.last_clean = raw, clean
            w = self._writer
            if w is not None:
                w.write(clean)
            shown = clean
            if self.focus and smooth:
                shown = clean.copy()
                engine.draw_focus(shown, smooth, self.focus, (math.sin(time.monotonic() * 7) + 1) / 2)
            self.frame.emit(bgr_to_qimage(shown))
            self.person.emit(bool(smooth))
            now = time.monotonic()
            self.fps = self.fps * 0.9 + (1 / max(1e-3, now - t_prev)) * 0.1
            t_prev = now
        if cap is not None:
            cap.release()
        self.stop_recording()
        pose.close()


# -------------------------------------------------------------- video view

class VideoView(QWidget):
    """Shows the composed frame (fit), toasts, countdown and REC badge."""

    clicked = Signal(QPoint)

    def __init__(self):
        super().__init__()
        self.image: QImage | None = None
        self.toast_text = ""
        self.countdown = 0
        self.rec_text = ""
        self.hint = ""
        self.flash = 0.0
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self.setMinimumSize(480, 360)
        self._toast_timer = QTimer(self, singleShot=True, timeout=self._clear_toast)
        self._flash_timer = QTimer(self, interval=30, timeout=self._fade)

    def set_image(self, img: QImage):
        self.image = img
        self.update()

    def toast(self, text: str, ms: int = 2500):
        self.toast_text = text
        self._toast_timer.start(ms)
        self.update()

    def do_flash(self):
        self.flash = 0.8
        self._flash_timer.start()

    def _fade(self):
        self.flash = max(0.0, self.flash - 0.12)
        if self.flash == 0:
            self._flash_timer.stop()
        self.update()

    def _clear_toast(self):
        self.toast_text = ""
        self.update()

    def image_rect(self) -> QRect:
        if self.image is None:
            return QRect()
        size = self.image.size().scaled(self.size(), Qt.AspectRatioMode.KeepAspectRatio)
        return QRect(QPoint((self.width() - size.width()) // 2, (self.height() - size.height()) // 2), size)

    def mousePressEvent(self, e):
        self.clicked.emit(e.position().toPoint())

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
        p.fillRect(self.rect(), QColor(0, 0, 0))
        if self.image is not None:
            p.drawImage(self.image_rect(), self.image)
        if self.flash > 0:
            p.fillRect(self.rect(), QColor(255, 255, 255, int(255 * self.flash)))
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        if self.rec_text:
            self._pill(p, self.rec_text, QPoint(self.width() // 2, 30), QColor(255, 59, 48, 230))
        if self.hint:
            self._pill(p, self.hint, QPoint(self.width() // 2, 74), QColor(0, 0, 0, 140))
        if self.toast_text:
            self._pill(p, self.toast_text, QPoint(self.width() // 2, self.height() - 40), QColor(0, 0, 0, 170))
        if self.countdown:
            f = QFont("Segoe UI", 96, QFont.Weight.Bold)
            p.setFont(f)
            p.setPen(QColor(255, 255, 255, 230))
            p.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, str(self.countdown))

    def _pill(self, p: QPainter, text: str, center: QPoint, color: QColor):
        f = QFont("Segoe UI", 11, QFont.Weight.DemiBold)
        p.setFont(f)
        r = p.fontMetrics().boundingRect(text).adjusted(-16, -8, 16, 8)
        r.moveCenter(center)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(color)
        p.drawRoundedRect(r, r.height() / 2, r.height() / 2)
        p.setPen(QColor(255, 255, 255))
        p.drawText(r, Qt.AlignmentFlag.AlignCenter, text)


# ----------------------------------------------------------- import dialog

class ImportDialog(QDialog):
    """Pick background colours on the photo, remove them, name and file the item."""

    def __init__(self, parent, img: np.ndarray, part: str, default_name: str):
        super().__init__(parent)
        self.setWindowTitle("Thêm vào giỏ hàng")
        self.src = img
        self.keys: list[tuple[int, int, int]] = []
        self.result_img: np.ndarray | None = None
        self.picking = False

        self.preview = QLabel(alignment=Qt.AlignmentFlag.AlignCenter)
        self.preview.setFixedSize(420, 420)
        self.preview.setStyleSheet("background:#F2F2F7;border-radius:16px;")
        self.preview.mousePressEvent = self._pick  # type: ignore[assignment]

        self.view_btn = QPushButton("🎯 Chọn màu nền (chạm vào ảnh)", checkable=True)
        self.view_btn.toggled.connect(self._set_picking)
        self.keys_list = QListWidget()
        self.keys_list.setFlow(QListWidget.Flow.LeftToRight)
        self.keys_list.setFixedHeight(44)
        self.keys_list.setToolTip("Bấm đúp vào màu để bỏ")
        self.keys_list.itemDoubleClicked.connect(self._remove_key)
        self.auto = QCheckBox("Tự động tách nền (nền trắng / trơn)", checked=True)
        self.border = QCheckBox("Chỉ xoá vùng màu nối với viền ảnh")
        self.tol = QSlider(Qt.Orientation.Horizontal, minimum=10, maximum=110, value=42)
        self.tol_lbl = QLabel("42")
        for w in (self.auto, self.border):
            w.toggled.connect(self._process)
        self.tol.valueChanged.connect(lambda v: self.tol_lbl.setText(str(v)))
        self.tol.sliderReleased.connect(self._process)
        self.name = QLineEdit(default_name)
        self.part = QComboBox()
        for k, label, item in PARTS:
            self.part.addItem(f"{label} · {item}", k)
        self.part.setCurrentIndex(PART_KEYS.index(part))
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("Thêm vào giỏ & mặc thử")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Huỷ")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        tol_row = QHBoxLayout()
        tol_row.addWidget(QLabel("Độ mạnh"))
        tol_row.addWidget(self.tol, 1)
        tol_row.addWidget(self.tol_lbl)
        right = QVBoxLayout()
        right.addWidget(self.view_btn)
        right.addWidget(QLabel("Màu nền sẽ xoá (bấm đúp để bỏ):"))
        right.addWidget(self.keys_list)
        right.addWidget(self.border)
        right.addWidget(self.auto)
        right.addLayout(tol_row)
        right.addSpacing(10)
        right.addWidget(QLabel("Tên sản phẩm"))
        right.addWidget(self.name)
        right.addWidget(QLabel("Mặc ở bộ phận"))
        right.addWidget(self.part)
        right.addStretch(1)
        right.addWidget(buttons)
        lay = QHBoxLayout(self)
        lay.addWidget(self.preview)
        lay.addLayout(right)
        self._process()

    def _shown(self) -> np.ndarray:
        return self.src if self.picking else (self.result_img if self.result_img is not None else self.src)

    def _render(self):
        img = self._shown()
        rgb = imgtools.over_checker(img)
        pm = QPixmap.fromImage(bgr_to_qimage(rgb)).scaled(400, 400, Qt.AspectRatioMode.KeepAspectRatio,
                                                         Qt.TransformationMode.SmoothTransformation)
        self.preview.setPixmap(pm)

    def _set_picking(self, on: bool):
        self.picking = on
        self.preview.setCursor(Qt.CursorShape.CrossCursor if on else Qt.CursorShape.ArrowCursor)
        self._render()

    def _pick(self, e):
        if not self.picking:
            return
        pm = self.preview.pixmap()
        if pm is None or pm.isNull():
            return
        ox, oy = (self.preview.width() - pm.width()) / 2, (self.preview.height() - pm.height()) / 2
        h, w = self.src.shape[:2]
        x = int((e.position().x() - ox) / pm.width() * w)
        y = int((e.position().y() - oy) / pm.height() * h)
        if not (0 <= x < w and 0 <= y < h):
            return
        patch = self.src[max(0, y - 2):y + 3, max(0, x - 2):x + 3, :3].reshape(-1, 3).mean(axis=0)
        key = tuple(int(v) for v in patch)
        if key not in self.keys:
            self.keys.append(key)
            pm = QPixmap(24, 24)
            pm.fill(QColor(key[2], key[1], key[0]))
            it = QListWidgetItem(QIcon(pm), f"#{key[2]:02X}{key[1]:02X}{key[0]:02X}")
            it.setData(Qt.ItemDataRole.UserRole, key)
            self.keys_list.addItem(it)
        self.view_btn.setChecked(False)
        self._process()

    def _remove_key(self, it: QListWidgetItem):
        key = it.data(Qt.ItemDataRole.UserRole)
        self.keys = [k for k in self.keys if k != tuple(key)]
        self.keys_list.takeItem(self.keys_list.row(it))
        self._process()

    def _process(self):
        self.result_img = imgtools.prepare(self.src, self.auto.isChecked(), self.tol.value(),
                                           self.keys, self.border.isChecked())
        self._render()

    def values(self):
        return self.name.text().strip() or "Sản phẩm", self.part.currentData(), self.result_img


# --------------------------------------------------------- backdrop dialog

class BackdropDialog(QDialog):
    def __init__(self, parent, store: Store, on_change):
        super().__init__(parent)
        self.setWindowTitle("Background – chỉ giữ lại người")
        self.store, self.on_change = store, on_change
        self.list = QListWidget()
        self.list.setViewMode(QListWidget.ViewMode.IconMode)
        self.list.setIconSize(QSize(96, 150))
        self.list.setGridSize(QSize(118, 190))
        self.list.setResizeMode(QListWidget.ResizeMode.Adjust)
        self.list.setMovement(QListWidget.Movement.Static)
        self.list.itemClicked.connect(self._select)
        add = QPushButton("＋ Thêm ảnh nền…")
        add.clicked.connect(self._add)
        rm = QPushButton("Xoá ảnh đang chọn", objectName="danger")
        rm.clicked.connect(self._remove)
        close = QPushButton("Xong")
        close.clicked.connect(self.accept)
        row = QHBoxLayout()
        row.addWidget(add)
        row.addWidget(rm)
        row.addStretch(1)
        row.addWidget(close)
        lay = QVBoxLayout(self)
        lay.addWidget(self.list)
        lay.addLayout(row)
        self.resize(720, 520)
        self._fill()

    def _fill(self):
        self.list.clear()
        none = QListWidgetItem("Không")
        pm = QPixmap(96, 150)
        pm.fill(QColor(40, 40, 50))
        none.setIcon(QIcon(pm))
        none.setData(Qt.ItemDataRole.UserRole, None)
        self.list.addItem(none)
        if self.store.backdrop_id is None:
            none.setSelected(True)
        for b in self.store.backdrops:
            img = imgtools.load_any(b.path)
            it = QListWidgetItem(b.name)
            if img is not None:
                it.setIcon(QIcon(QPixmap.fromImage(bgr_to_qimage(engine.cover(img, 96, 150)))))
            it.setData(Qt.ItemDataRole.UserRole, b.id)
            self.list.addItem(it)
            if b.id == self.store.backdrop_id:
                it.setSelected(True)

    def _select(self, it):
        self.store.select_backdrop(it.data(Qt.ItemDataRole.UserRole))
        self.on_change()

    def _add(self):
        path, _ = QFileDialog.getOpenFileName(self, "Chọn ảnh nền", "", "Ảnh (*.png *.jpg *.jpeg *.webp *.bmp)")
        if not path:
            return
        img = imgtools.load_any(path)
        if img is None:
            QMessageBox.warning(self, APP, "Không đọc được ảnh")
            return
        self.store.add_backdrop(imgtools.encode_jpg(img))
        self.on_change()
        self._fill()

    def _remove(self):
        it = self.list.currentItem()
        bid = it.data(Qt.ItemDataRole.UserRole) if it else None
        b = next((b for b in self.store.backdrops if b.id == bid), None)
        if b and QMessageBox.question(self, APP, f'Xoá "{b.name}"?') == QMessageBox.StandardButton.Yes:
            self.store.remove_backdrop(b)
            self.on_change()
            self._fill()


# --------------------------------------------------------------- main window

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("TryOn – thử đồ qua webcam")
        self.store = Store.load()
        self.cache: dict[str, np.ndarray] = {}
        self.mode = "photo"  # photo | video | still
        self.part: str | None = None
        self.recording_path: str | None = None
        self.rec_start = 0.0
        self.still: dict | None = None  # still-photo mode state
        self.still_engine: engine.PoseEngine | None = None
        model = os.path.join(resource_dir(), "models", "pose_landmarker_full.task")

        self.view = VideoView()
        self.worker = CameraWorker(model)
        self.worker.frame.connect(self._on_frame)
        self.worker.status.connect(lambda s: self.view.toast(s, 4000))
        self.worker.person.connect(self._on_person)

        # ---- top bar
        self.top = QWidget()
        top = QHBoxLayout(self.top)
        top.setContentsMargins(0, 0, 0, 0)
        title = QLabel("● TryOn")
        title.setStyleSheet("font-size:14pt;font-weight:700;")
        self.cam_box = QComboBox()
        for i in range(4):
            self.cam_box.addItem(f"Camera {i}", i)
        self.cam_box.currentIndexChanged.connect(lambda _: self.worker.set_camera(self.cam_box.currentData()))
        self.mirror = QCheckBox("Lật gương", checked=True)
        self.mirror.toggled.connect(self._set_mirror)
        bg_btn = QPushButton("🖼  Nền")
        bg_btn.clicked.connect(self._backdrops)
        folder = QPushButton("📁  Thư mục ảnh")
        folder.clicked.connect(lambda: (os.makedirs(OUT_DIR, exist_ok=True), os.startfile(OUT_DIR)))
        hide = QPushButton("👁  Ẩn giao diện (Esc)")
        hide.clicked.connect(lambda: self._hide_ui(True))
        top.addWidget(title)
        top.addSpacing(12)
        top.addWidget(self.cam_box)
        top.addWidget(self.mirror)
        top.addStretch(1)
        for w in (bg_btn, folder, hide):
            top.addWidget(w)

        # ---- bottom bar: modes + shutter
        self.bottom = QWidget()
        bottom = QHBoxLayout(self.bottom)
        bottom.setContentsMargins(0, 0, 0, 0)
        self.mode_group = QButtonGroup(self)
        modes = QHBoxLayout()
        for key, text in (("video", "VIDEO"), ("photo", "ẢNH"), ("still", "ẢNH TĨNH")):
            b = QPushButton(text, objectName="mode", checkable=True)
            b.setChecked(key == "photo")
            b.clicked.connect(lambda _=False, k=key: self._set_mode(k))
            self.mode_group.addButton(b)
            modes.addWidget(b)
        self.shutter = QPushButton()
        self.shutter.setFixedSize(76, 76)
        self.shutter.clicked.connect(self._shutter)
        self.still_bar = QWidget()
        sb = QHBoxLayout(self.still_bar)
        sb.setContentsMargins(0, 0, 0, 0)
        b1 = QPushButton("📂 Chọn ảnh có sẵn…")
        b1.clicked.connect(self._still_from_file)
        b2 = QPushButton("⏱ Chụp toàn thân (đếm ngược 5s)")
        b2.clicked.connect(self._still_from_webcam)
        b3 = QPushButton("↩ Quay lại camera")
        b3.clicked.connect(lambda: self._set_mode("photo"))
        for b in (b1, b2, b3):
            sb.addWidget(b)
        bottom.addLayout(modes)
        bottom.addStretch(1)
        bottom.addWidget(self.shutter)
        bottom.addStretch(1)
        bottom.addWidget(self.still_bar)
        self._style_shutter()

        # ---- right panel: parts + editor
        self.panel = QFrame(objectName="panel")
        self.panel.setFixedWidth(360)
        pl = QVBoxLayout(self.panel)
        pl.setContentsMargins(14, 14, 14, 14)
        pl.addWidget(QLabel("<b>Bộ phận</b> – bấm để chọn đồ / chỉnh size"))
        grid = QGridLayout()
        grid.setSpacing(8)
        self.part_btns: dict[str, QToolButton] = {}
        for i, (k, label, _) in enumerate(PARTS):
            b = QToolButton(checkable=True)
            b.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
            b.setIconSize(QSize(40, 40))
            b.setFixedSize(62, 86)
            b.setText(label.replace(' ', '\n'))  # 'Hào quang' -> two lines
            b.clicked.connect(lambda _=False, k=k: self._select_part(k))
            self.part_btns[k] = b
            grid.addWidget(b, i // 5, i % 5)
        pl.addLayout(grid)
        self.editor_title = QLabel()
        self.editor_title.setStyleSheet("font-size:12pt;font-weight:700;margin-top:8px;")
        pl.addWidget(self.editor_title)
        self.items = QListWidget()
        self.items.setViewMode(QListWidget.ViewMode.IconMode)
        self.items.setIconSize(QSize(64, 64))
        self.items.setGridSize(QSize(84, 100))
        self.items.setResizeMode(QListWidget.ResizeMode.Adjust)
        self.items.setMovement(QListWidget.Movement.Static)
        self.items.setWordWrap(True)
        self.items.setFixedHeight(220)
        self.items.itemClicked.connect(self._item_clicked)
        self.items.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.items.customContextMenuRequested.connect(self._item_menu)
        pl.addWidget(self.items)
        row = QHBoxLayout()
        self.del_btn = QPushButton("🗑 Xoá sản phẩm", objectName="danger")
        self.del_btn.clicked.connect(self._delete_selected)
        row.addWidget(self.del_btn)
        pl.addLayout(row)
        self.sliders: dict[str, QSlider] = {}
        self.slider_lbl: dict[str, QLabel] = {}
        specs = [("scale", "Kích thước", 40, 250), ("width", "Độ rộng", 50, 180), ("height", "Độ dài", 50, 180),
                 ("dx", "Trái ↔ phải", -60, 60), ("dy", "Lên ↕ xuống", -60, 60)]
        self.adj_box = QWidget()
        al = QGridLayout(self.adj_box)
        al.setContentsMargins(0, 6, 0, 0)
        for r, (key, label, lo, hi) in enumerate(specs):
            s = QSlider(Qt.Orientation.Horizontal, minimum=lo, maximum=hi)
            s.valueChanged.connect(lambda v, k=key: self._adjust(k, v))
            lbl = QLabel()
            lbl.setFixedWidth(46)
            al.addWidget(QLabel(label), r, 0)
            al.addWidget(s, r, 1)
            al.addWidget(lbl, r, 2)
            self.sliders[key], self.slider_lbl[key] = s, lbl
        self.flip = QCheckBox("Lật ngang ảnh")
        self.flip.toggled.connect(self._flip)
        reset = QPushButton("✨ Về size tự động")
        reset.clicked.connect(self._reset_adjust)
        al.addWidget(self.flip, len(specs), 0, 1, 2)
        al.addWidget(reset, len(specs) + 1, 0, 1, 3)
        pl.addWidget(self.adj_box)
        pl.addStretch(1)

        # ---- layout
        center = QWidget()
        cl = QVBoxLayout(center)
        cl.setContentsMargins(14, 10, 14, 10)
        cl.addWidget(self.top)
        mid = QHBoxLayout()
        mid.addWidget(self.view, 1)
        mid.addWidget(self.panel)
        cl.addLayout(mid, 1)
        cl.addWidget(self.bottom)
        self.setCentralWidget(center)

        self.show_ui_btn = QPushButton("👁", self.view)
        self.show_ui_btn.setFixedSize(40, 40)
        self.show_ui_btn.setToolTip("Hiện giao diện (Esc)")
        self.show_ui_btn.clicked.connect(lambda: self._hide_ui(False))
        self.show_ui_btn.hide()
        self.view.clicked.connect(lambda _: self._hide_ui(False) if self.show_ui_btn.isVisible() else None)
        QShortcut(QKeySequence(Qt.Key.Key_Escape), self, activated=lambda: self._hide_ui(not self.show_ui_btn.isVisible()))
        QShortcut(QKeySequence(Qt.Key.Key_Space), self, activated=self._shutter)

        self.rec_timer = QTimer(self, interval=500, timeout=self._rec_tick)
        self._refresh_items()
        self._select_part(PART_KEYS[0])
        self._set_mode("photo")
        self.worker.start()

    # ------------------------------------------------------------ state
    def _image(self, path: str) -> np.ndarray | None:
        img = self.cache.get(path)
        if img is None:
            img = imgtools.load_any(path)
            if img is not None:
                self.cache[path] = img
        return img

    def _refresh_items(self):
        items = {}
        for k in PART_KEYS:
            p = self.store.worn_item(k)
            if p is not None:
                img = self._image(p.path)
                if img is not None:
                    items[k] = img
        bd = self.store.backdrop
        backdrop = self._image(bd.path) if bd else None
        self.worker.items = items
        self.worker.adjust = self.store.adjust
        self.worker.backdrop = backdrop[:, :, :3] if backdrop is not None else None
        self.worker.need_mask = self.store.needs_mask()
        for k, b in self.part_btns.items():
            p = self.store.worn_item(k)
            b.setIcon(thumb_icon(p.path, 40) if p else QIcon())
            b.setStyleSheet("" if p is None else "QToolButton{border:2px solid #7C5CFF;}")
        if self.still:
            self._render_still()

    def _select_part(self, part: str):
        self.part = part
        for k, b in self.part_btns.items():
            b.setChecked(k == part)
        self.worker.focus = part
        self._fill_editor()

    def _fill_editor(self):
        part = self.part
        if part is None:
            return
        self.editor_title.setText(f"{PART_LABEL[part]} · {PART_ITEM[part]}")
        self.items.clear()
        add = QListWidgetItem("＋ Thêm")
        add.setData(Qt.ItemDataRole.UserRole, "__add__")
        none = QListWidgetItem("⊘ Không")
        none.setData(Qt.ItemDataRole.UserRole, "__none__")
        for it in (add, none):
            pm = QPixmap(64, 64)
            pm.fill(QColor(124, 92, 255) if it is add else QColor(50, 50, 60))
            it.setIcon(QIcon(pm))
            self.items.addItem(it)
        worn = self.store.worn.get(part)
        if worn is None:
            none.setSelected(True)
        for p in self.store.items_for(part):
            it = QListWidgetItem(thumb_icon(p.path, 64), p.name)
            it.setData(Qt.ItemDataRole.UserRole, p.id)
            self.items.addItem(it)
            if p.id == worn:
                it.setSelected(True)
        adj = self.store.adjust[part]
        for key, s in self.sliders.items():
            s.blockSignals(True)
            s.setValue(int(round(getattr(adj, key) * 100)))
            s.blockSignals(False)
            self._slider_text(key)
        self.flip.blockSignals(True)
        self.flip.setChecked(adj.flip)
        self.flip.blockSignals(False)
        self.adj_box.setEnabled(worn is not None)

    def _slider_text(self, key):
        v = self.sliders[key].value()
        self.slider_lbl[key].setText(f"{'+' if v > 0 else ''}{v}%" if key in ("dx", "dy") else f"{v}%")

    def _item_clicked(self, it: QListWidgetItem):
        data = it.data(Qt.ItemDataRole.UserRole)
        if data == "__add__":
            self._import()
            return
        self.store.wear(self.part, None if data == "__none__" else data)
        self._refresh_items()
        self._fill_editor()

    def _item_menu(self, pos):
        it = self.items.itemAt(pos)
        if it is None or it.data(Qt.ItemDataRole.UserRole) in ("__add__", "__none__"):
            return
        m = QMenu(self)
        act = QAction("🗑 Xoá sản phẩm", m)
        act.triggered.connect(lambda: self._delete(it.data(Qt.ItemDataRole.UserRole)))
        m.addAction(act)
        m.exec(self.items.mapToGlobal(pos))

    def _delete_selected(self):
        it = self.items.currentItem()
        if it is not None and it.data(Qt.ItemDataRole.UserRole) not in ("__add__", "__none__"):
            self._delete(it.data(Qt.ItemDataRole.UserRole))

    def _delete(self, pid):
        p = next((p for p in self.store.products if p.id == pid), None)
        if p and QMessageBox.question(self, APP, f'Xoá "{p.name}" khỏi giỏ hàng?') == QMessageBox.StandardButton.Yes:
            self.store.remove(p)
            self.cache.pop(p.path, None)
            self._refresh_items()
            self._fill_editor()

    def _adjust(self, key, v):
        setattr(self.store.adjust[self.part], key, v / 100)
        self._slider_text(key)
        self.store.changed()
        if self.still:
            self._render_still()

    def _flip(self, on):
        self.store.adjust[self.part].flip = on
        self.store.changed()
        if self.still:
            self._render_still()

    def _reset_adjust(self):
        self.store.adjust[self.part].reset()
        self.store.changed()
        self._fill_editor()
        if self.still:
            self._render_still()

    def _import(self):
        path, _ = QFileDialog.getOpenFileName(self, "Chọn ảnh sản phẩm", "", "Ảnh (*.png *.jpg *.jpeg *.webp *.bmp)")
        if not path:
            return
        img = imgtools.load_any(path)
        if img is None:
            QMessageBox.warning(self, APP, "Không đọc được ảnh")
            return
        n = len(self.store.items_for(self.part)) + 1
        dlg = ImportDialog(self, img, self.part, f"{PART_ITEM[self.part].split(' (')[0].split(' /')[0]} {n}")
        if dlg.exec() == QDialog.DialogCode.Accepted:
            name, part, result = dlg.values()
            if result is not None and result.size:
                self.store.add_product(name, part, imgtools.encode_png(result))
                self._refresh_items()
                self._select_part(part)

    def _backdrops(self):
        BackdropDialog(self, self.store, self._refresh_items).exec()

    def _set_mirror(self, on):
        self.worker.mirror = on

    # ------------------------------------------------------------ modes
    def _set_mode(self, mode: str):
        if self.recording_path:
            self._stop_recording()
        self.mode = mode
        for b in self.mode_group.buttons():
            b.setChecked(b.text() == {"video": "VIDEO", "photo": "ẢNH", "still": "ẢNH TĨNH"}[mode])
        still = mode == "still"
        self.still_bar.setVisible(still)
        self.cam_box.setEnabled(not still)
        if not still:
            self.still = None
            self.worker.paused = False
        else:
            self.worker.paused = True
            self.view.hint = "Chọn ảnh có sẵn hoặc chụp toàn thân bằng webcam"
            self.view.update()
        self._style_shutter()

    def _style_shutter(self):
        if self.mode == "video":
            inner = "#FF3B30"
            radius = 10 if self.recording_path else 30
            size = 26 if self.recording_path else 60
            self.shutter.setStyleSheet(
                f"QPushButton{{border:4px solid white;border-radius:38px;background:qradialgradient(cx:0.5,cy:0.5,"
                f"radius:{size / 76:.2f},fx:0.5,fy:0.5,stop:0.95 {inner},stop:1 transparent);}}")
            self.shutter.setToolTip("Quay / dừng (Space)")
        else:
            self.shutter.setStyleSheet(
                "QPushButton{border:4px solid white;border-radius:38px;background:qradialgradient(cx:0.5,cy:0.5,"
                "radius:0.42,fx:0.4,fy:0.4,stop:0 #FFFFFF,stop:0.9 #E9E6FF,stop:1 transparent);}")
            self.shutter.setToolTip("Lưu ảnh (Space)" if self.mode == "still" else "Chụp ảnh (Space)")

    def _shutter(self):
        if self.mode == "photo":
            self._save_photo(self.worker.last_clean)
        elif self.mode == "video":
            self._stop_recording() if self.recording_path else self._start_recording()
        elif self.still:
            self._save_photo(self.still["out"])

    def _save_photo(self, img):
        if img is None:
            self.view.toast("Chưa có hình")
            return
        os.makedirs(OUT_DIR, exist_ok=True)
        path = os.path.join(OUT_DIR, f"tryon_{datetime.now():%Y%m%d_%H%M%S}.jpg")
        with open(path, "wb") as f:
            f.write(imgtools.encode_jpg(img))
        self.view.do_flash()
        self.view.toast(f"Đã lưu {os.path.basename(path)} vào Pictures\\TryOn")

    def _start_recording(self):
        os.makedirs(OUT_DIR, exist_ok=True)
        path = os.path.join(OUT_DIR, f"tryon_{datetime.now():%Y%m%d_%H%M%S}.mp4")
        if not self.worker.start_recording(path):
            self.view.toast("Không bắt đầu quay được")
            return
        self.recording_path = path
        self.rec_start = time.monotonic()
        self.rec_timer.start()
        self._rec_tick()
        self._style_shutter()

    def _stop_recording(self):
        self.worker.stop_recording()
        path, self.recording_path = self.recording_path, None
        self.rec_timer.stop()
        self.view.rec_text = ""
        self._style_shutter()
        if path:
            self.view.toast(f"Đã lưu video {os.path.basename(path)} vào Pictures\\TryOn")

    def _rec_tick(self):
        s = int(time.monotonic() - self.rec_start)
        self.view.rec_text = f"● {s // 60:02d}:{s % 60:02d}"
        self.view.update()

    # ------------------------------------------------------------ still photo
    def _still_from_file(self):
        path, _ = QFileDialog.getOpenFileName(self, "Chọn ảnh toàn thân", "", "Ảnh (*.png *.jpg *.jpeg *.webp *.bmp)")
        if not path:
            return
        data = np.fromfile(path, dtype=np.uint8)
        img = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if img is None:
            QMessageBox.warning(self, APP, "Không đọc được ảnh")
            return
        h, w = img.shape[:2]
        s = 2048 / max(h, w)
        if s < 1:
            img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
        self._load_still(img)

    def _still_from_webcam(self):
        self.worker.paused = False
        self.view.hint = "Lùi ra xa để camera thấy cả người"
        self._count = 5
        self.view.countdown = self._count
        self.view.update()

        def tick():
            self._count -= 1
            self.view.countdown = self._count
            self.view.update()
            if self._count <= 0:
                timer.stop()
                raw = self.worker.last_raw
                self.worker.paused = True
                self.view.do_flash()
                if raw is not None:
                    self._load_still(raw.copy())

        timer = QTimer(self, interval=1000, timeout=tick)
        timer.start()

    def _load_still(self, img: np.ndarray):
        if self.still_engine is None:
            model = os.path.join(resource_dir(), "models", "pose_landmarker_full.task")
            self.still_engine = engine.PoseEngine(model, video=False)
        pts, mask = self.still_engine.detect(img)
        self.still = {"img": img, "pts": pts, "mask": mask, "out": img}
        self.view.hint = "" if pts else "Không tìm thấy người trong ảnh"
        self._render_still()

    def _render_still(self):
        s = self.still
        if not s:
            return
        need = self.store.needs_mask()
        s["out"] = engine.compose(s["img"], s["pts"], self.worker.items, self.store.adjust,
                                  s["mask"] if need else None, self.worker.backdrop)
        shown = s["out"]
        if self.part and s["pts"]:
            shown = shown.copy()
            engine.draw_focus(shown, s["pts"], self.part, 1.0)
        self.view.set_image(bgr_to_qimage(shown))

    # ------------------------------------------------------------ misc
    def _on_frame(self, img: QImage):
        if not self.worker.paused:
            self.view.set_image(img)

    def _on_person(self, found: bool):
        if self.mode != "still":
            hint = "" if found else "Đang tìm người… lùi ra xa để camera thấy cả người"
            if hint != self.view.hint:
                self.view.hint = hint
                self.view.update()

    def _hide_ui(self, hide: bool):
        for w in (self.top, self.bottom, self.panel):
            w.setVisible(not hide)
        self.worker.focus = None if hide else self.part
        self.show_ui_btn.setVisible(hide)
        self.show_ui_btn.move(self.view.width() - 52, 12)
        if hide:
            self.showFullScreen()
        else:
            self.showMaximized()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self.show_ui_btn.move(self.view.width() - 52, 12)

    def closeEvent(self, e):
        if self.recording_path:
            self._stop_recording()
        self.worker.stop()
        if self.still_engine:
            self.still_engine.close()
        super().closeEvent(e)


def main():
    app = QApplication(sys.argv)
    app.setApplicationName(APP)
    app.setStyleSheet(STYLE)
    icon = os.path.join(resource_dir(), "icon.png")
    if os.path.exists(icon):
        app.setWindowIcon(QIcon(icon))
    w = MainWindow()
    w.resize(1400, 860)
    w.showMaximized()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
