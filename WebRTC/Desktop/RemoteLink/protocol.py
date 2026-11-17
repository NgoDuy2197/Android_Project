"""Wire helpers for the RemoteLink stream protocol + VNC constants.

Must stay byte-for-byte in sync with the Android side:
  net/StreamServer.kt  and  net/VncServer.kt
"""
import hashlib
import struct

# ---- stream message types (server -> client) ----
S_HELLO = 0x01
S_CHALLENGE = 0x02
S_VIDEO_CONFIG = 0x10
S_VIDEO = 0x11
# ---- stream message types (client -> server) ----
C_AUTH = 0x80
C_INPUT = 0x81
C_KEEPALIVE = 0x82
# ---- input subtypes (inside C_INPUT) ----
IN_TOUCH = 1
IN_TAP = 2
IN_SWIPE = 3
IN_KEY = 4
IN_TEXT = 5
IN_GLOBAL = 6
IN_COMMAND = 7

# Android KeyEvent keycodes we use from the desktop keyboard.
KEY_ENTER = 66
KEY_DEL = 67            # backspace
KEY_FORWARD_DEL = 112   # delete
KEY_TAB = 61
KEY_SPACE = 62
KEY_DPAD_UP = 19
KEY_DPAD_DOWN = 20
KEY_DPAD_LEFT = 21
KEY_DPAD_RIGHT = 22


def frame(msg_type: int, payload: bytes = b"") -> bytes:
    """[1 byte type][4 byte BE length][payload]"""
    return bytes([msg_type]) + struct.pack(">i", len(payload)) + payload


def auth_response(nonce: bytes, password: str) -> bytes:
    return hashlib.sha256(nonce + password.encode("utf-8")).digest()


def input_touch(action: int, xn: float, yn: float, pid: int = 0) -> bytes:
    return frame(C_INPUT, bytes([IN_TOUCH, action]) + struct.pack(">ff", xn, yn) + bytes([pid & 0xFF]))


def input_tap(xn: float, yn: float) -> bytes:
    return frame(C_INPUT, bytes([IN_TAP]) + struct.pack(">ff", xn, yn))


def input_swipe(x1: float, y1: float, x2: float, y2: float, dur_ms: int) -> bytes:
    return frame(C_INPUT, bytes([IN_SWIPE]) + struct.pack(">ffffi", x1, y1, x2, y2, dur_ms))


def input_key(keycode: int) -> bytes:
    return frame(C_INPUT, bytes([IN_KEY]) + struct.pack(">i", keycode))


def input_text(s: str) -> bytes:
    b = s.encode("utf-8")
    return frame(C_INPUT, bytes([IN_TEXT]) + struct.pack(">i", len(b)) + b)


def input_global(action: str) -> bytes:
    b = action.encode("ascii")
    return frame(C_INPUT, bytes([IN_GLOBAL]) + struct.pack(">i", len(b)) + b)


def input_command(s: str) -> bytes:
    b = s.encode("utf-8")
    return frame(C_INPUT, bytes([IN_COMMAND]) + struct.pack(">i", len(b)) + b)


def recv_exactly(sock, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("socket closed")
        buf += chunk
    return bytes(buf)


def recv_message(sock):
    """Return (type, payload) for one server->client frame."""
    head = recv_exactly(sock, 5)
    mtype = head[0]
    (length,) = struct.unpack(">i", head[1:5])
    payload = recv_exactly(sock, length) if length > 0 else b""
    return mtype, payload
