"""
Audio-style test bench (previews only; nothing here goes into the mod).

Voice styles built from Minecraft's own villager sounds. The game can only play a vanilla sound whole, at a pitch
(0.5-2.0, which also changes its speed), a volume and a time, so these previews use exactly those controls and
nothing else: whatever sounds good here can be done in-game without shipping any files.

Reads the vanilla sounds from the local dev-toolchain cache (~/.gradle/caches/neoformruntime/assets).

    python tools/sound_gen/vanilla_previews.py
Writes previews/vanilla/*.mp3
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import generate as gen  # noqa: E402

SR = gen.SR
ASSETS = os.path.expanduser("~/.gradle/caches/neoformruntime/assets")
OUT = os.path.join(gen.PREVIEWS, "vanilla")
rng = np.random.default_rng(11)


def load_vanilla(name):
    index = json.load(open(os.path.join(ASSETS, "indexes", "17.json")))["objects"]
    h = index[f"minecraft/sounds/{name}.ogg"]["hash"]
    return gen.load_audio(os.path.join(ASSETS, "objects", h[:2], h))


def event(prefix, names):
    return [load_vanilla(f"{prefix}/{n}") for n in names]


V = {
    "idle": event("mob/villager", ["idle1", "idle2", "idle3"]),
    "yes": event("mob/villager", ["yes1", "yes2", "yes3"]),
    "no": event("mob/villager", ["no1", "no2", "no3"]),
    "haggle": event("mob/villager", ["haggle1", "haggle2", "haggle3"]),
}
T = {
    "idle": event("mob/wandering_trader", [f"idle{i}" for i in range(1, 6)]),
    "yes": event("mob/wandering_trader", [f"yes{i}" for i in range(1, 5)]),
    "no": event("mob/wandering_trader", [f"no{i}" for i in range(1, 6)]),
    "haggle": event("mob/wandering_trader", [f"haggle{i}" for i in range(1, 4)]),
}
GOLEM = {
    "walk": event("mob/irongolem", [f"walk{i}" for i in range(1, 5)]),
    "damage": event("mob/irongolem", ["damage1", "damage2"]),
    "hit": event("mob/irongolem", [f"hit{i}" for i in range(1, 5)]),
    "repair": event("mob/irongolem", ["repair"]),
}


def play(out, sounds, t, pitch, volume=1.0):
    """What SoundManager.play does: a random variant of the event, resampled by pitch (clamped like vanilla)."""
    clip = sounds[rng.integers(len(sounds))]
    gen.mix_at(out, gen.pitch_shift(clip, float(np.clip(pitch, 0.5, 2.0))) * volume, int(t * SR))


# --------------------------------------------------------------------------- the demo conversation

LINES = [
    # text, mood, villager pitch (per-UUID in game; kids x1.32)
    ("Hrmm? Oh, hello there, traveler. Careful where you step, those are my carrots.", "neutral", 1.0),
    ("Have you seen my hat? I can't find it anywhere.", "neutral", 0.92),
    ("Hmph. What do YOU want?", "grumpy", 0.9),
    ("Ha! You're funny. I like you.", "happy", 1.08),
    ("Hi! Hi! Are you an adventurer?", "kid", 1.32),
]
CPS = 30


def timeline(text):
    """Time each character appears in the dialogue box (same pauses as the game)."""
    times, t = [], 0.0
    for i, ch in enumerate(text):
        times.append(t)
        eow = i + 1 >= len(text) or text[i + 1] == " "
        t += 1 / CPS + (0.12 if ch in ",;:" and eow else 0.26 if ch in ".!?" and eow else 0)
    return times, t


def phrases(text):
    """(start index, terminator) of each phrase."""
    out, start = [], 0
    for i, ch in enumerate(text):
        if ch in ",.!?" and (i + 1 >= len(text) or text[i + 1] == " "):
            out.append((start, ch))
            start = i + 2
    if start < len(text):
        out.append((start, "."))
    return out


def words(text):
    out, i = [], 0
    for w in text.split(" "):
        letters = sum(c.isalpha() for c in w)
        if letters:
            out.append((i, letters, w[-1]))
        i += len(w) + 1
    return out


def reaction(text, mood):
    """Which vanilla sound fits the line as a whole."""
    first = text.split(". ")[0]
    if mood == "grumpy":
        return "no"
    if mood == "happy" or text.lower().startswith("ha"):
        return "yes"
    if "?" in first:
        return "haggle"
    if "!" in first:
        return "yes"
    return "idle"


def jitter(a=0.05):
    return rng.uniform(1 - a, 1 + a)


# --------------------------------------------------------------------------- styles

def style_react(text, mood, vp, sounds):
    """1. One villager sound per line, picked from the line's mood. Closest to vanilla, BotW-like restraint."""
    _, total = timeline(text)
    out = np.zeros(int((total + 1.5) * SR))
    play(out, sounds[reaction(text, mood)], 0.0, vp * jitter())
    return out


def style_phrases(text, mood, vp, sounds):
    """2. A villager sound at the start of every phrase: questions haggle, exclamations say yes, the rest hmm."""
    times, total = timeline(text)
    out = np.zeros(int((total + 1.5) * SR))
    for start, term in phrases(text):
        kind = "no" if mood == "grumpy" else "haggle" if term == "?" else "yes" if term == "!" else "idle"
        play(out, sounds[kind], times[start], vp * jitter() * (1.05 if term == "?" else 1.0), 0.8)
    return out


def style_chirp(text, mood, vp, sounds):
    """3. Sped-up villager chirps, one per word (short words chirp faster/higher), Animal Crossing energy."""
    times, total = timeline(text)
    out = np.zeros(int((total + 1.5) * SR))
    free = 0.0
    base = {"grumpy": 1.45, "happy": 1.85, "kid": 1.75, "neutral": 1.7}[mood]
    for i, letters, end in words(text):
        t = times[i]
        if t < free:
            continue
        p = vp * base * (1.12 if letters <= 3 else 1.0 if letters <= 6 else 0.9) * jitter(0.06)
        p *= 1.08 if end == "?" else 1.0
        kind = "no" if mood == "grumpy" else "idle" if letters > 3 else "yes"
        play(out, sounds[kind], t, p, 0.75)
        free = t + 0.55 / p  # villager sounds are ~0.5-0.8 s at normal speed
    return out


def style_murmur(text, mood, vp, sounds):
    """4. Soft, low murmuring: gentle overlapping hmms under the whole line, like someone talking to themselves."""
    times, total = timeline(text)
    out = np.zeros(int((total + 1.5) * SR))
    t = 0.0
    while t < total - 0.2:
        kind = "no" if mood == "grumpy" else ("idle" if rng.random() < 0.7 else "haggle")
        play(out, sounds[kind], t, vp * 0.92 * jitter(0.06), 0.6)
        t += rng.uniform(0.32, 0.48) / vp
    return out


def style_react_chirp(text, mood, vp, sounds):
    """5. A full-size reaction to open the line, then quiet sped-up chirps for the rest."""
    _, total = timeline(text)
    out = style_react(text, mood, vp, sounds)
    tail = style_chirp(text, mood, vp, sounds) * 0.55
    tail[: int(0.7 * SR)] = 0  # let the reaction breathe first
    n = min(len(out), len(tail))
    out[:n] += tail[:n]
    return out


STYLES = [
    ("1_react", style_react),
    ("2_phrases", style_phrases),
    ("3_chirp", style_chirp),
    ("4_murmur", style_murmur),
    ("5_react_chirp", style_react_chirp),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in STYLES:
        parts = []
        for text, mood, vp in LINES:
            parts += [fn(text, mood, vp, V)[: int((timeline(text)[1] + 0.9) * SR)], gen.silence(0.45)]
        # and the wandering trader, with its own sounds
        text = "Ah, a customer! I come from very far away. Buy something."
        parts += [fn(text, "neutral", 1.0, T)[: int((timeline(text)[1] + 1.2) * SR)]]
        gen.export(np.clip(np.concatenate(parts), -1, 1), mp3_path=os.path.join(OUT, f"{name}.mp3"))
        print("wrote", name)

    # Golem candidates (vanilla has no golem voice): heavy step, slowed-down groan, repair clink.
    parts = []
    for label, sounds, pitch, vol in [("step", GOLEM["walk"], 0.8, 1.0), ("groan", GOLEM["damage"], 0.6, 0.6),
                                      ("low groan", GOLEM["damage"], 0.5, 0.6), ("clank", GOLEM["hit"], 0.7, 0.7),
                                      ("happy clink", GOLEM["repair"], 1.1, 0.8)]:
        for _ in range(2):
            buf = np.zeros(int(1.6 * SR))
            play(buf, sounds, 0.0, pitch, vol)
            parts += [buf]
        parts.append(gen.silence(0.4))
    gen.export(np.clip(np.concatenate(parts), -1, 1), mp3_path=os.path.join(OUT, "golem_options.mp3"))
    print("wrote golem_options")


if __name__ == "__main__":
    main()
