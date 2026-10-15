"""Pose detection, smoothing, automatic fit and compositing for TryOn.

Mirrors lib/outfit_painter.dart of the mobile app: same 33 BlazePose
landmarks (MediaPipe uses the ML Kit order), same placement maths, same
back-to-front order. Images are BGR / BGRA numpy arrays.
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass

import cv2
import mediapipe as mp
import numpy as np

from store import PARTS, PartAdjust

# Landmark indices (identical in ML Kit and MediaPipe).
NOSE = 0
L_EYE_IN, L_EYE, L_EYE_OUT, R_EYE_IN, R_EYE, R_EYE_OUT = 1, 2, 3, 4, 5, 6
L_EAR, R_EAR, L_MOUTH, R_MOUTH = 7, 8, 9, 10
L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW = 11, 12, 13, 14
L_HIP, R_HIP, L_KNEE, R_KNEE, L_ANKLE, R_ANKLE = 23, 24, 25, 26, 27, 28
L_HEEL, R_HEEL, L_FOOT, R_FOOT = 29, 30, 31, 32


# --------------------------------------------------------------- detection

class PoseEngine:
    """MediaPipe PoseLandmarker: 33 points + person mask in one pass."""

    def __init__(self, model_path: str, video: bool = True):
        v = mp.tasks.vision
        self._video = video
        opts = v.PoseLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=model_path),
            running_mode=v.RunningMode.VIDEO if video else v.RunningMode.IMAGE,
            output_segmentation_masks=True,
        )
        self._lm = v.PoseLandmarker.create_from_options(opts)
        self._t0 = time.monotonic()
        self._last_ts = -1

    def detect(self, bgr: np.ndarray):
        """Returns (points {index: (x, y)} in pixels, mask float HxW or None)."""
        h, w = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        if self._video:
            ts = int((time.monotonic() - self._t0) * 1000)
            if ts <= self._last_ts:
                ts = self._last_ts + 1
            self._last_ts = ts
            res = self._lm.detect_for_video(image, ts)
        else:
            res = self._lm.detect(image)
        if not res.pose_landmarks:
            return {}, None
        pts = {}
        for i, p in enumerate(res.pose_landmarks[0]):
            vis = p.visibility if p.visibility is not None else 1.0
            pres = p.presence if p.presence is not None else 1.0
            if vis >= 0.5 and pres >= 0.5:
                pts[i] = (p.x * w, p.y * h)
        mask = None
        if res.segmentation_masks:
            mask = res.segmentation_masks[0].numpy_view().astype(np.float32)
            if mask.ndim == 3:
                mask = mask[:, :, 0]
        return pts, mask

    def close(self):
        self._lm.close()


class PoseTracker:
    """Adaptive exponential smoothing; keeps briefly lost points 350 ms."""

    HOLD = 0.35

    def __init__(self):
        self.pts: dict[int, tuple[float, float]] = {}
        self.seen: dict[int, float] = {}

    def reset(self):
        self.pts.clear()
        self.seen.clear()

    def update(self, raw: dict, width: int):
        now = time.monotonic()
        fast = width * 0.06
        for i, (x, y) in raw.items():
            old = self.pts.get(i)
            if old is None or now - self.seen.get(i, 0) > self.HOLD:
                self.pts[i] = (x, y)
            else:
                d = math.hypot(x - old[0], y - old[1])
                a = min(0.9, max(0.35, d / fast))
                self.pts[i] = (old[0] + (x - old[0]) * a, old[1] + (y - old[1]) * a)
            self.seen[i] = now
        for i in [i for i, t in self.seen.items() if now - t > self.HOLD]:
            self.seen.pop(i, None)
            self.pts.pop(i, None)
        return dict(self.pts)


# --------------------------------------------------------------- placement

@dataclass
class Placement:
    anchor: tuple[float, float]
    angle: float
    width: float
    height: float | None = None  # None = keep aspect
    anchor_y: float = 0.5
    flip: bool = False


def _mid(p, a, b):
    if a in p and b in p:
        return ((p[a][0] + p[b][0]) / 2, (p[a][1] + p[b][1]) / 2)
    return None


def _dist(p, a, b):
    if a in p and b in p:
        return math.hypot(p[a][0] - p[b][0], p[a][1] - p[b][1])
    return None


def _line_angle(p, a, b):
    if a in p and b in p:
        dx, dy = p[b][0] - p[a][0], p[b][1] - p[a][1]
        if dx < 0:
            dx, dy = -dx, -dy
        return math.atan2(dy, dx)
    return None


def _up(angle):
    return (math.sin(angle), -math.cos(angle))


def _add(a, b, k=1.0):
    return (a[0] + b[0] * k, a[1] + b[1] * k)


def _len(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _head_width(p):
    d = _dist(p, L_EAR, R_EAR)
    if d is not None:
        return d
    d = _dist(p, L_EYE_OUT, R_EYE_OUT)
    return d * 1.8 if d is not None else None


def _head_angle(p):
    a = _line_angle(p, R_EYE, L_EYE)
    return a if a is not None else _line_angle(p, R_EAR, L_EAR)


def placements_for(part: str, p: dict) -> list[Placement]:
    if part in ("head", "face", "eyes", "halo"):
        hw, eyes, ang = _head_width(p), _mid(p, L_EYE, R_EYE), _head_angle(p)
        if hw is None or eyes is None or ang is None:
            return []
        up = _up(ang)
        if part == "head":
            return [Placement(_add(eyes, up, hw * 0.42), ang, hw * 1.75, anchor_y=0.82)]
        if part == "eyes":
            return [Placement(eyes, ang, hw * 1.0)]
        if part == "halo":
            return [Placement(_add(eyes, up, hw * 1.35), ang, hw * 1.3)]
        mouth = _mid(p, L_MOUTH, R_MOUTH)
        if NOSE not in p or mouth is None:
            return []
        nose = p[NOSE]
        anchor = (nose[0] + (mouth[0] - nose[0]) * 0.55, nose[1] + (mouth[1] - nose[1]) * 0.55)
        return [Placement(anchor, ang, hw * 1.05, anchor_y=0.45)]

    if part in ("top", "dress", "wings"):
        sw, s_mid = _dist(p, L_SHOULDER, R_SHOULDER), _mid(p, L_SHOULDER, R_SHOULDER)
        ang = _line_angle(p, R_SHOULDER, L_SHOULDER)
        if sw is None or s_mid is None or ang is None:
            return []
        up = _up(ang)
        hips = _mid(p, L_HIP, R_HIP)
        torso = _len(hips, s_mid) if hips else sw * 1.3
        if part == "top":
            return [Placement(_add(s_mid, up, sw * 0.2), ang, sw * 1.6, torso * 1.25 + sw * 0.2, 0)]
        if part == "wings":
            return [Placement(_add(s_mid, up, -torso * 0.22), ang, sw * 4.2, anchor_y=0.45)]
        feet = _mid(p, L_ANKLE, R_ANKLE) or _mid(p, L_HEEL, R_HEEL)
        knees = _mid(p, L_KNEE, R_KNEE)
        if feet:
            length = _len(feet, s_mid) + sw * 0.35
        elif knees:
            length = _len(knees, s_mid) * 1.65
        elif hips:
            length = _len(hips, s_mid) * 3.3
        else:
            length = sw * 4.5
        spread = _dist(p, L_ANKLE, R_ANKLE) or 0
        return [Placement(_add(s_mid, up, sw * 0.12), ang, max(sw * 2.55, spread + sw * 1.3), length + sw * 0.12, 0)]

    if part in ("bottom", "skirt"):
        hw, h_mid = _dist(p, L_HIP, R_HIP), _mid(p, L_HIP, R_HIP)
        ang = _line_angle(p, R_HIP, L_HIP)
        if hw is None or h_mid is None or ang is None:
            return []
        up = _up(ang)
        ankles, knees = _mid(p, L_ANKLE, R_ANKLE), _mid(p, L_KNEE, R_KNEE)
        if part == "bottom":
            leg = _len(ankles, h_mid) if ankles else (_len(knees, h_mid) * 2.05 if knees else hw * 2.8)
            spread = max(_dist(p, L_ANKLE, R_ANKLE) or 0, _dist(p, L_KNEE, R_KNEE) or 0)
            return [Placement(_add(h_mid, up, hw * 0.35), ang, max(hw * 2.1, spread + hw * 1.1),
                              leg * 1.08 + hw * 0.35, 0)]
        hem = _len(knees, h_mid) * 1.2 if knees else (_len(ankles, h_mid) * 0.6 if ankles else hw * 2.2)
        spread = _dist(p, L_KNEE, R_KNEE) or 0
        return [Placement(_add(h_mid, up, hw * 0.4), ang, max(hw * 3.0, spread + hw * 1.6), hem + hw * 0.4, 0)]

    if part == "feet":
        hw = _dist(p, L_HIP, R_HIP) or _dist(p, L_ANKLE, R_ANKLE) or _dist(p, L_KNEE, R_KNEE)
        hip_x = (_mid(p, L_HIP, R_HIP) or _mid(p, L_ANKLE, R_ANKLE) or (None,))[0]
        out = []
        for heel_i, toe_i, ankle_i in ((L_HEEL, L_FOOT, L_ANKLE), (R_HEEL, R_FOOT, R_ANKLE)):
            if heel_i not in p or toe_i not in p:
                continue
            heel, toe = p[heel_i], p[toe_i]
            ankle = p.get(ankle_i, heel)
            foot = _len(toe, heel)
            base = hw or foot * 1.4
            mid = ((heel[0] + toe[0]) / 2, (heel[1] + toe[1]) / 2)
            center = (mid[0] + (ankle[0] - mid[0]) * 0.25, mid[1] + (ankle[1] - mid[1]) * 0.25)
            side_view = foot > base * 0.45
            flip = toe[0] < heel[0] if side_view else (hip_x is not None and center[0] < hip_x)
            ang = 0.0
            if side_view:
                dx, dy = toe[0] - heel[0], toe[1] - heel[1]
                if dx < 0:
                    dx, dy = -dx, -dy
                ang = math.atan2(dy, dx)
            out.append(Placement(center, ang, max(foot * 1.45, base * 0.75), anchor_y=0.55, flip=flip))
        return out
    return []


FOCUS_POINTS = {
    "head": [L_EAR, R_EAR, L_EYE, R_EYE, NOSE],
    "halo": [L_EAR, R_EAR, L_EYE, R_EYE],
    "face": [NOSE, L_MOUTH, R_MOUTH],
    "eyes": [L_EYE_OUT, L_EYE, L_EYE_IN, R_EYE_IN, R_EYE, R_EYE_OUT],
    "wings": [L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW],
    "top": [L_SHOULDER, R_SHOULDER, L_HIP, R_HIP],
    "bottom": [L_HIP, R_HIP, L_KNEE, R_KNEE, L_ANKLE, R_ANKLE],
    "skirt": [L_HIP, R_HIP, L_KNEE, R_KNEE],
    "dress": [L_SHOULDER, R_SHOULDER, L_HIP, R_HIP, L_ANKLE, R_ANKLE],
    "feet": [L_ANKLE, R_ANKLE, L_HEEL, R_HEEL, L_FOOT, R_FOOT],
}

# Back-to-front; wings sit behind the person.
ORDER = ["wings", "bottom", "skirt", "dress", "top", "feet", "face", "eyes", "head", "halo"]


# --------------------------------------------------------------- compositing

def _blend(dst: np.ndarray, src_bgr: np.ndarray, alpha: np.ndarray):
    """dst = src*alpha + dst*(1-alpha) in place; alpha float HxW in 0..1."""
    a = alpha[..., None]
    dst[:] = (src_bgr.astype(np.float32) * a + dst.astype(np.float32) * (1 - a)).astype(np.uint8)


def _draw_item(frame: np.ndarray, item: np.ndarray, pl: Placement, adj: PartAdjust,
               behind: np.ndarray | None):
    h0, w0 = item.shape[:2]
    w = pl.width * adj.scale * adj.width
    h = (pl.height if pl.height is not None else pl.width * h0 / w0) * adj.scale * adj.height
    if w < 2 or h < 2:
        return
    # rect centre in the rotated item frame
    cx = adj.dx * w
    cy = -pl.anchor_y * h + adj.dy * h + h / 2
    flip = -1.0 if pl.flip != adj.flip else 1.0
    sx, sy = w / w0 * flip, h / h0
    ca, sa = math.cos(pl.angle), math.sin(pl.angle)
    # p_frame = anchor + R*(c + S*(p_img - img_centre))
    a11, a12 = ca * sx, -sa * sy
    a21, a22 = sa * sx, ca * sy
    tx = pl.anchor[0] + ca * cx - sa * cy - (a11 * w0 / 2 + a12 * h0 / 2)
    ty = pl.anchor[1] + sa * cx + ca * cy - (a21 * w0 / 2 + a22 * h0 / 2)
    corners = np.array([[0, 0], [w0, 0], [0, h0], [w0, h0]], np.float32)
    xs = a11 * corners[:, 0] + a12 * corners[:, 1] + tx
    ys = a21 * corners[:, 0] + a22 * corners[:, 1] + ty
    fh, fw = frame.shape[:2]
    x0, x1 = max(0, int(xs.min())), min(fw, int(math.ceil(xs.max())))
    y0, y1 = max(0, int(ys.min())), min(fh, int(math.ceil(ys.max())))
    if x1 <= x0 or y1 <= y0:
        return
    m = np.array([[a11, a12, tx - x0], [a21, a22, ty - y0]], np.float32)
    warped = cv2.warpAffine(item, m, (x1 - x0, y1 - y0), flags=cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0, 0))
    alpha = warped[:, :, 3].astype(np.float32) / 255.0
    if behind is not None:
        alpha *= 1.0 - behind[y0:y1, x0:x1]
    _blend(frame[y0:y1, x0:x1], warped[:, :, :3], alpha)


def person_alpha(mask: np.ndarray, shape) -> np.ndarray:
    """Mask resized to the frame, edge steepened like the mobile app."""
    h, w = shape[:2]
    if mask.shape[:2] != (h, w):
        mask = cv2.resize(mask, (w, h), interpolation=cv2.INTER_LINEAR)
    return np.clip((mask - 0.25) / 0.5, 0, 1).astype(np.float32)


def cover(img: np.ndarray, w: int, h: int) -> np.ndarray:
    """Resize/crop a background to fill w x h (BoxFit.cover)."""
    ih, iw = img.shape[:2]
    s = max(w / iw, h / ih)
    r = cv2.resize(img, (max(w, int(iw * s + 0.5)), max(h, int(ih * s + 0.5))), interpolation=cv2.INTER_AREA)
    y, x = (r.shape[0] - h) // 2, (r.shape[1] - w) // 2
    return r[y:y + h, x:x + w, :3]


def compose(frame: np.ndarray, pts: dict, items: dict, adjust: dict,
            mask: np.ndarray | None = None, backdrop: np.ndarray | None = None) -> np.ndarray:
    """Returns a new frame with backdrop + worn items."""
    out = frame.copy()
    person = person_alpha(mask, out.shape) if mask is not None else None
    if backdrop is not None and person is not None:
        bg = backdrop if backdrop.shape[:2] == out.shape[:2] else cover(backdrop, out.shape[1], out.shape[0])
        _blend(out, bg, 1.0 - person)
    if not pts:
        return out
    for part in ORDER:
        item = items.get(part)
        if item is None:
            continue
        behind = person if part == "wings" else None
        for pl in placements_for(part, pts):
            _draw_item(out, item, pl, adjust.get(part) or PartAdjust(), behind)
    return out


def draw_focus(frame: np.ndarray, pts: dict, part: str, pulse: float):
    """Glowing box + dots around the body area of the selected part."""
    sel = [pts[i] for i in FOCUS_POINTS.get(part, []) if i in pts]
    if not sel:
        return
    unit = frame.shape[1] / 100
    xs, ys = [p[0] for p in sel], [p[1] for p in sel]
    pad = unit * 4
    x0, y0, x1, y1 = int(min(xs) - pad), int(min(ys) - pad), int(max(xs) + pad), int(max(ys) + pad)
    glow = frame.copy()
    cv2.rectangle(glow, (x0, y0), (x1, y1), (255, 255, 255), max(2, int(unit * (0.6 + 0.4 * pulse))), cv2.LINE_AA)
    cv2.addWeighted(glow, 0.6 + 0.3 * pulse, frame, 0.4 - 0.3 * pulse, 0, frame)
    for x, y in sel:
        cv2.circle(frame, (int(x), int(y)), max(3, int(unit * 0.8)), (255, 231, 91), -1, cv2.LINE_AA)


__all__ = ["PoseEngine", "PoseTracker", "compose", "draw_focus", "cover", "PARTS"]
