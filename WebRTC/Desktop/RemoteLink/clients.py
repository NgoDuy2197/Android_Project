"""Three ways to view+control an Android screen, all exposing the same Qt signals:

  StreamClient  -> talks to the RemoteLink app's custom H.264 server (lowest latency).
  VncClient     -> talks to any RFB/VNC server (the app's VNC mode, or any VNC server).
  AdbClient     -> no phone app at all: drives the device over `adb` (3rd capture solution).

Each runs its network/decode loop on a background thread and emits frames as QImage.
Input is sent back through normalized (0..1) coordinates so resolution never has to match.
"""
import socket
import struct
import subprocess
import threading
import os
import shutil

from PySide6.QtCore import QObject, Signal
from PySide6.QtGui import QImage

import protocol as P


# ---------------------------------------------------------------- base
class BaseClient(QObject):
    frame = Signal(QImage)
    status = Signal(str)
    closed = Signal()

    def __init__(self):
        super().__init__()
        self._running = False
        self._thread = None

    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._safe_run, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False

    def _safe_run(self):
        try:
            self._run()
        except Exception as e:  # noqa
            self.status.emit(f"Lỗi: {e}")
        finally:
            self.closed.emit()

    def _run(self):
        raise NotImplementedError

    # input API (normalized coords) — overridden where supported
    def touch(self, action, xn, yn, pid=0): ...
    def tap(self, xn, yn): ...
    def swipe(self, x1, y1, x2, y2, dur_ms): ...
    def key(self, keycode): ...
    def text(self, s): ...
    def global_action(self, action): ...
    def command(self, s): ...


# ---------------------------------------------------------------- H.264 decoder helper
class H264Decoder:
    def __init__(self):
        import av  # imported lazily so VNC/ADB-screencap users don't need it
        self._av = av
        self.codec = av.CodecContext.create("h264", "r")

    def feed(self, data: bytes):
        imgs = []
        try:
            for packet in self.codec.parse(data):
                for frame in self.codec.decode(packet):
                    arr = frame.to_ndarray(format="rgb24")
                    h, w, _ = arr.shape
                    qimg = QImage(bytes(arr.data), w, h, 3 * w, QImage.Format_RGB888)
                    imgs.append(qimg.copy())
        except Exception:
            pass
        return imgs


# ---------------------------------------------------------------- custom stream
class StreamClient(BaseClient):
    def __init__(self, host, port, password=""):
        super().__init__()
        self.host, self.port, self.password = host, port, password
        self.sock = None
        self._send_lock = threading.Lock()

    def _send(self, data):
        try:
            with self._send_lock:
                if self.sock:
                    self.sock.sendall(data)
        except Exception:
            pass

    def _run(self):
        self.status.emit(f"Đang kết nối {self.host}:{self.port} (Stream)…")
        self.sock = socket.create_connection((self.host, self.port), timeout=8)
        self.sock.settimeout(None)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        mtype, payload = P.recv_message(self.sock)
        if mtype != P.S_HELLO or payload[:4] != b"RLNK":
            raise ConnectionError("không phải server RemoteLink")
        w, h = struct.unpack(">ii", payload[5:13])
        auth_flag = payload[13]
        self.status.emit(f"Đã kết nối · màn hình {w}x{h}")

        if auth_flag:
            mtype, nonce = P.recv_message(self.sock)
            if mtype != P.S_CHALLENGE:
                raise ConnectionError("thiếu challenge")
            self._send(P.frame(P.C_AUTH, P.auth_response(nonce, self.password)))

        dec = H264Decoder()
        while self._running:
            mtype, payload = P.recv_message(self.sock)
            if mtype == P.S_VIDEO_CONFIG:
                for img in dec.feed(payload):
                    self.frame.emit(img)
            elif mtype == P.S_VIDEO:
                for img in dec.feed(payload[9:]):  # skip [1 flags][8 pts]
                    self.frame.emit(img)
        try:
            self.sock.close()
        except Exception:
            pass

    def touch(self, action, xn, yn, pid=0): self._send(P.input_touch(action, xn, yn, pid))
    def tap(self, xn, yn): self._send(P.input_tap(xn, yn))
    def swipe(self, x1, y1, x2, y2, dur_ms): self._send(P.input_swipe(x1, y1, x2, y2, dur_ms))
    def key(self, keycode): self._send(P.input_key(keycode))
    def text(self, s): self._send(P.input_text(s))
    def global_action(self, action): self._send(P.input_global(action))
    def command(self, s): self._send(P.input_command(s))


# ---------------------------------------------------------------- VNC (RFB 3.8)
class VncClient(BaseClient):
    def __init__(self, host, port, password=""):
        super().__init__()
        self.host, self.port, self.password = host, port, password
        self.sock = None
        self.w = 0
        self.h = 0
        self.fb = None  # numpy HxWx4
        self._send_lock = threading.Lock()

    def _send(self, data):
        try:
            with self._send_lock:
                if self.sock:
                    self.sock.sendall(data)
        except Exception:
            pass

    def _run(self):
        import numpy as np
        self.status.emit(f"Đang kết nối {self.host}:{self.port} (VNC)…")
        self.sock = socket.create_connection((self.host, self.port), timeout=8)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        P.recv_exactly(self.sock, 12)                    # server version
        self.sock.sendall(b"RFB 003.008\n")

        n_sec = P.recv_exactly(self.sock, 1)[0]
        sectypes = P.recv_exactly(self.sock, n_sec)
        if 1 in sectypes and not self.password:
            self.sock.sendall(bytes([1]))                # None
        elif 2 in sectypes:
            self.sock.sendall(bytes([2]))                # VNC auth
            challenge = P.recv_exactly(self.sock, 16)
            self.sock.sendall(self._vnc_des(self.password, challenge))
        else:
            self.sock.sendall(bytes([sectypes[0]]))
        result = struct.unpack(">i", P.recv_exactly(self.sock, 4))[0]
        if result != 0:
            raise ConnectionError("VNC auth thất bại")

        self.sock.sendall(bytes([1]))                    # ClientInit shared
        init = P.recv_exactly(self.sock, 24)
        self.w, self.h = struct.unpack(">HH", init[0:4])
        name_len = struct.unpack(">i", init[20:24])[0]
        P.recv_exactly(self.sock, name_len)
        self.fb = np.zeros((self.h, self.w, 4), dtype=np.uint8)
        self.status.emit(f"Đã kết nối · màn hình {self.w}x{self.h}")

        # ask for Raw encoding only
        self.sock.sendall(struct.pack(">BBHi", 2, 0, 1, 0))  # SetEncodings: Raw(0)
        self._request_update(incremental=0)

        while self._running:
            mtype = P.recv_exactly(self.sock, 1)[0]
            if mtype == 0:
                self._handle_update(np)
                self._request_update(incremental=1)
            elif mtype == 2:
                pass  # Bell
            elif mtype == 3:
                hdr = P.recv_exactly(self.sock, 7)
                length = struct.unpack(">i", hdr[3:7])[0]
                P.recv_exactly(self.sock, length)
        try:
            self.sock.close()
        except Exception:
            pass

    def _request_update(self, incremental):
        self._send(struct.pack(">BBHHHH", 3, incremental, 0, 0, self.w, self.h))

    def _handle_update(self, np):
        P.recv_exactly(self.sock, 1)  # padding
        n_rects = struct.unpack(">H", P.recv_exactly(self.sock, 2))[0]
        for _ in range(n_rects):
            rh = P.recv_exactly(self.sock, 12)
            x, y, w, h = struct.unpack(">HHHH", rh[0:8])
            enc = struct.unpack(">i", rh[8:12])[0]
            if enc != 0:
                continue  # only Raw supported
            data = P.recv_exactly(self.sock, w * h * 4)
            rect = np.frombuffer(data, dtype=np.uint8).reshape((h, w, 4))
            self.fb[y:y + h, x:x + w, :] = rect
        qimg = QImage(bytes(self.fb.data), self.w, self.h, 4 * self.w, QImage.Format_RGBA8888)
        self.frame.emit(qimg.copy())

    @staticmethod
    def _vnc_des(password, challenge):
        # VNC quirk: 8-byte key from password, each byte bit-reversed.
        key = bytearray(password.encode("latin-1")[:8].ljust(8, b"\x00"))
        for i in range(8):
            b = key[i]
            key[i] = int(f"{b:08b}"[::-1], 2)
        try:
            from cryptography.hazmat.primitives.ciphers import Cipher, modes
            try:  # moved to 'decrepit' in cryptography 43+
                from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
            except Exception:
                from cryptography.hazmat.primitives.ciphers.algorithms import TripleDES
            c = Cipher(TripleDES(bytes(key)), modes.ECB())  # 8-byte key == single DES
            enc = c.encryptor()
            return enc.update(challenge) + enc.finalize()
        except Exception as e:
            raise RuntimeError("Cần gói 'cryptography' cho VNC có mật khẩu") from e

    # VNC pointer/key encoding
    def _pointer(self, mask, xn, yn):
        x = max(0, min(self.w - 1, int(xn * self.w)))
        y = max(0, min(self.h - 1, int(yn * self.h)))
        self._send(struct.pack(">BBHH", 5, mask, x, y))

    def touch(self, action, xn, yn, pid=0):
        self._pointer(1 if action in (0, 1) else 0, xn, yn)

    def tap(self, xn, yn):
        self._pointer(1, xn, yn); self._pointer(0, xn, yn)

    def swipe(self, x1, y1, x2, y2, dur_ms):
        self._pointer(1, x1, y1)
        steps = 10
        for i in range(1, steps + 1):
            self._pointer(1, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
        self._pointer(0, x2, y2)

    def _keyevent(self, keysym, down):
        self._send(struct.pack(">BBHI", 4, 1 if down else 0, 0, keysym))

    def key(self, keycode):
        # map a few Android keycodes back to X keysyms
        m = {P.KEY_ENTER: 0xFF0D, P.KEY_DEL: 0xFF08, P.KEY_FORWARD_DEL: 0xFFFF,
             P.KEY_TAB: 0xFF09, P.KEY_DPAD_LEFT: 0xFF51, P.KEY_DPAD_UP: 0xFF52,
             P.KEY_DPAD_RIGHT: 0xFF53, P.KEY_DPAD_DOWN: 0xFF54}
        ks = m.get(keycode)
        if ks:
            self._keyevent(ks, True); self._keyevent(ks, False)

    def text(self, s):
        for ch in s:
            self._keyevent(ord(ch), True); self._keyevent(ord(ch), False)

    def global_action(self, action):
        if action == "back":
            self._keyevent(0xFF1B, True); self._keyevent(0xFF1B, False)


# ---------------------------------------------------------------- ADB (no phone app)
def find_adb():
    exe = shutil.which("adb")
    if exe:
        return exe
    guess = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
    return guess if os.path.exists(guess) else "adb"


class AdbClient(BaseClient):
    """3rd capture solution: drive the device purely over adb — screenrecord H.264 in, `input` out."""
    def __init__(self, serial=""):
        super().__init__()
        self.serial = serial.strip()
        self.adb = find_adb()
        self.w = 0
        self.h = 0
        self.proc = None

    def _adb_args(self, *extra):
        base = [self.adb]
        if self.serial:
            base += ["-s", self.serial]
        return base + list(extra)

    def _run(self):
        self.status.emit("Đang lấy kích thước màn hình (adb)…")
        out = subprocess.run(self._adb_args("shell", "wm", "size"), capture_output=True, text=True, timeout=10).stdout
        # "Physical size: 1080x2400"
        try:
            dims = out.strip().splitlines()[-1].split(":")[-1].strip()
            self.w, self.h = (int(v) for v in dims.lower().split("x"))
        except Exception:
            self.w, self.h = 1080, 2400
        self.status.emit(f"ADB · màn hình {self.w}x{self.h}")

        dec = H264Decoder()
        while self._running:
            self.proc = subprocess.Popen(
                self._adb_args("exec-out", "screenrecord", "--output-format=h264",
                               "--time-limit", "170", "-"),
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            try:
                while self._running:
                    chunk = self.proc.stdout.read(65536)
                    if not chunk:
                        break
                    for img in dec.feed(chunk):
                        self.frame.emit(img)
            finally:
                try:
                    self.proc.kill()
                except Exception:
                    pass

    def stop(self):
        super().stop()
        try:
            if self.proc:
                self.proc.kill()
        except Exception:
            pass

    def _px(self, xn, yn):
        return int(xn * self.w), int(yn * self.h)

    def _shell(self, *args):
        try:
            subprocess.Popen(self._adb_args("shell", *args),
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass

    def tap(self, xn, yn):
        x, y = self._px(xn, yn); self._shell("input", "tap", str(x), str(y))

    def swipe(self, x1, y1, x2, y2, dur_ms):
        ax, ay = self._px(x1, y1); bx, by = self._px(x2, y2)
        self._shell("input", "swipe", str(ax), str(ay), str(bx), str(by), str(int(dur_ms)))

    def touch(self, action, xn, yn, pid=0):
        if action == 0:
            self._down = self._px(xn, yn)
        elif action == 2:
            bx, by = self._px(xn, yn)
            ax, ay = getattr(self, "_down", (bx, by))
            if abs(bx - ax) > 6 or abs(by - ay) > 6:
                self._shell("input", "swipe", str(ax), str(ay), str(bx), str(by), "120")
            else:
                self._shell("input", "tap", str(bx), str(by))

    def key(self, keycode): self._shell("input", "keyevent", str(keycode))
    def text(self, s): self._shell("input", "text", s.replace(" ", "%s"))
    def global_action(self, action):
        codes = {"back": 4, "home": 3, "recents": 187, "power": 26}
        if action in codes:
            self._shell("input", "keyevent", str(codes[action]))
    def command(self, s):
        self._shell(*s.split())
