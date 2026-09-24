"""Sinh icon app ScreenShare (phong cách iOS hiện đại).

Chạy:  python tools/make_icon.py
Xuất ra thư mục gốc dự án:
  - icon.png            : icon đầy đủ 1024px (nền gradient + hình), dùng cho
                          flutter_launcher_icons (icon thường).
  - icon_background.png : nền gradient cho adaptive icon Android.
  - icon_foreground.png : hình (nền trong suốt) nằm trong vùng an toàn của
                          adaptive icon.
Sau đó: dart run flutter_launcher_icons
"""

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

OUT = Path(__file__).resolve().parent.parent
SIZE = 1024
SS = 4  # siêu lấy mẫu để viền mượt
W = SIZE * SS

TOP_LEFT = (74, 222, 128)  # xanh lá sáng
BOTTOM_RIGHT = (13, 116, 110)  # ngọc lam đậm
GLASS = (255, 255, 255)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient_background(w: int) -> Image.Image:
    """Gradient chéo + vầng sáng mềm ở góc trên (kiểu iOS)."""
    small = 256
    img = Image.new("RGB", (small, small))
    px = img.load()
    for y in range(small):
        for x in range(small):
            t = (x + y) / (2 * (small - 1))
            px[x, y] = lerp(TOP_LEFT, BOTTOM_RIGHT, t)
    img = img.resize((w, w), Image.BICUBIC)

    glow = Image.new("L", (w, w), 0)
    d = ImageDraw.Draw(glow)
    r = int(w * 0.55)
    cx, cy = int(w * 0.28), int(w * 0.18)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=90)
    glow = glow.filter(ImageFilter.GaussianBlur(w * 0.12))
    white = Image.new("RGB", (w, w), (255, 255, 255))
    return Image.composite(white, img, glow)


def rounded(d: ImageDraw.ImageDraw, box, r, **kw):
    d.rounded_rectangle([int(v) for v in box], radius=int(r), **kw)


def symbol_layer(w: int, scale: float, offset=(-0.03, -0.02)) -> Image.Image:
    """Hình biểu tượng (RGBA, nền trong suốt) — tọa độ theo tỉ lệ 0..1 của
    khung [scale] đặt giữa canvas."""
    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    box = w * scale
    # offset theo tỉ lệ khung hình, để căn giữa trọng tâm cụm hình.
    ox = (w - box) / 2 + offset[0] * box
    oy = (w - box) / 2 + offset[1] * box

    def P(x, y):
        return ox + x * box, oy + y * box

    def S(v):
        return v * box

    # --- Bóng đổ mềm dưới cả cụm ---
    shadow = Image.new("L", (w, w), 0)
    sd = ImageDraw.Draw(shadow)
    x0, y0 = P(0.10, 0.36)
    x1, y1 = P(0.40, 0.95)
    rounded(sd, (x0, y0 + S(0.03), x1, y1 + S(0.03)), S(0.06), fill=110)
    shadow = shadow.filter(ImageFilter.GaussianBlur(S(0.035)))
    img.paste((6, 60, 50, 255), (0, 0), shadow)

    # --- Màn hình TV kính mờ (phía sau, bên phải) ---
    tv = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    td = ImageDraw.Draw(tv)
    tx0, ty0 = P(0.24, 0.12)
    tx1, ty1 = P(0.96, 0.66)
    rounded(td, (tx0, ty0, tx1, ty1), S(0.075), fill=GLASS + (70,),
            outline=GLASS + (235,), width=int(S(0.028)))
    # Chân đế TV
    sx0, sy0 = P(0.52, 0.70)
    sx1, sy1 = P(0.80, 0.735)
    rounded(td, (sx0, sy0, sx1, sy1), S(0.018), fill=GLASS + (235,))
    img = Image.alpha_composite(img, tv)

    # --- Sóng phát (giữa điện thoại và TV) ---
    wv = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    wd = ImageDraw.Draw(wv)
    cx, cy = P(0.40, 0.46)
    for i, (rad, alpha) in enumerate([(0.10, 255), (0.19, 215), (0.28, 170)]):
        r = S(rad)
        wd.arc((cx - r, cy - r, cx + r, cy + r), start=-62, end=-8,
               fill=GLASS + (alpha,), width=int(S(0.034)))
    img = Image.alpha_composite(img, wv)

    # --- Điện thoại trắng (phía trước, bên trái) ---
    ph = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    pd = ImageDraw.Draw(ph)
    px0, py0 = P(0.10, 0.34)
    px1, py1 = P(0.40, 0.92)
    rounded(pd, (px0, py0, px1, py1), S(0.06), fill=(255, 255, 255, 255))
    # Màn hình điện thoại (gradient xanh) — vẽ bằng mask
    inset = S(0.022)
    scr = (px0 + inset, py0 + S(0.055), px1 - inset, py1 - S(0.05))
    grad = Image.new("RGBA", (w, w))
    gpx = ImageDraw.Draw(grad)
    steps = 64
    for i in range(steps):
        t = i / (steps - 1)
        c = lerp((61, 207, 142), (16, 132, 118), t)
        yA = scr[1] + (scr[3] - scr[1]) * i / steps
        yB = scr[1] + (scr[3] - scr[1]) * (i + 1) / steps + 1
        gpx.rectangle((scr[0], yA, scr[2], yB), fill=c + (255,))
    m = Image.new("L", (w, w), 0)
    rounded(ImageDraw.Draw(m), scr, S(0.035), fill=255)
    ph.paste(grad, (0, 0), m)
    # Loa thoại
    lx0, ly0 = P(0.21, 0.365)
    lx1, ly1 = P(0.29, 0.378)
    rounded(pd, (lx0, ly0, lx1, ly1), S(0.007), fill=(200, 215, 212, 255))
    # Mũi tên chia sẻ trên màn hình điện thoại
    ax, ay = P(0.25, 0.63)
    a = S(0.075)
    pd.polygon([(ax, ay - a), (ax - a * 0.8, ay - a * 0.1),
                (ax + a * 0.8, ay - a * 0.1)], fill=(255, 255, 255, 255))
    rounded(pd, (ax - a * 0.28, ay - a * 0.25, ax + a * 0.28, ay + a * 0.85),
            a * 0.12, fill=(255, 255, 255, 255))
    img = Image.alpha_composite(img, ph)
    return img


def squircle_mask(w: int) -> Image.Image:
    """Mặt nạ bo góc kiểu icon iOS (bán kính 22.37% cạnh)."""
    m = Image.new("L", (w, w), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, w - 1), radius=int(w * 0.2237), fill=255)
    return m


def down(img: Image.Image) -> Image.Image:
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    bg = gradient_background(W).convert("RGBA")

    # Icon đầy đủ: nền + hình, bo góc siêu elip (góc trong suốt).
    full = Image.alpha_composite(bg, symbol_layer(W, 0.80))
    alpha = ImageChops.multiply(full.getchannel("A"), squircle_mask(W))
    full.putalpha(alpha)
    down(full).save(OUT / "icon.png")

    # Adaptive icon: nền vuông kín; hình thu nhỏ vào vùng an toàn (~66%).
    down(bg).save(OUT / "icon_background.png")
    down(symbol_layer(W, 0.56)).save(OUT / "icon_foreground.png")
    print("Created icon.png, icon_background.png, icon_foreground.png in", OUT)


if __name__ == "__main__":
    main()
