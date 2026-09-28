"""Sinh banner Android TV (320x180, drawable-xhdpi) từ icon hiện có.

Chạy (sau tools/make_icon.py):  python tools/make_banner.py
Xuất: android/app/src/main/res/drawable-xhdpi/banner.png
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "android/app/src/main/res/drawable-xhdpi/banner.png"
W, H = 320, 180
SS = 4  # siêu lấy mẫu để chữ/viền mượt
LABEL = "ScreenShare"


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for name in ("segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def main():
    w, h = W * SS, H * SS
    bg = Image.open(ROOT / "icon_background.png").convert("RGB")
    bg = bg.resize((w, w), Image.BICUBIC).crop((0, (w - h) // 2, w, (w + h) // 2))
    img = bg.convert("RGBA")

    # Biểu tượng bên trái, chiếm ~ chiều cao banner.
    fg = Image.open(ROOT / "icon_foreground.png").convert("RGBA")
    icon_size = int(h * 1.1)
    fg = fg.resize((icon_size, icon_size), Image.LANCZOS)
    img.alpha_composite(fg, (int(-h * 0.08), (h - icon_size) // 2))

    # Tên app bên phải.
    d = ImageDraw.Draw(img)
    font = load_font(int(h * 0.2))
    x0 = int(h * 0.88)
    tw = d.textlength(LABEL, font=font)
    while x0 + tw > w - 12 * SS and font.size > 10:
        font = load_font(font.size - 4)
        tw = d.textlength(LABEL, font=font)
    d.text((x0, h // 2), LABEL, font=font, fill=(255, 255, 255, 255), anchor="lm")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.resize((W, H), Image.LANCZOS).save(OUT)
    print(f"Saved {OUT}")


if __name__ == "__main__":
    main()
