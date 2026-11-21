"""Generate icon.png + icon.ico (capture brackets + record dot) with Pillow — matches the Android icon."""
from PIL import Image, ImageDraw

S = 512
sc = S / 108.0  # same 108-unit layout as the Android vector icon


def main():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # diagonal gradient violet (#7C3AED) -> pink (#EC4899)
    for y in range(S):
        t = y / S
        r = int(0x7C + (0xEC - 0x7C) * t)
        g = int(0x3A + (0x48 - 0x3A) * t)
        b = int(0xED + (0x99 - 0xED) * t)
        d.line([(0, y), (S, y)], fill=(r, g, b, 255))

    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(0.22 * S), fill=255)
    img.putalpha(mask)
    d = ImageDraw.Draw(img)

    white = (255, 255, 255, 255)
    lw = int(5 * sc)

    def line(x1, y1, x2, y2):
        d.line([(x1 * sc, y1 * sc), (x2 * sc, y2 * sc)], fill=white, width=lw, joint="curve")

    # four capture brackets
    # top-left
    line(26, 44, 26, 38); line(26, 38, 36, 38)
    # top-right
    line(82, 44, 82, 38); line(82, 38, 72, 38)
    # bottom-left
    line(26, 64, 26, 70); line(26, 70, 36, 70)
    # bottom-right
    line(82, 64, 82, 70); line(82, 70, 72, 70)

    # centre record dot
    rad = 10 * sc
    cx, cy = 54 * sc, 54 * sc
    d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(0xEF, 0x44, 0x44, 255))

    img.save("icon.png")
    img.save("icon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("wrote icon.png + icon.ico")


if __name__ == "__main__":
    main()
