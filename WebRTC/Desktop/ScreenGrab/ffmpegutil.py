"""Locate the ffmpeg binary (bundled via imageio-ffmpeg) and enumerate DirectShow audio devices."""
import re
import subprocess

_CREATE_NO_WINDOW = 0x08000000  # don't flash a console window on Windows


def ffmpeg_path() -> str:
    """Path to an ffmpeg.exe — the imageio-ffmpeg bundle, or 'ffmpeg' from PATH."""
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return "ffmpeg"


def list_audio_devices() -> list[str]:
    """Names of DirectShow audio input devices (microphones, Stereo Mix, virtual cables...)."""
    exe = ffmpeg_path()
    try:
        p = subprocess.run(
            [exe, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"],
            capture_output=True, text=True, creationflags=_CREATE_NO_WINDOW,
        )
    except Exception:
        return []
    out = (p.stderr or "") + (p.stdout or "")
    names: list[str] = []
    audio_section = False
    for line in out.splitlines():
        if "DirectShow audio devices" in line:
            audio_section = True
            continue
        if "DirectShow video devices" in line:
            audio_section = False
            continue
        if audio_section:
            m = re.search(r'"([^"]+)"', line)
            if m and "Alternative name" not in line:
                names.append(m.group(1))
    return names
