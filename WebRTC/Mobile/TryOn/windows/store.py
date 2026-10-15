"""Local cart: products, worn items, per-part adjustments and backdrops.

Same JSON layout as the mobile app (lib/models.dart). Stored in
%APPDATA%\\TryOn (cart.json + products\\*.png / *.jpg).
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import time
from dataclasses import asdict, dataclass, field

# (key, bar label, item label) in the mobile app order.
PARTS = [
    ("head", "Đầu", "Mũ / nón"),
    ("halo", "Hào quang", "Vòng thiên sứ / trang trí trên đầu"),
    ("face", "Mặt", "Khẩu trang"),
    ("eyes", "Mắt", "Kính"),
    ("wings", "Cánh", "Cánh"),
    ("top", "Áo", "Áo"),
    ("bottom", "Quần", "Quần"),
    ("skirt", "Chân váy", "Chân váy"),
    ("dress", "Váy dài", "Váy liền dài (từ vai tới chân)"),
    ("feet", "Giày", "Giày"),
]
PART_KEYS = [p[0] for p in PARTS]
PART_LABEL = {k: label for k, label, _ in PARTS}
PART_ITEM = {k: item for k, _, item in PARTS}

# Wearing one takes the others off (a long dress replaces top/pants/skirt).
CLASHES = {
    "top": ["dress"],
    "bottom": ["skirt", "dress"],
    "skirt": ["bottom", "dress"],
    "dress": ["top", "bottom", "skirt"],
}

SAMPLES = {
    "cap": ("head", "Mũ lưỡi trai (mẫu)"),
    "mask": ("face", "Khẩu trang y tế (mẫu)"),
    "glasses": ("eyes", "Kính râm (mẫu)"),
    "tshirt": ("top", "Áo thun đỏ (mẫu)"),
    "jeans": ("bottom", "Quần jeans (mẫu)"),
    "skirt": ("skirt", "Chân váy xoè (mẫu)"),
    "dress": ("dress", "Váy dài dạ hội (mẫu)"),
    "wings": ("wings", "Cánh thiên thần (mẫu)"),
    "halo": ("halo", "Vòng thiên sứ (mẫu)"),
    "sneaker": ("feet", "Giày sneaker (mẫu)"),
}
SAMPLE_BACKDROPS = {
    "bg_heaven": "Thiên đường",
    "bg_studio": "Studio hồng",
    "bg_sunset": "Hoàng hôn",
    "bg_city": "Phố đêm",
}


def resource_dir() -> str:
    """Bundled files: PyInstaller temp dir, or the source tree."""
    if getattr(sys, "frozen", False):
        return sys._MEIPASS  # type: ignore[attr-defined]
    return os.path.dirname(os.path.abspath(__file__))


def samples_dir() -> str:
    bundled = os.path.join(resource_dir(), "samples")
    if os.path.isdir(bundled):
        return bundled
    # Running from source: share the mobile app's assets.
    return os.path.join(resource_dir(), "..", "mobile", "assets", "samples")


@dataclass
class PartAdjust:
    scale: float = 1.0
    width: float = 1.0
    height: float = 1.0
    dx: float = 0.0
    dy: float = 0.0
    flip: bool = False

    def is_default(self) -> bool:
        return self == PartAdjust()

    def reset(self):
        self.scale = self.width = self.height = 1.0
        self.dx = self.dy = 0.0
        self.flip = False


@dataclass
class Product:
    id: str
    name: str
    part: str
    path: str


@dataclass
class Backdrop:
    id: str
    name: str
    path: str


@dataclass
class Store:
    products: list[Product] = field(default_factory=list)
    worn: dict[str, str] = field(default_factory=dict)
    adjust: dict[str, PartAdjust] = field(default_factory=lambda: {k: PartAdjust() for k in PART_KEYS})
    backdrops: list[Backdrop] = field(default_factory=list)
    backdrop_id: str | None = None
    seeded: set[str] = field(default_factory=set)
    revision: int = 0

    # ---------------------------------------------------------------- files
    @staticmethod
    def base_dir() -> str:
        d = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "TryOn")
        os.makedirs(os.path.join(d, "products"), exist_ok=True)
        return d

    @property
    def product_dir(self) -> str:
        return os.path.join(self.base_dir(), "products")

    @classmethod
    def load(cls) -> "Store":
        s = cls()
        path = os.path.join(cls.base_dir(), "cart.json")
        if os.path.exists(path):
            try:
                with open(path, encoding="utf-8") as f:
                    j = json.load(f)
                s.products = [Product(**p) for p in j.get("products", []) if os.path.exists(p["path"])
                              and p.get("part") in PART_KEYS]
                ids = {p.id for p in s.products}
                s.worn = {k: v for k, v in j.get("worn", {}).items() if k in PART_KEYS and v in ids}
                for k, v in j.get("adjust", {}).items():
                    if k in PART_KEYS:
                        s.adjust[k] = PartAdjust(**{f: v[f] for f in asdict(PartAdjust()) if f in v})
                s.backdrops = [Backdrop(**b) for b in j.get("backdrops", []) if os.path.exists(b["path"])]
                bid = j.get("backdropId")
                s.backdrop_id = bid if any(b.id == bid for b in s.backdrops) else None
                s.seeded = set(j.get("seededSamples", []))
            except Exception:
                pass  # corrupt file: start empty
        s._seed()
        return s

    def save(self):
        j = {
            "products": [asdict(p) for p in self.products],
            "worn": self.worn,
            "adjust": {k: asdict(v) for k, v in self.adjust.items()},
            "backdrops": [asdict(b) for b in self.backdrops],
            "backdropId": self.backdrop_id,
            "seededSamples": sorted(self.seeded),
        }
        with open(os.path.join(self.base_dir(), "cart.json"), "w", encoding="utf-8") as f:
            json.dump(j, f, ensure_ascii=False, indent=1)

    def _seed(self):
        src = samples_dir()
        added = False
        for asset, (part, name) in SAMPLES.items():
            f = os.path.join(src, f"{asset}.png")
            if asset in self.seeded or not os.path.exists(f):
                continue
            dst = os.path.join(self.product_dir, f"sample_{asset}.png")
            shutil.copyfile(f, dst)
            self.products.append(Product(f"sample_{asset}", name, part, dst))
            self.seeded.add(asset)
            added = True
        for asset, name in SAMPLE_BACKDROPS.items():
            f = os.path.join(src, f"{asset}.jpg")
            if asset in self.seeded or not os.path.exists(f):
                continue
            dst = os.path.join(self.product_dir, f"sample_{asset}.jpg")
            shutil.copyfile(f, dst)
            self.backdrops.append(Backdrop(f"sample_{asset}", name, dst))
            self.seeded.add(asset)
            added = True
        if added:
            self.save()

    # -------------------------------------------------------------- changes
    def changed(self):
        self.revision += 1
        self.save()

    def items_for(self, part: str) -> list[Product]:
        return [p for p in self.products if p.part == part]

    def worn_item(self, part: str) -> Product | None:
        pid = self.worn.get(part)
        return next((p for p in self.products if p.id == pid), None)

    def wear(self, part: str, pid: str | None):
        if pid is None:
            self.worn.pop(part, None)
        else:
            self.worn[part] = pid
            for other in CLASHES.get(part, []):
                self.worn.pop(other, None)
        self.changed()

    def add_product(self, name: str, part: str, png_bytes: bytes) -> Product:
        pid = str(int(time.time() * 1e6))
        path = os.path.join(self.product_dir, f"{pid}.png")
        with open(path, "wb") as f:
            f.write(png_bytes)
        p = Product(pid, name, part, path)
        self.products.append(p)
        self.wear(part, pid)
        return p

    def remove(self, p: Product):
        self.products = [e for e in self.products if e.id != p.id]
        if self.worn.get(p.part) == p.id:
            self.worn.pop(p.part)
        self.changed()
        try:
            os.remove(p.path)
        except OSError:
            pass

    @property
    def backdrop(self) -> Backdrop | None:
        return next((b for b in self.backdrops if b.id == self.backdrop_id), None)

    def select_backdrop(self, bid: str | None):
        self.backdrop_id = bid
        self.changed()

    def add_backdrop(self, jpg_bytes: bytes) -> Backdrop:
        bid = f"bg_{int(time.time() * 1e6)}"
        path = os.path.join(self.product_dir, f"{bid}.jpg")
        with open(path, "wb") as f:
            f.write(jpg_bytes)
        b = Backdrop(bid, "Ảnh của tôi", path)
        self.backdrops.append(b)
        self.select_backdrop(bid)
        return b

    def remove_backdrop(self, b: Backdrop):
        self.backdrops = [e for e in self.backdrops if e.id != b.id]
        if self.backdrop_id == b.id:
            self.backdrop_id = None
        self.changed()
        try:
            os.remove(b.path)
        except OSError:
            pass

    def needs_mask(self) -> bool:
        return self.backdrop is not None or "wings" in self.worn
