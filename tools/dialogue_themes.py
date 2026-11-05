"""
Dialogue themes (DialogueScreen, ThemeStyle): our own textures for each `dialogueTheme`, drawn here from shapes, plus
each theme's theme.json (nine-slice borders, colours, alpha, font). All art is generated, never copied.

Parts are nine-slice: corners kept, edges and middle stretched (or tiled where the theme says). Smooth themes are drawn
at K=4 texture px per GUI px, anti-aliased; Blocky is pixel art at 1:1.

    python tools/dialogue_themes.py -> src/main/resources/assets/villagechatter/textures/gui/dialogue/<theme>/
"""
import json
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "villagechatter", "textures", "gui", "dialogue")
SS = 4  # supersampling for the anti-aliasing
RNG = np.random.default_rng(7)


# ------------------------------------------------------------------ shapes (signed distance, tex px, negative inside)

def grid(w, h, ss=SS):
    ys, xs = np.mgrid[0:h * ss, 0:w * ss]
    return (xs + 0.5) / ss, (ys + 0.5) / ss


def rrect(x, y, w, h, r, inset=0.0):
    """Rounded rectangle filling w x h, corner radius r (r = h/2 is a pill)."""
    qx = np.abs(x - w / 2) - (w / 2 - inset - r)
    qy = np.abs(y - h / 2) - (h / 2 - inset - r)
    return np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - r


def chamfer(x, y, w, h, c, inset=0.0):
    """Rectangle with its corners cut off at 45 degrees, c long."""
    dx = np.abs(x - w / 2) - (w / 2 - inset)
    dy = np.abs(y - h / 2) - (h / 2 - inset)
    box = np.maximum(dx, dy)
    cut = (dx + dy + c) / math.sqrt(2)
    return np.maximum(box, cut)


def pointed(x, y, w, h, p, inset=0.0):
    """A bar whose left and right ends come to a point, p long."""
    v = np.abs(y - h / 2) / (h / 2)
    dy = np.abs(y - h / 2) - (h / 2 - inset)
    dx = (np.abs(x - w / 2) - (w / 2 - inset) + p * v) / math.hypot(1, p / (h / 2))
    return np.maximum(dy, dx)


def slanted(x, y, w, h, s, inset=0.0):
    """A tab whose right end slants (top longer than bottom), s px of slant."""
    dy = np.abs(y - h / 2) - (h / 2 - inset)
    left = inset - x
    right = (x - (w - inset - s * (y / h))) / 1.2
    return np.maximum(dy, np.maximum(left, right))


def swallowtail(x, y, w, h, n, inset=0.0):
    """A ribbon with a notch cut into each end, n px deep."""
    dy = np.abs(y - h / 2) - (h / 2 - inset)
    v = np.abs(y - h / 2) / (h / 2)
    left = (inset + n * (1 - v)) - x
    right = x - (w - inset - n * (1 - v))
    return np.maximum(dy, np.maximum(left, right))


def solid(d):
    return np.clip(0.5 - d, 0, 1)


def line(d, t):
    """A line t px thick along the shape's edge, inside it."""
    return np.clip(0.5 - (np.abs(d + t / 2) - t / 2), 0, 1)


def soft(d, feather):
    a = np.clip(-d / feather, 0, 1)
    return a * a * (3 - 2 * a)


def down(a, w, h, ss=SS):
    return a.reshape(h, ss, w, ss).mean(axis=(1, 3))


def rgba(color, alpha, w, h, ss=SS):
    """An image of one colour (0xRRGGBB, or an HxWx3 array) with alpha from a supersampled mask."""
    a = down(alpha, w, h, ss) if alpha.shape != (h, w) else alpha
    img = np.zeros((h, w, 4), np.float64)
    if isinstance(color, int):
        img[..., 0], img[..., 1], img[..., 2] = (color >> 16) & 255, (color >> 8) & 255, color & 255
    else:
        img[..., :3] = color
    img[..., 3] = a * 255
    return img


def over(top, bottom):
    """Alpha-composite two float RGBA images."""
    ta, ba = top[..., 3:] / 255, bottom[..., 3:] / 255
    oa = ta + ba * (1 - ta)
    rgb = np.where(oa > 0, (top[..., :3] * ta + bottom[..., :3] * ba * (1 - ta)) / np.maximum(oa, 1e-6), 0)
    return np.concatenate([rgb, oa * 255], axis=-1)


def noise(w, h, base, amount, scale=1):
    """A colour with gentle blotchy variation (parchment, stone)."""
    small = RNG.normal(0, 1, (h // scale + 2, w // scale + 2))
    img = np.array(Image.fromarray(((small - small.min()) / np.ptp(small) * 255).astype(np.uint8))
                   .resize((w, h), Image.BICUBIC), np.float64) / 255 - 0.5
    rgb = np.zeros((h, w, 3))
    for i, sh in enumerate((16, 8, 0)):
        rgb[..., i] = np.clip(((base >> sh) & 255) + img * amount * 2, 0, 255)
    return rgb


# ------------------------------------------------------------------ output

class Theme:
    def __init__(self, name, scale, blur=True):
        self.name, self.k, self.blur = name, scale, blur
        self.dir = os.path.join(OUT, name)
        os.makedirs(self.dir, exist_ok=True)
        for old in os.listdir(self.dir):  # parts a theme no longer has mustn't linger
            if old.endswith((".png", ".png.mcmeta", "theme.json")):
                os.remove(os.path.join(self.dir, old))
        self.parts = {}

    def save(self, part, img, border=None, tile=False):
        """Save a part; border (bx, by) in texture px marks the nine-slice corners."""
        h, w = img.shape[:2]
        Image.fromarray(np.clip(np.round(img), 0, 255).astype(np.uint8), "RGBA").save(os.path.join(self.dir, part + ".png"))
        with open(os.path.join(self.dir, part + ".png.mcmeta"), "w") as f:
            json.dump({"texture": {"blur": self.blur}}, f)
        bx, by = border if border else (0, 0)
        assert bx % self.k == 0 and by % self.k == 0, (self.name, part, border)
        self.parts[part] = {"w": w, "h": h, "bx": bx, "by": by, **({"tile": True} if tile else {})}

    def write(self, **style):
        with open(os.path.join(self.dir, "theme.json"), "w") as f:
            json.dump({"scale": self.k, "parts": self.parts, **style}, f, indent=2)
        print(self.name, sorted(self.parts))


# Shared GUI sizes (keep in step with ThemeStyle / DialogueScreen).
OPT_H = 14
TAB_H = 12


def classic():
    """Today's smoked glass: soft-edged warm-dark fills, a thin pale ring on the selected choice."""
    t = Theme("classic", 4)
    k = t.k
    tint = 0x14120E
    w = h = 32 * k
    x, y = grid(w, h)
    t.save("box_fill", rgba(tint, soft(rrect(x, y, w, h, 12 * k), 1.5 * k), w, h), (13 * k, 13 * k))
    w, h = 24 * k, TAB_H * k
    x, y = grid(w, h)
    d = rrect(x, y, w, h, h / 2)
    t.save("tab_fill", rgba(0x0C0B09, soft(d, 0.8 * k), w, h), (8 * k, 6 * k))
    t.save("tab_frame", rgba(0xEDE6D3, line(d, 0.6 * k) * 0.45, w, h), (8 * k, 6 * k))
    w, h = 24 * k, OPT_H * k
    x, y = grid(w, h)
    d = rrect(x, y, w, h, h / 2)
    t.save("opt_fill", rgba(tint, soft(d, 0.8 * k), w, h), (8 * k, 7 * k))
    t.save("sel_fill", rgba(0x2A2620, soft(d, 0.8 * k), w, h), (8 * k, 7 * k))
    t.save("sel_frame", rgba(0xEDE6D3, line(d, 1.0 * k), w, h), (8 * k, 7 * k))
    cursor(t, 0xF5EBD0, 1.1)
    t.write(font="villagechatter:dialogue", text="#F4F4F0", name="#EDEDE8", option="#D8D5CC",
            selected="#FFFFFF", narration="#DCD5C6", shadow=True, alpha={"box": 1.0, "opt": 0.85, "sel": 1.2, "frame": 1.0})


def blocky():
    """Minecraft's own look: a translucent stone panel with a pixel bevel; the chosen line is grass green."""
    t = Theme("blocky", 1, blur=False)

    def px(c):
        return [(c >> s) & 255 for s in (16, 8, 0)] + [255]

    def bevel(w, h, fill, light, dark, edge):
        """Vanilla-style: a dark outline, a light top-left and a dark bottom-right line, a flat inside."""
        img = np.zeros((h, w, 4))
        img[1:-1, 1:-1] = px(fill)
        img[0, 1:-1] = img[-1, 1:-1] = img[1:-1, 0] = img[1:-1, -1] = px(edge)
        img[1, 1:-2] = img[1:-2, 1] = px(light)
        img[-2, 2:-1] = img[2:-1, -2] = px(dark)
        return img

    def split(img):
        """Fill (the flat inside, follows opacity) and frame (the bevel, kept crisper)."""
        fill, frame = img.copy(), img.copy()
        fill[:2] = fill[-2:] = 0
        fill[:, :2] = fill[:, -2:] = 0
        frame[2:-2, 2:-2] = 0
        return fill, frame

    fill, frame = split(bevel(16, 16, 0x3B3B36, 0xA6A69C, 0x55554E, 0x161614))
    t.save("box_fill", fill, (4, 4))
    t.save("box_frame", frame, (4, 4))
    fill, frame = split(bevel(16, TAB_H, 0x33362C, 0xA6A69C, 0x55554E, 0x161614))
    t.save("tab_fill", fill, (4, 4))
    t.save("tab_frame", frame, (4, 4))
    fill, frame = split(bevel(16, OPT_H, 0x4A4A44, 0x9A9A90, 0x4A4A44, 0x161614))
    t.save("opt_fill", fill, (4, 4))
    t.save("opt_frame", frame, (4, 4))
    fill, frame = split(bevel(16, OPT_H, 0x5C8A30, 0x9BD25C, 0x3A5A1C, 0x161614))
    t.save("sel_fill", fill, (4, 4))
    t.save("sel_frame", frame, (4, 4))
    # Pixel arrow.
    arrow = np.zeros((7, 5, 4))
    for r in range(7):
        for c in range(5):
            if c <= 3 - abs(r - 3):
                arrow[r, c] = [255, 255, 255, 255]
    t.save("cursor", arrow)
    t.write(font="minecraft:default", text="#FFFFFF", name="#FFFFFF", option="#E6E6E0", selected="#FFFFFF",
            narration="#D8D8C8", shadow=True, alpha={"box": 1.0, "opt": 0.95, "sel": 1.1, "frame": 1.0},
            tabIcon="minecraft:grass_block")


def terminal():
    """A green terminal: dark translucent green with faint scanlines, 1 px glowing lines, the choices inside the frame
    in a column split off by a divider, the chosen one boxed."""
    t = Theme("terminal", 4)
    k = t.k
    green, glow = 0x46FF7A, 0x1E9C44
    w = h = 16 * k
    x, y = grid(w, h)
    d = chamfer(x, y, w, h, 2.5 * k, 0.5 * k)
    fill = rgba(0x041A0A, solid(d), w, h)
    rows = (np.arange(h) // (k // 2)) % 2 == 0
    fill[rows, :, :3] = np.clip(fill[rows, :, :3] + [6, 22, 10], 0, 255)
    t.save("box_fill", fill, (4 * k, 4 * k), tile=True)
    halo = rgba(glow, np.clip(1 - np.abs(d + 0.5 * k) / (2.2 * k), 0, 1) ** 2 * 0.5, w, h)
    t.save("box_frame", over(rgba(green, line(d, 1.0 * k), w, h), halo), (4 * k, 4 * k))
    w, h = 24 * k, TAB_H * k
    x, y = grid(w, h)
    d = slanted(x, y, w, h, 4 * k, 0.5 * k)
    t.save("tab_fill", rgba(0x062A10, solid(d), w, h), (4 * k, 4 * k))
    t.save("tab_frame", rgba(green, line(d, 1.0 * k), w, h), (4 * k, 4 * k))
    w, h = 16 * k, OPT_H * k
    x, y = grid(w, h)
    d = rrect(x, y, w, h, 0.5 * k, 0.5 * k)
    t.save("sel_fill", rgba(0x0E3A18, solid(d), w, h), (4 * k, 4 * k))
    t.save("sel_frame", rgba(green, line(d, 1.0 * k), w, h), (4 * k, 4 * k))
    # Solid pointer.
    w, h = 7 * k, 9 * k
    x, y = grid(w, h)
    tri = np.maximum(0.5 * k - x, np.abs(y - h / 2) - (h / 2 - 0.5 * k) * (1 - x / w))
    t.save("cursor", rgba(green, solid(tri), w, h))
    t.write(font="minecraft:default", text="#5CFF86", name="#9CFFB4", option="#46D86C",
            selected="#9CFFB4", narration="#3FBF64", shadow=False, alpha={"box": 1.0, "opt": 0.9, "sel": 0.95, "frame": 1.0},
            inline=True, divider="#46FF7A", tabIcon="minecraft:emerald")


def diamond(x, y, cx, cy, r, k):
    """A small diamond outline (a square on its point)."""
    d = np.abs(x - cx) + np.abs(y - cy) - r
    return np.clip(0.5 - (np.abs(d) - 0.35 * k), 0, 1)


def northern():
    """Dark slate glass in a thin pale-silver frame with bracketed corners; the name in capitals, centred on a tab
    with diamond ends; choices with pointed ends past a divider."""
    t = Theme("northern", 4)
    k = t.k
    silver = 0xC9CED3
    w = h = 32 * k
    x, y = grid(w, h)
    t.save("box_fill", rgba(0x111417, soft(rrect(x, y, w, h, 1.5 * k), 1.2 * k), w, h), (12 * k, 12 * k))
    a = line(rrect(x, y, w, h, 0.5 * k, 3 * k), 0.5 * k)
    for cx, sx in ((0, 1), (w, -1)):
        for cy, sy in ((0, 1), (h, -1)):
            # An L bracket just outside the line, a diamond where it turns.
            bx, by = cx + sx * 1.2 * k, cy + sy * 1.2 * k
            arm = 7 * k
            on_h = (np.abs(y - by) < 0.35 * k) & ((x - bx) * sx >= 0) & ((x - bx) * sx <= arm)
            on_v = (np.abs(x - bx) < 0.35 * k) & ((y - by) * sy >= 0) & ((y - by) * sy <= arm)
            a = np.maximum(a, (on_h | on_v).astype(float))
            a = np.maximum(a, diamond(x, y, bx + sx * 2.6 * k, by + sy * 2.6 * k, 1.4 * k, k))
    t.save("box_frame", rgba(silver, a, w, h), (12 * k, 12 * k))
    w, h = 32 * k, TAB_H * k
    x, y = grid(w, h)
    d = pointed(x, y, w, h, 4 * k, 0.5 * k)
    t.save("tab_fill", rgba(0x15191D, solid(d), w, h), (8 * k, 6 * k))
    a = line(d, 0.5 * k)
    for cx in (3.2 * k, w - 3.2 * k):
        a = np.maximum(a, diamond(x, y, cx, h / 2, 1.8 * k, k))
    t.save("tab_frame", rgba(silver, a, w, h), (8 * k, 6 * k))
    w, h = 32 * k, OPT_H * k
    x, y = grid(w, h)
    d = pointed(x, y, w, h, 4 * k, 0.5 * k)
    t.save("opt_fill", rgba(0x111417, solid(d), w, h), (8 * k, 7 * k))
    t.save("opt_frame", rgba(silver, line(d, 0.5 * k) * 0.55, w, h), (8 * k, 7 * k))
    t.save("sel_fill", rgba(0x262C33, solid(d), w, h), (8 * k, 7 * k))
    t.save("sel_frame", rgba(0xF2F4F6, line(d, 0.8 * k), w, h), (8 * k, 7 * k))
    cursor(t, silver, 1.0)
    t.write(font="villagechatter:dialogue", text="#F2F2EE", name="#FFFFFF", option="#C8CCD0",
            selected="#FFFFFF", narration="#C8CCD0", shadow=True, italic=True, center=True, tabCenter=True,
            nameUpper=True, columnDivider=True, divider="#C9CED3", alpha={"box": 1.0, "opt": 0.85, "sel": 1.0, "frame": 0.9})


def leaf_corner(x, y, cx, cy, sx, sy, k):
    """A scroll curling out of the corner with two small leaves."""
    r = np.hypot(x - cx, y - cy)
    ang = np.arctan2((y - cy) * sy, (x - cx) * sx)
    spiral = np.abs(r - (1.0 * k + (ang + math.pi) / (2 * math.pi) * 2.2 * k)) - 0.3 * k
    a = np.clip(0.5 - spiral, 0, 1) * (r < 3.4 * k)
    for lx, ly, rot in ((5.5, 1.6, 0.35), (1.6, 5.5, 1.2)):
        px_, py_ = cx + sx * lx * k, cy + sy * ly * k
        u = (x - px_) * math.cos(rot) + (y - py_) * math.sin(rot)
        v = -(x - px_) * math.sin(rot) + (y - py_) * math.cos(rot)
        a = np.maximum(a, solid(np.hypot(u / 1.9, v / 0.8) - 1.0 * k))
    return a


def bell(t, k):
    """Our emblem: a small gold bell."""
    w, h = 9 * k, 9 * k
    x, y = grid(w, h)
    body = np.maximum(np.abs(x - w / 2) - (1.6 * k + (y / h) * 2.2 * k), np.maximum(1.4 * k - y, y - 7.2 * k))
    dome = np.hypot(x - w / 2, y - 2.6 * k) - 1.9 * k
    shape = np.minimum(body, dome)
    lip = np.maximum(np.abs(x - w / 2) - 4.0 * k, np.abs(y - 7.4 * k) - 0.6 * k)
    clapper = np.hypot(x - w / 2, y - 8.3 * k) - 0.7 * k
    d = np.minimum(np.minimum(shape, lip), clapper)
    img = rgba(0xE0B451, solid(d), w, h)
    t.save("emblem", over(rgba(0x7A5418, line(d, 0.5 * k), w, h), img))


def storybook():
    """Translucent parchment in a gold-and-brown frame with leaf scrolls in the corners, the name on a dark wooden tab,
    a gold bell at the foot."""
    t = Theme("storybook", 4)
    k = t.k
    gold, brown = 0xC99E45, 0x5A3A1E
    w = h = 32 * k
    x, y = grid(w, h)
    paper = noise(w, h, 0xE6D3A6, 18, 6)
    t.save("box_fill", rgba(paper, soft(rrect(x, y, w, h, 2 * k), 1.0 * k), w, h), (12 * k, 12 * k), tile=True)
    img = rgba(gold, line(rrect(x, y, w, h, 2 * k, 0.4 * k), 0.9 * k), w, h)
    a = line(rrect(x, y, w, h, 1 * k, 2.4 * k), 0.45 * k)
    for cx, sx in ((3 * k, 1), (w - 3 * k, -1)):
        for cy, sy in ((3 * k, 1), (h - 3 * k, -1)):
            a = np.maximum(a, leaf_corner(x, y, cx, cy, sx, sy, k))
    t.save("box_frame", over(rgba(brown, a, w, h), img), (12 * k, 12 * k))
    w, h = 32 * k, TAB_H * k
    x, y = grid(w, h)
    d = chamfer(x, y, w, h, 3 * k, 0.3 * k)
    grain = noise(w, h * 6, 0x4A2C18, 14, 2)[:h]
    grain[: h // 4] = np.clip(grain[: h // 4] + 12, 0, 255)
    t.save("tab_fill", rgba(grain, solid(d), w, h), (8 * k, 4 * k))
    a = line(d, 0.6 * k)
    for cx in (3.6 * k, w - 3.6 * k):
        a = np.maximum(a, diamond(x, y, cx, h / 2, 1.3 * k, k))
    t.save("tab_frame", rgba(0xE0B451, a, w, h), (8 * k, 4 * k))
    w, h = 24 * k, OPT_H * k
    x, y = grid(w, h)
    d = chamfer(x, y, w, h, 3 * k, 0.3 * k)
    t.save("opt_fill", rgba(noise(w, h, 0xE6D3A6, 12, 6), solid(d), w, h), (4 * k, 4 * k))
    t.save("opt_frame", rgba(brown, line(d, 0.6 * k) * 0.8, w, h), (4 * k, 4 * k))
    t.save("sel_fill", rgba(noise(w, h, 0xF3E4B8, 12, 6), solid(d), w, h), (4 * k, 4 * k))
    t.save("sel_frame", rgba(0xD9A840, line(d, 1.0 * k), w, h), (4 * k, 4 * k))
    bell(t, k)
    # Gold arrow: a solid point with a notched tail.
    w, h = 9 * k, 9 * k
    x, y = grid(w, h)
    head = np.maximum(x - (w - 0.5 * k), np.abs(y - h / 2) - (h / 2 - 0.5 * k) * (w - x) / w)
    d = np.maximum(head, 0.5 * k + np.abs(y - h / 2) * 0.8 - x)  # the tail's V cut
    t.save("cursor", over(rgba(0x7A5418, line(d, 0.4 * k), w, h), rgba(0xE8B850, solid(d), w, h)))
    t.write(font="villagechatter:dialogue", text="#3A2414", name="#F6E7C4", option="#4A3018",
            selected="#2A1408", narration="#5A4630", shadow=False, italic=True, alpha={"box": 0.95, "opt": 0.9, "sel": 1.0, "frame": 1.0},
            minAlpha=0.7)


def bronze():
    """Charcoal glass in a thin bronze bevel with cut corners; choices come to a point at each end."""
    t = Theme("bronze", 4)
    k = t.k

    def bronze_line(d, y, h, width):
        """Bronze that catches the light at the top and darkens to the bottom."""
        g = (y / h)[..., None]
        rgb = np.array([0xE6, 0xB8, 0x72]) * (1 - g) + np.array([0x8A, 0x5A, 0x28]) * g
        return rgba(down_rgb(rgb, d.shape), line(d, width), d.shape[1] // SS, d.shape[0] // SS)

    def down_rgb(rgb, shape):
        h, w = shape[0] // SS, shape[1] // SS
        return rgb.reshape(h, SS, w, SS, 3).mean(axis=(1, 3))

    w = h = 32 * k
    x, y = grid(w, h)
    t.save("box_fill", rgba(0x15120E, solid(chamfer(x, y, w, h, 5 * k)), w, h), (12 * k, 12 * k))
    d = chamfer(x, y, w, h, 5 * k, 0.5 * k)
    inner = chamfer(x, y, w, h, 4 * k, 2.5 * k)
    frame = over(bronze_line(d, y, h, 1.2 * k), rgba(0x6A4A24, line(inner, 0.5 * k) * 0.8, w, h))
    t.save("box_frame", frame, (12 * k, 12 * k))
    w, h = 24 * k, TAB_H * k
    x, y = grid(w, h)
    d = chamfer(x, y, w, h, 3 * k, 0.3 * k)
    t.save("tab_fill", rgba(0x0E0C09, solid(d), w, h), (4 * k, 4 * k))
    t.save("tab_frame", bronze_line(d, y, h, 1.0 * k), (4 * k, 4 * k))
    w, h = 24 * k, OPT_H * k
    x, y = grid(w, h)
    d = pointed(x, y, w, h, 5 * k, 0.4 * k)
    t.save("opt_fill", rgba(0x15120E, solid(d), w, h), (8 * k, 7 * k))
    t.save("opt_frame", rgba(0x8A5A28, line(d, 0.7 * k) * 0.7, w, h), (8 * k, 7 * k))
    t.save("sel_fill", rgba(0x2E2416, solid(d), w, h), (8 * k, 7 * k))
    t.save("sel_frame", bronze_line(d, y, h, 1.0 * k), (8 * k, 7 * k))
    cursor(t, 0xE0B070, 1.1)
    t.write(font="villagechatter:dialogue", text="#EDE3C8",
            name="#F0D7A0", option="#BFAF8E", selected="#FFF4DA", narration="#CFC3A6", shadow=True,
            alpha={"box": 1.0, "opt": 0.9, "sel": 1.1, "frame": 1.0})


def cursor(t, color, width):
    """Hollow right-pointing triangle with rounded corners, 7 x 9 GUI px."""
    k = t.k
    w, h = 7 * k, 9 * k
    x, y = grid(w, h)
    tw = width * k
    pts = [(tw, tw), (w - tw, h / 2), (tw, h - tw)]

    def seg(p, q):
        px, py = x - p[0], y - p[1]
        qx, qy = q[0] - p[0], q[1] - p[1]
        u = np.clip((px * qx + py * qy) / (qx * qx + qy * qy), 0, 1)
        return np.hypot(px - u * qx, py - u * qy)

    d = np.minimum(np.minimum(seg(pts[0], pts[1]), seg(pts[1], pts[2])), seg(pts[2], pts[0]))
    t.save("cursor", rgba(color, np.clip(0.5 - (d - tw / 2), 0, 1), w, h))


if __name__ == "__main__":
    for make in (classic, blocky, terminal, northern, storybook, bronze):
        make()
