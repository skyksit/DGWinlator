#!/usr/bin/env python3
"""Generate DGWinlator branding assets (wordmark logo + launcher icons).

Palette from the app's AppThemeDark: background #3f474f, text #fafafa,
accent #0288d1. Everything is rendered at 4x and downscaled with LANCZOS.

Outputs:
  app/app/src/main/res/drawable-hdpi/logo.png            376x128 drawer wordmark
  app/app/src/main/res/mipmap-*/ic_launcher.png          48/72/96/144/192 legacy icon
  app/app/src/main/res/mipmap-*/ic_launcher_foreground.png  108/162/216/324/432 adaptive fg
  logo.png, app/logo.png                                 752x256 README hero
"""

import os

from PIL import Image, ImageDraw, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(REPO, "app", "app", "src", "main", "res")

BG = (0x3F, 0x47, 0x4F, 255)
TEXT = (0xFA, 0xFA, 0xFA, 255)
ACCENT = (0x02, 0x88, 0xD1, 255)

FONT_PATH = "C:/Windows/Fonts/segoeuib.ttf"
if not os.path.isfile(FONT_PATH):
    FONT_PATH = "C:/Windows/Fonts/arialbd.ttf"

SS = 4  # supersampling factor


def fit_font(draw, text, max_w, max_h, start=400):
    size = start
    while size > 8:
        font = ImageFont.truetype(FONT_PATH, size)
        l, t, r, b = draw.textbbox((0, 0), text, font=font)
        if r - l <= max_w and b - t <= max_h:
            return font, (l, t, r, b)
        size -= 4
    raise RuntimeError("cannot fit text")


def wordmark(w, h, text_color=TEXT):
    """'DG' in accent + 'Winlator' in text_color, vertically centered, transparent bg."""
    img = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    pad = int(w * SS * 0.03)
    font, _ = fit_font(draw, "DGWinlator", w * SS - 2 * pad, int(h * SS * 0.72))
    l1, t1, r1, b1 = draw.textbbox((0, 0), "DG", font=font)
    lf, tf, rf, bf = draw.textbbox((0, 0), "DGWinlator", font=font)
    total_w = rf - lf
    x = (w * SS - total_w) // 2 - lf
    y = (h * SS - (bf - tf)) // 2 - tf
    draw.text((x, y), "DG", font=font, fill=ACCENT)
    draw.text((x + font.getlength("DG"), y), "Winlator", font=font, fill=text_color)
    # underline bar under 'DG' as a small motif
    bar_y = y + bf + int(h * SS * 0.04)
    if bar_y + int(h * SS * 0.045) < h * SS:
        draw.rounded_rectangle(
            [x + l1, bar_y, x + r1, bar_y + int(h * SS * 0.045)],
            radius=int(h * SS * 0.02), fill=ACCENT)
    return img.resize((w, h), Image.LANCZOS)


def monogram(canvas, glyph_box_ratio, rounded_bg):
    """'DG' monogram; rounded-square background when rounded_bg else transparent."""
    s = canvas * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if rounded_bg:
        r = int(s * 0.20)
        draw.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=BG)
    box = int(s * glyph_box_ratio)
    font, (l, t, rr, b) = fit_font(draw, "DG", box, box)
    x = (s - (rr - l)) // 2 - l
    y = (s - (b - t)) // 2 - t
    draw.text((x, y), "D", font=font, fill=TEXT)
    draw.text((x + font.getlength("D"), y), "G", font=font, fill=ACCENT)
    return img.resize((canvas, canvas), Image.LANCZOS)


def save(img, *rel):
    path = os.path.join(*rel)
    img.save(path, "PNG")
    print(f"{path}  {img.size[0]}x{img.size[1]}")


def main():
    # drawer logo (sits on colorPrimaryDark, displayed 153x52dp) -> light text
    save(wordmark(376, 128), RES, "drawable-hdpi", "logo.png")

    # README hero images: GitHub renders on light by default -> dark slate text
    hero = wordmark(752, 256, text_color=BG)
    save(hero, REPO, "logo.png")
    save(hero, REPO, "app", "logo.png")

    # legacy launcher icons: full-bleed rounded square + DG monogram (~62% glyph box)
    for dpi, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(monogram(size, 0.62, rounded_bg=True), RES, f"mipmap-{dpi}", "ic_launcher.png")

    # adaptive foreground: transparent, glyph confined to central 66/108 safe zone
    for dpi, size in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        save(monogram(size, 0.66 * 66 / 108, rounded_bg=False), RES, f"mipmap-{dpi}", "ic_launcher_foreground.png")


if __name__ == "__main__":
    main()
