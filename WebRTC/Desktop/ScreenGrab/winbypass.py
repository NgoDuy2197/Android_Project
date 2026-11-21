"""Best-effort attempt to let capture-blocked windows (Telegram Desktop, some players) be recorded.

Windows apps hide themselves from screen capture with SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE).
We walk the top-level windows and try to reset the affinity to WDA_NONE.

[Unverified] This frequently fails cross-process (ACCESS_DENIED) without injecting into the target, and
hardware DRM (Widevine L1, protected video paths) is enforced below the window layer and cannot be
cleared this way. So treat this as "try", not "guarantee".
"""
import ctypes
from ctypes import wintypes

WDA_NONE = 0x0

try:
    user32 = ctypes.windll.user32
except Exception:
    user32 = None


def _title(hwnd) -> str:
    n = user32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def try_unprotect_all() -> tuple[int, int]:
    """Try to clear capture protection on every visible top-level window.
    Returns (attempted, succeeded)."""
    if user32 is None:
        return (0, 0)
    attempted = 0
    succeeded = 0

    EnumWindowsProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def cb(hwnd, _lparam):
        nonlocal attempted, succeeded
        if not user32.IsWindowVisible(hwnd):
            return True
        if not _title(hwnd):
            return True
        attempted += 1
        try:
            if user32.SetWindowDisplayAffinity(hwnd, WDA_NONE):
                succeeded += 1
        except Exception:
            pass
        return True

    try:
        user32.EnumWindows(EnumWindowsProc(cb), 0)
    except Exception:
        pass
    return (attempted, succeeded)
