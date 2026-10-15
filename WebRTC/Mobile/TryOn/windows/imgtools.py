"""Product image clean-up (port of lib/image_tools.dart).

All functions take/return BGRA uint8 numpy arrays.
"""
from __future__ import annotations

import cv2
import numpy as np


def load_any(path: str) -> np.ndarray | None:
    """Reads an image (unicode paths OK) as BGRA, capped at 1000 px."""
    data = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_UNCHANGED)
    if img is None:
        return None
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGRA)
    elif img.shape[2] == 3:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2BGRA)
    h, w = img.shape[:2]
    s = 1000 / max(h, w)
    if s < 1:
        img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    return img


def _border_connected(cand: np.ndarray) -> np.ndarray:
    """Keeps only True regions of `cand` that touch the image border."""
    n, labels = cv2.connectedComponents(cand.astype(np.uint8), connectivity=4)
    if n <= 1:
        return np.zeros_like(cand)
    edge = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    edge = edge[edge != 0]
    return np.isin(labels, edge)


def _dist(rgb: np.ndarray, color_bgr) -> np.ndarray:
    d = rgb.astype(np.int32) - np.array(color_bgr, np.int32)
    return np.sqrt((d * d).sum(axis=2)).astype(np.float32)


def prepare(img: np.ndarray, auto: bool = True, tolerance: int = 42,
            keys: list[tuple[int, int, int]] | None = None, border_only: bool = False) -> np.ndarray:
    """Erases the background (auto from border colour and/or picked colours),
    softens the edge and trims empty margins."""
    out = img.copy()
    bgr = out[:, :, :3]
    alpha = out[:, :, 3].astype(np.float32)
    h, w = alpha.shape
    border_transparent = np.concatenate([alpha[0], alpha[-1], alpha[:, 0], alpha[:, -1]])
    already_cut = (border_transparent < 200).mean() > 0.3

    if auto and not already_cut:
        ring = np.concatenate([bgr[0], bgr[-1], bgr[:, 0], bgr[:, -1]])
        bg = ring.mean(axis=0)
        d = _dist(bgr, bg)
        removed = _border_connected(d <= tolerance)
        alpha[removed] = 0
        # feather: kept pixels touching removed ones get partial alpha
        edge = cv2.dilate(removed.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool) & ~removed
        alpha[edge] = np.minimum(alpha[edge], np.clip(d[edge] / (tolerance * 2.2), 0, 1) * 255)

    if keys:
        d = np.min(np.stack([_dist(bgr, k) for k in keys]), axis=0)
        soft = tolerance * 1.6
        if border_only:
            allowed = _border_connected(d <= tolerance)
            allowed = cv2.dilate(allowed.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool) & (d < soft)
        else:
            allowed = np.ones_like(d, dtype=bool)
        hard = allowed & (d <= tolerance)
        alpha[hard] = 0
        band = allowed & (d > tolerance) & (d < soft)
        alpha[band] = np.minimum(alpha[band], (d[band] - tolerance) / (soft - tolerance) * 255)

    out[:, :, 3] = alpha.astype(np.uint8)
    ys, xs = np.nonzero(out[:, :, 3] > 16)
    if len(xs):
        out = out[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return out


def encode_png(img: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", img)
    return buf.tobytes() if ok else b""


def encode_jpg(img: np.ndarray, quality: int = 92) -> bytes:
    ok, buf = cv2.imencode(".jpg", img[:, :, :3], [cv2.IMWRITE_JPEG_QUALITY, quality])
    return buf.tobytes() if ok else b""


def checker(w: int, h: int, s: int = 10) -> np.ndarray:
    yy, xx = np.mgrid[0:h, 0:w]
    c = (((yy // s) + (xx // s)) % 2).astype(np.uint8)
    out = np.where(c[..., None] == 1, np.uint8(220), np.uint8(242)).repeat(3, axis=2)
    return out.astype(np.uint8)


def over_checker(img: np.ndarray) -> np.ndarray:
    """BGRA → BGR composited over a checkerboard (for previews)."""
    h, w = img.shape[:2]
    bg = checker(w, h).astype(np.float32)
    a = img[:, :, 3:4].astype(np.float32) / 255
    return (img[:, :, :3] * a + bg * (1 - a)).astype(np.uint8)
