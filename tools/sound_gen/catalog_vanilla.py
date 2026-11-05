"""
Catalog the vanilla sounds we can build on: length, pitch, how "voiced" (vocal) vs noisy (sound effect) each is.
Writes tools/sound_gen/SOUND_CATALOG.md and a spectrogram sheet per creature to previews/catalog/.

    python tools/sound_gen/catalog_vanilla.py
"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import generate as gen  # noqa: E402

ASSETS = os.path.expanduser("~/.gradle/caches/neoformruntime/assets")
INDEX = json.load(open(os.path.join(ASSETS, "indexes", "17.json")))["objects"]
SR = gen.SR

CREATURES = {
    "villager": "mob/villager/",
    "wandering trader": "mob/wandering_trader/",
    "iron golem": "mob/irongolem/",
    "wolf (dog)": "mob/wolf/",
    "cat": "mob/cat/",
    "parrot": "mob/parrot/",
    "piglin": "mob/piglin/",
    "pillager": "mob/pillager/",
    "vindicator": "mob/vindication_illager/",
    "evoker": "mob/evocation_illager/",
}

# What each vanilla file group is, from how the game uses it.
ROLE = {
    "villager": {"idle": "voice: ambient hmm", "yes": "voice: happy / agree", "no": "voice: refuse / grumpy",
                 "haggle": "voice: considering / questioning", "hit": "hurt (avoid)", "death": "death (avoid)",
                 "celebrate": "voice: cheer"},
    "wandering trader": {"idle": "voice: ambient", "yes": "voice: agree", "no": "voice: refuse", "haggle": "voice: haggle",
                         "hurt": "hurt (avoid)", "death": "death (avoid)", "appeared": "magic SFX", "disappeared": "magic SFX",
                         "reappeared": "magic SFX", "drink": "drinking SFX"},
    "iron golem": {"walk": "SFX: footstep", "damage": "SFX: metal cracking", "hit": "hurt grunt (avoid)",
                   "death": "death (avoid)", "repair": "SFX: repair clink", "throw": "SFX: attack swing"},
    "wolf (dog)": {"bark": "voice: bark", "growl": "voice: growl", "whine": "voice: whine", "panting": "voice: pant",
                   "howl": "voice: howl", "hurt": "hurt (avoid)", "death": "death (avoid)", "shake": "SFX: shaking off water",
                   "step": "SFX: footstep"},
    "cat": {"meow": "voice: meow", "purr": "voice: purr", "purreow": "voice: purr-meow", "hiss": "voice: hiss",
            "beg": "voice: begging meow", "idle": "voice: ambient meow", "hitt": "hurt (avoid)", "hurt": "hurt (avoid)",
            "death": "death (avoid)", "eat": "SFX: eating"},
    "parrot": {"idle": "voice: chirp", "fly": "SFX: wings", "step": "SFX: footstep", "hurt": "hurt (avoid)",
               "death": "death (avoid)", "eat": "SFX: eating"},
    "piglin": {"idle": "voice: talk / ambient grunts", "admire": "voice: wonder at gold (admire)",
               "celebrate": "voice: happy / cheer", "jealous": "voice: grumpy huff (wants your gold)",
               "angry": "voice: angry squeal (attacking)", "retreat": "voice: scared, backing off",
               "hurt": "hurt (avoid)", "death": "death (avoid)", "step": "SFX: footstep",
               "converted": "SFX: zombifying (avoid)"},
    "pillager": {"ambient": "voice: talk / mutter", "idle": "voice: talk / mutter", "celebrate": "voice: laugh / raid cheer",
                 "horn_celebrate": "SFX: raid horn (avoid)", "hurt": "hurt (avoid)", "death": "death (avoid)"},
    "vindicator": {"idle": "voice: talk / mutter", "ambient": "voice: talk / mutter", "celebrate": "voice: laugh / cheer",
                   "hurt": "hurt (avoid)", "death": "death (avoid)"},
    "evoker": {"idle": "voice: talk / mutter", "ambient": "voice: talk / mutter", "celebrate": "voice: laugh / cheer",
               "cast": "SFX: spell (avoid)", "prepare": "SFX: spell (avoid)", "hurt": "hurt (avoid)", "death": "death (avoid)"},
}


def load(key):
    h = INDEX[key]["hash"]
    return gen.load_audio(os.path.join(ASSETS, "objects", h[:2], h))


def analyze(x):
    hop = int(0.01 * SR)
    frames = [x[i:i + 2048] for i in range(0, len(x) - 2048, hop)]
    env = np.array([np.sqrt(np.mean(f ** 2)) for f in frames]) if frames else np.zeros(1)
    loud = env > env.max() * 0.2
    f0s, voiced = [], 0
    for f, l in zip(frames, loud):
        if not l:
            continue
        f = f - f.mean()
        c = np.correlate(f, f, "full")[2047:]
        lo, hi = int(SR / 900), int(SR / 50)
        if c[0] <= 0:
            continue
        k = lo + int(np.argmax(c[lo:hi]))
        if c[k] / c[0] > 0.5:
            voiced += 1
            f0s.append(SR / k)
    n_loud = max(1, int(loud.sum()))
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    freqs = np.fft.rfftfreq(len(x), 1 / SR)
    centroid = float(np.sum(freqs * spec) / (np.sum(spec) + 1e-9))
    return {
        "dur": len(x) / SR,
        "voiced": voiced / n_loud,
        "f0": float(np.median(f0s)) if f0s else 0.0,
        "centroid": centroid,
    }


def role_of(creature, name):
    for prefix, role in ROLE.get(creature, {}).items():
        if name.startswith(prefix):
            return role
    return "?"


def main():
    md = ["# Vanilla sound catalog", "",
          "What Minecraft gives us to build on. **voiced** = share of the loud part that has a clear pitch "
          "(high = a voice, low = noise / sound effect). Only *voice* rows are candidates for dialogue; hurt, death "
          "and sound effects are not.", ""]
    os.makedirs(os.path.join(gen.PREVIEWS, "catalog"), exist_ok=True)
    for creature, prefix in CREATURES.items():
        keys = sorted(k for k in INDEX if k.startswith("minecraft/sounds/" + prefix) and k.endswith(".ogg"))
        md += [f"## {creature}", "", "| file | role | length | voiced | pitch | brightness |", "|---|---|---|---|---|---|"]
        rows = []
        for k in keys:
            name = k.split("/")[-1][:-4]
            x = load(k)
            a = analyze(x)
            md.append(f"| {name} | {role_of(creature, name)} | {a['dur']:.2f}s | {a['voiced']:.0%} | "
                      f"{a['f0']:.0f} Hz | {a['centroid']:.0f} Hz |")
            rows.append((name, x))
        md.append("")
        sheet(creature, rows)
    with open(os.path.join(HERE, "SOUND_CATALOG.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    print("wrote SOUND_CATALOG.md")


def sheet(creature, rows):
    imgs = []
    for name, x in rows:
        _, _, Z = signal.stft(x, SR, nperseg=1024, noverlap=768)
        m = 20 * np.log10(np.abs(Z)[:240] + 1e-6)
        m = np.clip((m - m.max() + 70) / 70, 0, 1)
        imgs.append((name, Image.fromarray((255 * m[::-1]).astype(np.uint8)).resize((min(900, max(60, Z.shape[1] * 2)), 90))))
    h = sum(i.height + 14 for _, i in imgs)
    out = Image.new("L", (1000, h), 0)
    d = ImageDraw.Draw(out)
    y = 0
    for name, i in imgs:
        d.text((2, y + 2), name, fill=255)
        out.paste(i, (95, y + 12))
        y += i.height + 14
    out.save(os.path.join(gen.PREVIEWS, "catalog", creature.split(" ")[0] + ".png"))


if __name__ == "__main__":
    main()
