"""
Village Chatter sound generator.

Synthesizes every voice "syllable" set and UI sound the mod uses, writing:
  - OGG files into src/main/resources/assets/villagechatter/sounds/  (used in-game)
  - MP3 previews into previews/                                       (for listening)

Usage (from the project root):
    python tools/sound_gen/generate.py

Needs numpy, scipy and ffmpeg (with libvorbis + libmp3lame) on PATH.
The "bountiful" voice set is cut from tools/sound_gen/source/bountiful_mumble.ogg,
which is borrowed from Bountiful NPC for the alpha only (see README).
"""
import json
import os
import subprocess
import tempfile

import numpy as np
import scipy.io.wavfile as wavfile
from scipy import signal

SR = 44100
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOUNDS = os.path.join(ROOT, "src", "main", "resources", "assets", "villagechatter", "sounds")
PREVIEWS = os.path.join(ROOT, "previews")
SOURCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "source")

rng = np.random.default_rng(1337)


# --------------------------------------------------------------------------- helpers

def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def fade(x, fin=0.005, fout=0.02):
    x = x.copy()
    n_in, n_out = min(len(x), int(fin * SR)), min(len(x), int(fout * SR))
    if n_in:
        x[:n_in] *= np.linspace(0, 1, n_in) ** 2
    if n_out:
        x[-n_out:] *= np.linspace(1, 0, n_out) ** 2
    return x


def normalize(x, peak=0.9):
    m = np.max(np.abs(x))
    return x if m == 0 else x * (peak / m)


def resonator(x, freq, bw):
    """2nd-order resonant bandpass (a single formant)."""
    r = np.exp(-np.pi * bw / SR)
    theta = 2 * np.pi * freq / SR
    a = [1, -2 * r * np.cos(theta), r * r]
    b = [1 - r]
    return signal.lfilter(b, a, x)


def lowpass(x, cutoff, order=2):
    b, a = signal.butter(order, cutoff / (SR / 2), "low")
    return signal.lfilter(b, a, x)


def highpass(x, cutoff, order=2):
    b, a = signal.butter(order, cutoff / (SR / 2), "high")
    return signal.lfilter(b, a, x)


def bandpass(x, lo, hi, order=2):
    b, a = signal.butter(order, [lo / (SR / 2), hi / (SR / 2)], "band")
    return signal.lfilter(b, a, x)


def glottal(f0_curve, brightness=1.0):
    """Band-limited sawtooth-ish voice source following a per-sample pitch curve."""
    phase = np.cumsum(f0_curve) / SR
    out = np.zeros_like(phase)
    max_h = int(5000 / max(1.0, np.max(f0_curve)))
    for h in range(1, max_h + 1):
        out += np.sin(2 * np.pi * h * phase) / (h ** (1.6 / brightness))
    return out


def reverb(x, size=0.35, mix=0.18, damp=6000):
    """Cheap, soft room: convolve with decaying filtered noise."""
    n = int(size * SR)
    ir = rng.standard_normal(n) * np.exp(-np.linspace(0, 7, n))
    ir = lowpass(ir, damp)
    ir /= np.sqrt(np.sum(ir ** 2))
    dry = np.concatenate([x, np.zeros(n)])
    wet = signal.fftconvolve(x, ir)[: len(dry)]
    wet = np.pad(wet, (0, len(dry) - len(wet)))
    return dry * (1 - mix) + wet * mix * 0.6


def pitch_shift(x, ratio):
    """Resample (speed + pitch together), exactly what Minecraft's sound pitch does."""
    n = int(len(x) / ratio)
    return np.interp(np.arange(n) * ratio, np.arange(len(x)), x)


def mix_at(out, x, start):
    """Add x into out starting at sample `start`, clipping anything past the end."""
    n = max(0, min(len(x), len(out) - start))
    out[start:start + n] += x[:n]


def write_wav(path, x):
    wavfile.write(path, SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))


def export(x, ogg_path=None, mp3_path=None):
    export_rate(x, SR, ogg_path, mp3_path)


def export_rate(x, rate, ogg_path=None, mp3_path=None):
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        tmp = f.name
    wavfile.write(tmp, rate, (np.clip(x, -1, 1) * 32767).astype(np.int16))
    try:
        if ogg_path:
            os.makedirs(os.path.dirname(ogg_path), exist_ok=True)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", tmp, "-ac", "1",
                            "-c:a", "libvorbis", "-q:a", "5", ogg_path], check=True)
        if mp3_path:
            os.makedirs(os.path.dirname(mp3_path), exist_ok=True)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", tmp, "-ac", "1",
                            "-c:a", "libmp3lame", "-q:a", "2", mp3_path], check=True)
    finally:
        os.remove(tmp)


def load_audio(path):
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        tmp = f.name
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-ac", "1", "-ar", str(SR), tmp], check=True)
    _, x = wavfile.read(tmp)
    os.remove(tmp)
    return x.astype(np.float64) / 32768


# --------------------------------------------------------------------------- voices

def voice_bountiful():
    """Cut syllable-sized grains out of the borrowed Bountiful NPC clip (alpha only, see README)."""
    src = load_audio(os.path.join(SOURCE, "bountiful_mumble.ogg"))
    hop = int(0.005 * SR)
    env = np.array([np.sqrt(np.mean(src[i:i + hop] ** 2)) for i in range(0, len(src) - hop, hop)])
    env = np.convolve(env, np.ones(4) / 4, "same")
    on = env > 0.012
    grains, start = [], None
    for i, v in enumerate(on):
        if v and start is None:
            start = i
        elif not v and start is not None:
            seg = (start * hop, i * hop)
            if seg[1] - seg[0] > 0.05 * SR:
                grains.append(seg)
            start = None
    out = []
    max_len = int(0.17 * SR)
    for a, b in grains:
        # Long phrases get split at their quietest points into syllable-sized pieces.
        while b - a > max_len * 1.4:
            window = env[(a + int(0.09 * SR)) // hop:(a + max_len) // hop]
            cut = a + int(0.09 * SR) + int(np.argmin(window)) * hop
            out.append((a, cut))
            a = cut
        out.append((a, b))
    syllables = [fade(normalize(src[a:min(b, a + max_len)], 0.85), 0.006, 0.03) for a, b in out]
    return [s for s in syllables if len(s) > 0.06 * SR][:12]


def voice_blip():
    """Retro text blips: soft square/sine chirps."""
    out = []
    for i in range(6):
        dur = 0.05
        t = t_axis(dur)
        f = 620 * (1 + 0.06 * i) * (1 - 0.15 * t / dur)
        ph = np.cumsum(f) / SR
        x = 0.6 * np.sin(2 * np.pi * ph) + 0.25 * signal.square(2 * np.pi * ph, 0.5)
        x = lowpass(x, 3500)
        out.append(fade(normalize(x, 0.35), 0.002, 0.02))
    return out


VOICES = {
    "bountiful": voice_bountiful,
    "blip": voice_blip,
}

# How each style is played in game (mirrored in DialogueVoice.java): base pitch and min gap between syllables.
VOICE_PLAYBACK = {
    "bountiful": (1.0, 0.11),
    "blip": (1.0, 0.055),
}


# --------------------------------------------------------------------------- UI sounds

def pluck(freq, dur, partials=((1, 1.0, 1.0), (2, 0.25, 1.8), (5.4, 0.12, 4.0)), decay=9.0):
    """Kalimba / marimba-like pluck: a few partials, the higher ones dying faster."""
    t = t_axis(dur)
    x = np.zeros_like(t)
    for ratio, amp, dk in partials:
        x += amp * np.sin(2 * np.pi * freq * ratio * t) * np.exp(-decay * dk * t)
    return fade(x, 0.002, 0.03)


def ui_hover():
    # Tiny wooden 'tik': high marimba-ish partials, very short.
    x = pluck(1760, 0.07, partials=((1, 1.0, 1.0), (2.76, 0.35, 2.5), (5.4, 0.15, 5)), decay=45)
    click = highpass(rng.standard_normal(int(0.004 * SR)), 4000) * 0.15
    x[: len(click)] += click
    return normalize(reverb(x, 0.25, 0.12), 0.35)


def ui_select():
    # Two quick rising notes (a fifth), bright and clean: the 'confirm' feel.
    a = pluck(1046.5, 0.35, decay=8)   # C6
    b = pluck(1568.0, 0.45, decay=7)   # G6
    out = np.zeros(int(0.5 * SR))
    mix_at(out, a * 0.8, 0)
    off = int(0.055 * SR)
    mix_at(out, b, off)
    return normalize(reverb(out, 0.4, 0.2), 0.6)


def ui_open():
    # Soft airy swell + gentle two-note chime.
    dur = 0.18
    t = t_axis(dur)
    noise = rng.standard_normal(len(t))
    sweep = np.zeros_like(noise)
    # rising band of air
    for i, c in enumerate(np.linspace(800, 3000, 6)):
        seg = slice(i * len(t) // 6, (i + 1) * len(t) // 6)
        sweep[seg] = bandpass(noise, c * 0.7, c * 1.3)[seg]
    sweep *= np.sin(np.pi * t / dur) ** 2 * 0.25
    chime = pluck(1318.5, 0.5, decay=6) * 0.5 + pluck(1975.5, 0.5, decay=7) * 0.3  # E6 + B6
    out = np.zeros(int(0.6 * SR))
    mix_at(out, sweep, 0)
    off = int(0.07 * SR)
    mix_at(out, chime, off)
    return normalize(reverb(out, 0.45, 0.22), 0.5)


def ui_close():
    a = pluck(1318.5, 0.3, decay=9)    # E6
    b = pluck(880.0, 0.4, decay=8)     # A5
    out = np.zeros(int(0.45 * SR))
    mix_at(out, a * 0.7, 0)
    off = int(0.06 * SR)
    mix_at(out, b * 0.8, off)
    return normalize(reverb(out, 0.4, 0.2), 0.4)


def ui_advance():
    # Barely-there 'ting' when a line finishes.
    x = pluck(2093.0, 0.3, partials=((1, 1.0, 1.0), (3.0, 0.1, 3)), decay=10)
    return normalize(reverb(x, 0.35, 0.2), 0.25)


UI = {"open": ui_open, "hover": ui_hover, "select": ui_select, "close": ui_close, "advance": ui_advance}


# --------------------------------------------------------------------------- sounds.json

def write_sounds_json():
    """One sound event per folder of variants: ui/<name>.ogg, voice/<style>/*.ogg, voice/natural/<slot>/<set>/*.ogg."""
    events = {}

    def add(event, folder, subtitle=None):
        files = sorted((f[:-4] for f in os.listdir(os.path.join(SOUNDS, folder)) if f.endswith(".ogg")), key=lambda n: (len(n), n))
        if files:
            events[event] = {"sounds": [f"villagechatter:{folder}/{f}" for f in files]}
            if subtitle:
                events[event]["subtitle"] = subtitle

    for f in sorted(os.listdir(os.path.join(SOUNDS, "ui"))):
        if f.endswith(".ogg"):
            events[f"ui.{f[:-4]}"] = {"sounds": [f"villagechatter:ui/{f[:-4]}"]}
    # Every folder of .ogg files under voice/ is one event: voice/natural/0/greet -> voice.natural.0.greet
    voice_root = os.path.join(SOUNDS, "voice")
    for dirpath, _, files in sorted(os.walk(voice_root)):
        if any(f.endswith(".ogg") for f in files):
            rel = os.path.relpath(dirpath, SOUNDS).replace(os.sep, "/")
            add(rel.replace("/", "."), rel, "subtitles.villagechatter.voice")
    with open(os.path.join(SOUNDS, "..", "sounds.json"), "w", encoding="utf-8") as f:
        json.dump(events, f, indent=2)
    print(f"wrote sounds.json ({len(events)} events)")


# --------------------------------------------------------------------------- previews

DEMO_TEXT = "Hello there, traveler! Mind the chickens. Hrmm... have you seen my hat?"


def speak(syllables, text, villager_pitch, base_pitch, min_gap, cps=30):
    """Mirror of the in-game algorithm: reveal text at cps, babble on letters, pause on punctuation."""
    pauses = {",": 0.12, ".": 0.25, "!": 0.25, "?": 0.25}
    events, t, last = [], 0.0, -1.0
    for i, ch in enumerate(text):
        if ch.isalpha() and t - last >= min_gap:
            rising = "?" in text[i:i + 12] and "." not in text[i:i + 12]  # questions lift at the end
            p = base_pitch * villager_pitch * rng.uniform(0.95, 1.05) * (1.08 if rising else 1.0)
            events.append((t, syllables[rng.integers(len(syllables))], p))
            last = t
        t += 1 / cps + pauses.get(ch, 0)
    out = np.zeros(int((t + 0.5) * SR))
    for start, syl, p in events:
        s = pitch_shift(syl, p) * 0.7
        i = int(start * SR)
        mix_at(out, s, int(start * SR))
    return out


def silence(sec):
    return np.zeros(int(sec * SR))


def main():
    os.makedirs(PREVIEWS, exist_ok=True)
    ui = {name: fn() for name, fn in UI.items()}
    for name, x in ui.items():
        export(x, ogg_path=os.path.join(SOUNDS, "ui", f"{name}.ogg"))

    for style, fn in VOICES.items():
        syllables = fn()
        for i, s in enumerate(syllables):
            export(s, ogg_path=os.path.join(SOUNDS, "voice", style, f"{i}.ogg"))
        base, gap = VOICE_PLAYBACK[style]
        # Same line spoken by a low, mid and high voiced villager.
        demo = np.concatenate([np.concatenate([speak(syllables, DEMO_TEXT, vp, base, gap), silence(0.4)])
                               for vp in (0.82, 1.0, 1.22)])
        export(np.clip(demo, -1, 1), mp3_path=os.path.join(PREVIEWS, f"voice_{style}.mp3"))
        print(f"voice {style}: {len(syllables)} syllables")

    # UI reel: open -> villager talks -> advance -> hover, hover, hover -> select ... close
    syl = VOICES["blip"]()
    reel = [ui["open"], silence(0.15), speak(syl, "Welcome, welcome. Mind the chickens.", 1.0, 1.0, 0.055),
            ui["advance"], silence(0.5), ui["hover"], silence(0.35), ui["hover"], silence(0.35), ui["hover"],
            silence(0.5), ui["select"], silence(0.8), ui["close"], silence(0.3)]
    export(normalize(np.concatenate(reel), 0.8), mp3_path=os.path.join(PREVIEWS, "ui_reel.mp3"))
    for name, x in ui.items():
        export(x, mp3_path=os.path.join(PREVIEWS, f"ui_{name}.mp3"))
    print("ui sounds:", ", ".join(ui))

    write_sounds_json()


if __name__ == "__main__":
    main()
