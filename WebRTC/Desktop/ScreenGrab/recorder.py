"""Screen recording via ffmpeg gdigrab, with optional microphone and/or device (loopback) audio."""
from __future__ import annotations

import os
import subprocess
import time

from ffmpegutil import ffmpeg_path

_CREATE_NO_WINDOW = 0x08000000


def _out_dir() -> str:
    d = os.path.join(os.path.expanduser("~"), "Videos", "ScreenGrab")
    os.makedirs(d, exist_ok=True)
    return d


class Recorder:
    def __init__(self):
        self.proc: subprocess.Popen | None = None
        self.path: str | None = None

    @property
    def is_recording(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self, region=None, fps=30, bitrate_mbps=12, audio_names: list[str] | None = None) -> str:
        """region = (left, top, width, height) or None for full screen. audio_names = dshow device names."""
        exe = ffmpeg_path()
        audio_names = [a for a in (audio_names or []) if a]
        self.path = os.path.join(_out_dir(), time.strftime("ScreenGrab_%Y%m%d_%H%M%S.mp4"))

        args = [exe, "-y", "-f", "gdigrab", "-framerate", str(fps)]
        if region is not None:
            l, t, w, h = region
            # gdigrab needs even dimensions for yuv420p
            w -= w % 2
            h -= h % 2
            args += ["-offset_x", str(l), "-offset_y", str(t), "-video_size", f"{w}x{h}"]
        args += ["-i", "desktop"]

        for name in audio_names:
            args += ["-f", "dshow", "-i", f"audio={name}"]

        if len(audio_names) == 0:
            args += ["-map", "0:v"]
        elif len(audio_names) == 1:
            args += ["-map", "0:v", "-map", "1:a"]
        else:
            args += ["-filter_complex", "[1:a][2:a]amix=inputs=2:duration=longest[aout]",
                     "-map", "0:v", "-map", "[aout]"]

        args += ["-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-b:v", f"{bitrate_mbps}M"]
        if audio_names:
            args += ["-c:a", "aac", "-b:a", "160k"]
        args += [self.path]

        self.proc = subprocess.Popen(
            args, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=_CREATE_NO_WINDOW,
        )
        return self.path

    def stop(self) -> str | None:
        """Ask ffmpeg to finalize the file (writes the moov atom), return the saved path."""
        p = self.proc
        if p is None:
            return None
        try:
            if p.poll() is None:
                try:
                    p.stdin.write(b"q")  # graceful quit → valid mp4
                    p.stdin.flush()
                except Exception:
                    pass
                try:
                    p.wait(timeout=8)
                except Exception:
                    p.terminate()
                    try:
                        p.wait(timeout=3)
                    except Exception:
                        p.kill()
        finally:
            self.proc = None
        return self.path
