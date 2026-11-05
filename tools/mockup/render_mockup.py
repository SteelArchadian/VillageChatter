"""
Renders what the dialogue screen looks like, using the same layout numbers as DialogueScreen.java and
Minecraft's own bitmap font, over an in-game background.

Outputs (in previews/):
  dialogue_mockup.png      - 1920x1080, GUI scale 3, options visible with the first one selected
  dialogue_mockup.mp4      - 1280x720, GUI scale 2, animated: text scroll + babble, options fade in,
                             hover, click flash, with the real UI sounds

    python tools/mockup/render_mockup.py
"""
import math
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "sound_gen"))
PREVIEWS = os.path.join(ROOT, "previews")

# ---- mirror of DialogueScreen.java constants ----
BOX_MAX_WIDTH, BOX_PAD_X, BOX_PAD_Y, BOX_RADIUS = 300, 14, 10, 6
BOTTOM_MARGIN, LINE_HEIGHT, MIN_LINES = 12, 11, 2
BOX_COLOR, TEXT_COLOR, NAME_COLOR = 0xB4000000, 0xFFF2F2F2, 0xFFF0D59A
OPT_HEIGHT, OPT_GAP, OPT_PAD_X, OPT_RADIUS = 15, 3, 10, 4
OPT_BG, OPT_BG_SELECTED, OPT_OUTLINE_SELECTED = 0x99000000, 0xD8141414, 0xFFEDE6D3
OPT_TEXT, OPT_TEXT_SELECTED, CURSOR_COLOR = 0xFFC4C4C4, 0xFFFFFFFF, 0xFFF5EBD0
FLASH_MS, OPTIONS_FADE_MS, COMMA_PAUSE_MS, SENTENCE_PAUSE_MS = 140, 160, 120, 260
TEXT_SPEED = 30

NAME = "Farmer"
GREETING = "Hrmm? Oh, hello there, traveler. Careful where you step, those are my carrots."
OPTIONS = ["Show me your wares.", "Heard anything interesting?", "Goodbye."]

# ---- Minecraft font (assets/minecraft/textures/font/ascii.png) ----
_atlas = np.array(Image.open(os.path.join(HERE, "ascii.png")).convert("RGBA"))[:, :, 3] > 0
GLYPHS = {}
for code in range(32, 127):
    gx, gy = (code % 16) * 8, (code // 16) * 8
    g = _atlas[gy:gy + 8, gx:gx + 8]
    cols = np.where(g.any(axis=0))[0]
    GLYPHS[chr(code)] = (g, (cols.max() + 1) if len(cols) else 0)


def advance(ch):
    return 4 if ch == " " else GLYPHS.get(ch, GLYPHS["?"])[1] + 1


def text_width(s):
    return sum(advance(c) for c in s)


def wrap(text, width):
    lines, cur = [], ""
    for word in text.split(" "):
        test = word if not cur else cur + " " + word
        if text_width(test) <= width:
            cur = test
        else:
            lines.append(cur)
            cur = word
    lines.append(cur)
    return lines


# ---- drawing onto a float canvas in GUI coordinates ----
class Canvas:
    def __init__(self, bg, scale):
        self.px = bg.astype(np.float32) / 255
        self.s = scale
        self.w, self.h = bg.shape[1] // scale, bg.shape[0] // scale

    def fill(self, x0, y0, x1, y1, argb):
        a = ((argb >> 24) & 0xFF) / 255
        if a <= 0 or x1 <= x0 or y1 <= y0:
            return
        rgb = np.array([(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF], np.float32) / 255
        s = self.s
        region = self.px[int(y0 * s):int(y1 * s), int(x0 * s):int(x1 * s)]
        region[:] = region * (1 - a) + rgb * a

    def gradient(self, x0, y0, x1, y1, top, bottom):
        for y in range(y0, y1):
            t = (y - y0) / max(1, y1 - y0 - 1)
            self.fill(x0, y, x1, y + 1, lerp_color(top, bottom, t))

    def rounded(self, x, y, w, h, r, color):
        r = min(r, min(w, h) // 2)
        for i in range(r):
            dy = r - i - 0.5
            inset = r - int(round(math.sqrt(r * r - dy * dy)))
            self.fill(x + inset, y + i, x + w - inset, y + i + 1, color)
            self.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color)
        self.fill(x, y + r, x + w, y + h - r, color)

    def text(self, s, x, y, color, shadow=False):
        if shadow:
            sc = (color & 0xFF000000) | ((color & 0xFCFCFC) >> 2)
            self._text(s, x + 1, y + 1, sc)
        self._text(s, x, y, color)

    def _text(self, s, x, y, color):
        for ch in s:
            g, _ = GLYPHS.get(ch, GLYPHS["?"])
            if ch != " ":
                for gy, gx in zip(*np.nonzero(g)):
                    self.fill(x + gx, y + gy, x + gx + 1, y + gy + 1, color)
            x += advance(ch)

    def image(self):
        return Image.fromarray((np.clip(self.px, 0, 1) * 255).astype(np.uint8))


def with_alpha(argb, f):
    a = round(((argb >> 24) & 0xFF) * f)
    return (max(a, 4) << 24) | (argb & 0xFFFFFF)


def lerp_color(a, b, t):
    out = 0
    for shift in (24, 16, 8, 0):
        ca, cb = (a >> shift) & 0xFF, (b >> shift) & 0xFF
        out |= int(ca + (cb - ca) * t) << shift
    return out


# ---- layout (same as DialogueScreen.layout) ----
def layout(w, h):
    box_w = min(BOX_MAX_WIDTH, w - 32)
    lines = wrap(GREETING, box_w - BOX_PAD_X * 2)
    box_h = max(MIN_LINES, len(lines)) * LINE_HEIGHT + BOX_PAD_Y * 2 - 2
    box_x, box_y = (w - box_w) // 2, h - box_h - BOTTOM_MARGIN
    opt_w = max(text_width(o) for o in OPTIONS) + OPT_PAD_X * 2
    column_h = len(OPTIONS) * (OPT_HEIGHT + OPT_GAP) - OPT_GAP
    if w - (box_x + box_w) >= opt_w + 24:
        opt_x, opt_y = box_x + box_w + 12, box_y + box_h - column_h
    else:
        opt_x, opt_y = box_x + box_w - opt_w, box_y - 8 - column_h
    return dict(lines=lines, box=(box_x, box_y, box_w, box_h), opt=(opt_x, opt_y, opt_w))


def draw(bg, scale, revealed, options_alpha, selected, flash_t, now_ms):
    c = Canvas(bg, scale)
    L = layout(c.w, c.h)
    bx, by, bw, bh = L["box"]
    c.gradient(0, c.h * 2 // 3, c.w, c.h, 0x00000000, 0x50000000)
    c.rounded(bx, by, bw, bh, BOX_RADIUS, BOX_COLOR)
    c.text(NAME, bx + 10, by - 11, NAME_COLOR, shadow=True)
    budget, y = revealed, by + BOX_PAD_Y
    for line in L["lines"]:
        if budget <= 0:
            break
        c.text(line[:budget], bx + BOX_PAD_X, y, TEXT_COLOR)
        budget -= len(line)
        y += LINE_HEIGHT
    if options_alpha > 0:
        ox, oy0, ow = L["opt"]
        slide = round((1 - options_alpha) * 6)
        for i, label in enumerate(OPTIONS):
            x, oy = ox + slide, oy0 + i * (OPT_HEIGHT + OPT_GAP)
            sel = i == selected
            bg_c = OPT_BG_SELECTED if sel else OPT_BG
            tx = OPT_TEXT_SELECTED if sel else OPT_TEXT
            if sel and flash_t is not None:
                bg_c = lerp_color(0xF0FFFFFF, OPT_BG_SELECTED, flash_t)
                tx = lerp_color(0xFF202020, OPT_TEXT_SELECTED, flash_t)
            if sel:
                c.rounded(x - 1, oy - 1, ow + 2, OPT_HEIGHT + 2, OPT_RADIUS + 1, with_alpha(OPT_OUTLINE_SELECTED, options_alpha * 0.55))
            c.rounded(x, oy, ow, OPT_HEIGHT, OPT_RADIUS, with_alpha(bg_c, options_alpha))
            if options_alpha > 0.1:
                c.text(label, x + OPT_PAD_X, oy + (OPT_HEIGHT - 8) // 2, with_alpha(tx, options_alpha))
            if sel:
                bob = round(math.sin(now_ms / 160) * 1.2)
                cx, cy = x - 8 + bob, oy + OPT_HEIGHT // 2
                for dy in range(-3, 4):
                    c.fill(cx, cy + dy, cx + 4 - abs(dy), cy + dy + 1, with_alpha(CURSOR_COLOR, options_alpha))
    return c.image()


def char_schedule(flat):
    """Time (ms) each character appears, same pauses as DialogueScreen.pauseAfter."""
    t, out = 0, []
    for i, ch in enumerate(flat):
        out.append(t)
        end_of_word = i + 1 >= len(flat) or flat[i + 1] == " "
        pause = COMMA_PAUSE_MS if ch in ",;:" and end_of_word else SENTENCE_PAUSE_MS if ch in ".!?" and end_of_word else 0
        t += 1000 // TEXT_SPEED + pause
    return out


def main():
    os.makedirs(PREVIEWS, exist_ok=True)
    bg = np.array(Image.open(os.path.join(HERE, "background.png")).convert("RGB"))

    # Still: 1920x1080 at GUI scale 3, text done, options in, first selected.
    draw(bg, 3, 10_000, 1.0, 0, None, 0).save(os.path.join(PREVIEWS, "dialogue_mockup.png"))
    print("wrote dialogue_mockup.png")

    # Animation: 1280x720 at GUI scale 2.
    small = np.array(Image.fromarray(bg).resize((1280, 720), Image.LANCZOS))
    flat = "".join(layout(640, 360)["lines"])
    sched = char_schedule(flat)
    open_at = 300
    done = open_at + sched[-1] + 1000 // TEXT_SPEED
    hover1, hover2, click = done + 900, done + 1500, done + 2300
    end = click + FLASH_MS + 500
    fps = 30
    frames_dir = tempfile.mkdtemp()
    n = int(end / 1000 * fps)
    for f in range(n):
        t = f * 1000 / fps
        if t < open_at:
            img = Image.fromarray(small)
        else:
            revealed = sum(1 for s in sched if open_at + s <= t)
            alpha = 0.0 if t < done else min(1.0, (t - done) / OPTIONS_FADE_MS)
            selected = 1 if hover1 <= t < hover2 else 0
            flash = None if t < click else min(1.0, (t - click) / FLASH_MS)
            img = draw(small, 2, revealed, alpha, selected, flash, t)
            if t >= click + FLASH_MS:
                img = Image.fromarray(small)  # screen closes into the trade menu
        img.save(os.path.join(frames_dir, f"{f:05d}.png"))

    audio = render_audio(flat, sched, open_at, done, hover1, hover2, click, end)
    out = os.path.join(PREVIEWS, "dialogue_mockup.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(fps), "-i", os.path.join(frames_dir, "%05d.png"),
                    "-i", audio, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-c:a", "aac", "-shortest", out],
                   check=True)
    print("wrote dialogue_mockup.mp4")


def render_audio(flat, sched, open_at, done, hover1, hover2, click, end):
    import generate as gen
    import bark_voices as bv
    sr = gen.SR
    out = np.zeros(int((end / 1000 + 1) * sr))
    ui = {k: fn() for k, fn in gen.UI.items()}

    def at(ms, x, vol=1.0):
        gen.mix_at(out, x * vol, int(ms / 1000 * sr))

    at(open_at - 80, ui["open"], 0.6)
    # Voice: the natural voice in REACT mode, as the game picks it ("Hrmm? ..." opens with a question sound).
    reaction = bv.reaction_for(flat, "greeting", set(), np.random.default_rng(0))
    clip_dir = os.path.join(gen.SOUNDS, "voice", "natural", "0", reaction)
    if os.path.isdir(clip_dir):
        at(open_at, gen.load_audio(os.path.join(clip_dir, sorted(os.listdir(clip_dir))[0])), 0.9)
    at(done, ui["advance"], 0.35)
    at(hover1, ui["hover"], 0.5)
    at(hover2, ui["hover"], 0.5)
    at(click, ui["select"], 0.6)
    path = os.path.join(tempfile.mkdtemp(), "audio.wav")
    gen.write_wav(path, np.clip(out, -1, 1))
    return path


if __name__ == "__main__":
    main()
