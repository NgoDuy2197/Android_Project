"""Screenshots (full screen or a region) via mss, saved as PNG."""
from __future__ import annotations

import os
import time

import mss
import mss.tools


def _out_dir() -> str:
    d = os.path.join(os.path.expanduser("~"), "Pictures", "ScreenGrab")
    os.makedirs(d, exist_ok=True)
    return d


def capture(region: tuple[int, int, int, int] | None = None) -> str:
    """Grab the screen (or `region` = (left, top, width, height)) and save a PNG. Returns the path."""
    with mss.mss() as sct:
        if region is None:
            mon = sct.monitors[1]  # primary monitor
            bbox = {"left": mon["left"], "top": mon["top"], "width": mon["width"], "height": mon["height"]}
        else:
            left, top, w, h = region
            bbox = {"left": left, "top": top, "width": w, "height": h}
        img = sct.grab(bbox)
    name = time.strftime("ScreenGrab_%Y%m%d_%H%M%S.png")
    path = os.path.join(_out_dir(), name)
    mss.tools.to_png(img.rgb, img.size, output=path)
    return path
