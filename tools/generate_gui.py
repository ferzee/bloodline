#!/usr/bin/env python3
"""
Generates the pixel-art sprites of Bloodline Chronicles' own screens (src/main/resources/assets/bloodline/textures/gui/sprites/).

Run it after changing a sprite:  python3 tools/generate_gui.py

The sprites are nine-sliced by the game (see each .png.mcmeta), so a small image stretches to any window size
with crisp borders. Colours are an aged parchment, brown ink and red wax.
"""
import json
import os
import random

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "bloodline", "textures", "gui", "sprites")
rng = random.Random(1066)

PARCHMENT = [(236, 221, 182), (231, 214, 172), (226, 207, 163), (233, 218, 178)]
PARCHMENT_DARK = [(214, 190, 141), (206, 180, 130)]
BURN = [(176, 132, 78), (150, 106, 58), (121, 82, 42)]
INK = (92, 62, 32)
GOLD = (176, 132, 52)


def save(name, image, border=None, tile=True):
    os.makedirs(OUT, exist_ok=True)
    image.save(os.path.join(OUT, name + ".png"))
    if border is not None:
        scaling = {"type": "nine_slice", "width": image.width, "height": image.height, "border": border}
        if not tile:
            scaling["stretch_inner"] = True
        with open(os.path.join(OUT, name + ".png.mcmeta"), "w") as f:
            json.dump({"gui": {"scaling": scaling}}, f, indent=2)


def parchment_pixel(x, y, w, h, dark_chance=0.08):
    if rng.random() < dark_chance:
        return rng.choice(PARCHMENT_DARK)
    return rng.choice(PARCHMENT)


def scroll():
    """The big sheet: parchment with burnt, ragged edges and a thin inked frame."""
    size, border = 64, 14
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    for y in range(size):
        for x in range(size):
            edge = min(x, y, size - 1 - x, size - 1 - y)
            # ragged outer edge
            if edge == 0 and rng.random() < 0.55:
                continue
            if edge == 0:
                px[x, y] = BURN[2] + (255,)
            elif edge == 1:
                px[x, y] = rng.choice(BURN[1:]) + (255,)
            elif edge == 2:
                px[x, y] = rng.choice(BURN[:2]) + (255,)
            elif edge <= 4:
                px[x, y] = (rng.choice(BURN[:1] + PARCHMENT_DARK) if rng.random() < 0.6 else rng.choice(PARCHMENT_DARK)) + (255,)
            else:
                px[x, y] = parchment_pixel(x, y, size, size, 0.12 if edge < 8 else 0.07) + (255,)
    # an inked double rule inside the edge, with corner diamonds
    for i in range(7, size - 7):
        for a, b in ((7, i), (i, 7), (size - 8, i), (i, size - 8)):
            px[a, b] = INK + (255,)
        for a, b in ((9, i), (i, 9), (size - 10, i), (i, size - 10)):
            if 9 <= i <= size - 10:
                px[a, b] = GOLD + (255,)
    for cx, cy in ((7, 7), (size - 8, 7), (7, size - 8), (size - 8, size - 8)):
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1)):
            px[cx + dx, cy + dy] = INK + (255,)
        px[cx, cy] = GOLD + (255,)
    save("scroll", img, border)


def card(name, frame, frame_width):
    """A child's card: lighter paper in a frame."""
    size, border = 24, 5
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    light = [(244, 234, 206), (240, 228, 198), (246, 238, 214)]
    for y in range(size):
        for x in range(size):
            edge = min(x, y, size - 1 - x, size - 1 - y)
            if edge < frame_width:
                shade = frame if edge == 0 else tuple(min(255, c + 30) for c in frame)
                px[x, y] = shade + (255,)
            elif edge == frame_width:
                px[x, y] = (214, 196, 156, 255)
            else:
                px[x, y] = rng.choice(light) + (255,)
    save(name, img, border)


def button(name, base, light, dark, outline=(40, 24, 12)):
    """A leather-bound button with a stitched highlight."""
    w, h, border = 48, 20, 5
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for y in range(h):
        for x in range(w):
            if (x in (0, w - 1) and y in (0, h - 1)):
                continue
            if x in (0, w - 1) or y in (0, h - 1):
                px[x, y] = outline + (255,)
            elif y == 1:
                px[x, y] = light + (255,)
            elif y == h - 2:
                px[x, y] = dark + (255,)
            else:
                v = rng.randint(-6, 6)
                px[x, y] = tuple(max(0, min(255, c + v)) for c in base) + (255,)
    # stitching
    for x in range(4, w - 4, 2):
        px[x, 3] = tuple(min(255, c + 45) for c in base) + (255,)
        px[x, h - 4] = tuple(min(255, c + 30) for c in base) + (255,)
    save(name, img, border)


def seal():
    """A disc of red wax with a rough rim, for the window's emblem."""
    size = 28
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    c = (size - 1) / 2
    for y in range(size):
        for x in range(size):
            d = ((x - c) ** 2 + (y - c) ** 2) ** 0.5
            r = 12.5 + rng.uniform(-0.8, 0.6)
            if d > r:
                continue
            if d > r - 1.2:
                col = (92, 18, 16)
            elif d > r - 3:
                col = (128, 26, 22) if (x + y) % 3 else (112, 22, 19)
            else:
                shade = (x - c) * -0.06 + (y - c) * -0.08
                col = tuple(max(0, min(255, int(v * (1 + shade)))) for v in (166, 38, 32))
                if d > 7.5 and d < 8.6:
                    col = (120, 24, 20)
            px[x, y] = col + (255,)
    # a highlight
    for x, y in ((9, 7), (10, 7), (8, 8), (9, 8)):
        px[x, y] = (206, 92, 80, 255)
    save("seal", img)


def flourish():
    """An inked divider line with a diamond in the middle."""
    w, h = 96, 7
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    mid = w // 2
    for x in range(6, w - 6):
        fade = min(x - 6, w - 7 - x)
        if fade < 4 and rng.random() < 0.5:
            continue
        px[x, 3] = INK + (255,)
    for d in range(4):
        for dy in range(-3 + d, 4 - d):
            px[mid - d, 3 + dy] = (GOLD if d == 0 else INK) + (255,)
            px[mid + d, 3 + dy] = (GOLD if d == 0 else INK) + (255,)
    for x in (mid - 12, mid + 12):
        px[x, 2] = INK + (255,); px[x, 4] = INK + (255,)
    save("flourish", img)


def book_cover():
    """The open book's leather binding, with gold tooling on the corners."""
    size, border = 48, 12
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    leather = [(98, 38, 30), (104, 42, 33), (92, 35, 28), (100, 40, 31)]
    for y in range(size):
        for x in range(size):
            if (x in (0, size - 1)) and (y in (0, size - 1)):
                continue
            edge = min(x, y, size - 1 - x, size - 1 - y)
            if edge == 0:
                px[x, y] = (46, 18, 14, 255)
            elif edge == 1:
                px[x, y] = (128, 58, 44, 255)
            else:
                px[x, y] = rng.choice(leather) + (255,)
    # gold rule and corner pieces
    gold, dark_gold = (196, 152, 64), (140, 104, 40)
    for i in range(4, size - 4):
        for a2, b2 in ((4, i), (i, 4), (size - 5, i), (i, size - 5)):
            px[a2, b2] = dark_gold + (255,)
    for cx, cy, sx, sy in ((4, 4, 1, 1), (size - 5, 4, -1, 1), (4, size - 5, 1, -1), (size - 5, size - 5, -1, -1)):
        for d in range(6):
            px[cx + sx * d, cy] = gold + (255,)
            px[cx, cy + sy * d] = gold + (255,)
        for d in range(3):
            px[cx + sx * (d + 1), cy + sy * (d + 1)] = gold + (255,)
    save("book_cover", img, border)


def page(name, spine_on_right):
    """A page: cream paper, darker at the outer edge where the pages stack, shadowed towards the spine."""
    w, h, border = 40, 40, 10
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    paper = [(240, 230, 204), (236, 225, 197), (242, 233, 209)]
    for y in range(h):
        for x in range(w):
            to_spine = (w - 1 - x) if spine_on_right else x
            to_edge = x if spine_on_right else (w - 1 - x)
            base = rng.choice(paper)
            if to_spine < 8:
                f = 0.72 + 0.035 * to_spine
                base = tuple(int(c * f) for c in base)
            if to_edge < 3 or y < 2 or y >= h - 2:
                # the edges of the pages underneath
                base = (214, 198, 160) if (to_edge + y) % 2 == 0 and to_edge < 3 else (222, 208, 172)
            if to_edge == 0 or y == 0 or y == h - 1:
                base = (150, 124, 84)
            px[x, y] = base + (255,)
    save(name, img, border)


def tab(name, fill, frame):
    """A bookmark tab sticking out of the book."""
    w, h, border = 32, 16, 4
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for y in range(h):
        for x in range(w):
            if x == w - 1 and y in (0, h - 1):
                continue
            if y in (0, h - 1) or x == w - 1:
                px[x, y] = frame + (255,)
            elif x < 3:
                px[x, y] = tuple(int(c * 0.8) for c in fill) + (255,)
            else:
                v = rng.randint(-5, 5)
                px[x, y] = tuple(max(0, min(255, c + v)) for c in fill) + (255,)
    save(name, img, border)


def ribbon():
    """The red silk ribbon that hangs from the spine."""
    w, h = 6, 40
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for y in range(h):
        for x in range(w):
            if y >= h - 4 and abs(x - 2.5) < (y - (h - 4)) * 0.9:
                continue  # the forked end
            px[x, y] = ((168, 32, 36) if x in (1, 2, 3) else (124, 20, 24)) + (255,)
    save("ribbon", img)


if __name__ == "__main__":
    book_cover()
    page("page_left", True)
    page("page_right", False)
    tab("tab", (226, 210, 172), (122, 82, 48))
    tab("tab_active", (238, 214, 140), (176, 130, 44))
    ribbon()
    scroll()
    card("card", (122, 82, 48), 1)
    card("card_hover", (186, 140, 46), 2)
    button("button", (92, 58, 34), (122, 82, 51), (59, 36, 20))
    button("button_hover", (112, 74, 44), (146, 102, 64), (72, 45, 25))
    button("button_blood", (112, 26, 20), (146, 44, 34), (74, 16, 12))
    button("button_blood_hover", (140, 36, 27), (176, 62, 48), (92, 20, 15))
    button("button_primary", (54, 78, 42), (78, 108, 60), (34, 50, 26))
    button("button_primary_hover", (66, 96, 52), (96, 130, 74), (42, 62, 32))
    seal()
    flourish()
    print("Wrote sprites to", os.path.normpath(OUT))
