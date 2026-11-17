"""Generate icon.png + icon.ico (phone casting its screen + a remote cursor) with Pillow.
Run automatically by build_exe.bat; safe to run by hand too."""
from PIL import Image, ImageDraw

S = 512
sc = S / 108.0  # same 108-unit layout as the Android vector icon


def main():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # diagonal-ish gradient indigo (#4F46E5) -> cyan (#06B6D4)
    for y in range(S):
        t = y / S
        r = int(79 + (6 - 79) * t)
        g = int(70 + (182 - 70) * t)
        b = int(229 + (212 - 229) * t)
        d.line([(0, y), (S, y)], fill=(r, g, b, 255))

    # rounded-square mask
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(0.22 * S), fill=255)
    img.putalpha(mask)
    d = ImageDraw.Draw(img)

    def rr(x1, y1, x2, y2, rad, **kw):
        d.rounded_rectangle([x1 * sc, y1 * sc, x2 * sc, y2 * sc], radius=rad * sc, **kw)

    def arc(cx, cy, rad, a0, a1, width, col):
        d.arc([(cx - rad) * sc, (cy - rad) * sc, (cx + rad) * sc, (cy + rad) * sc],
              a0, a1, fill=col, width=max(1, int(width * sc)))

    rr(39, 24, 69, 84, 7, fill=(255, 255, 255, 255))      # phone
    rr(42.5, 30, 65.5, 72, 2.5, fill=(20, 23, 52, 255))   # screen

    cyan = (34, 211, 238, 255)
    rad = 1.8
    d.ellipse([(46.3 - rad) * sc, (68.3 - rad) * sc, (46.3 + rad) * sc, (68.3 + rad) * sc], fill=cyan)
    arc(45.5, 69.5, 5.1, 270, 360, 1.8, cyan)
    arc(45.5, 69.5, 10.3, 270, 360, 1.8, cyan)

    cursor = [(58, 46), (58, 64), (62.4, 59.6), (65.6, 66.3), (68.0, 65.2), (64.8, 58.6), (70.3, 58.6)]
    d.polygon([(x * sc, y * sc) for x, y in cursor], fill=(255, 255, 255, 255), outline=(20, 23, 52, 255))

    img.save("icon.png")
    img.save("icon.ico", sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    print("[icon] wrote icon.png + icon.ico")


if __name__ == "__main__":
    main()
