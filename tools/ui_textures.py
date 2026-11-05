"""
Textures for the dialogue screen (DialogueScreen.java), drawn smooth and anti-aliased at 4 pixels per GUI pixel so
the pill ends and pointer stay round at any GUI scale (the old fill() corners were pixel stairs).

Every texture is a white shape in its alpha channel; the screen tints it, so colours and opacity stay settings.
Pills are drawn as left cap + stretched middle + right cap, the caps always as wide as half the height.

    python tools/ui_textures.py -> src/main/resources/assets/villagechatter/textures/gui/dialogue/*.png
"""
import json
import math
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "villagechatter", "textures", "gui", "dialogue")
K = 4  # texture pixels per GUI pixel
SS = 4  # supersampling for the anti-aliasing


def pill_sdf(w, h, inset=0.0):
    """Signed distance (texture px, negative inside) to a pill filling w x h, sampled SS times finer."""
    ys, xs = np.mgrid[0:h * SS, 0:w * SS]
    x = (xs + 0.5) / SS
    y = (ys + 0.5) / SS
    r = h / 2 - inset
    cx = np.clip(x, h / 2, w - h / 2)
    return np.hypot(x - cx, y - h / 2) - r


def to_image(alpha, w, h):
    a = alpha.reshape(h, SS, w, SS).mean(axis=(1, 3))
    rgba = np.zeros((h, w, 4), np.uint8)
    rgba[..., :3] = 255
    rgba[..., 3] = np.clip(a * 255, 0, 255).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


def fill(gui_h, feather):
    """Solid pill whose edge fades out over `feather` GUI px, so it reads soft like smoked glass, not cut out."""
    h = gui_h * K
    w = h + 16
    d = pill_sdf(w, h)
    f = feather * K
    a = np.clip(-d / f, 0, 1)
    return to_image(a * a * (3 - 2 * a), w, h)


def ring(gui_h, width):
    """Outline of a pill, `width` GUI px thick, inside the shape's edge."""
    h = gui_h * K
    w = h + 16
    d = pill_sdf(w, h)
    t = width * K
    a = np.clip(0.5 - (np.abs(d + t / 2) - t / 2), 0, 1)
    return to_image(a, w, h)


def trim(gui_h):
    """BotW's faint brackets: a short arc inside each end of the box with a dot at each tip (caps only)."""
    h = gui_h * K
    w = h + 16
    ys, xs = np.mgrid[0:h * SS, 0:w * SS]
    x = (xs + 0.5) / SS
    y = (ys + 0.5) / SS
    a = np.zeros_like(x)
    r = h / 2 - 9 * K
    span = math.radians(38)
    for cx, sign in ((h / 2, -1), (w - h / 2, 1)):
        dx, dy = x - cx, y - h / 2
        ang = np.arctan2(dy, dx * sign)  # 0 points outwards
        on = np.abs(ang) <= span
        a = np.maximum(a, np.where(on, np.clip(0.5 - (np.abs(np.hypot(dx, dy) - r) - 0.55 * K), 0, 1), 0))
        for s in (-1, 1):
            tx, ty = cx + sign * r * math.cos(span), h / 2 + s * r * math.sin(span)
            a = np.maximum(a, np.clip(0.5 - (np.hypot(x - tx, y - ty) - 1.1 * K), 0, 1))
    return to_image(a, w, h)


def cursor(gui_w, gui_h, width):
    """Hollow right-pointing triangle with rounded corners."""
    w, h = gui_w * K, gui_h * K
    ys, xs = np.mgrid[0:h * SS, 0:w * SS]
    x = (xs + 0.5) / SS
    y = (ys + 0.5) / SS
    t = width * K
    pad = t
    pts = [(pad, pad), (w - pad, h / 2), (pad, h - pad)]

    def seg(p, q):
        px, py = x - p[0], y - p[1]
        qx, qy = q[0] - p[0], q[1] - p[1]
        u = np.clip((px * qx + py * qy) / (qx * qx + qy * qy), 0, 1)
        return np.hypot(px - u * qx, py - u * qy)

    d = np.minimum(np.minimum(seg(pts[0], pts[1]), seg(pts[1], pts[2])), seg(pts[2], pts[0]))
    return to_image(np.clip(0.5 - (d - t / 2), 0, 1), w, h)


def save(img, name):
    img.save(os.path.join(OUT, name + ".png"))
    with open(os.path.join(OUT, name + ".png.mcmeta"), "w") as f:
        json.dump({"texture": {"blur": True}}, f)
    print(name, img.size)


def main():
    os.makedirs(OUT, exist_ok=True)
    save(fill(40, 1.5), "box")
    save(fill(17, 1.0), "option")
    save(ring(17, 1.6), "option_ring")
    save(cursor(9, 12, 1.3), "cursor")


if __name__ == "__main__":
    main()
