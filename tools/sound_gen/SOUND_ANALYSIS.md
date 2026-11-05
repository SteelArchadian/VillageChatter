# Creature sound analysis and approach review

Goal: give villagers, golems and dogs a wider "vocal range" that still sounds like Minecraft. It must never sound
like a person doing an impression, or like a cheap warp of an existing sound.

Measurements are in `SOUND_CATALOG.md`, and spectrograms are in `previews/catalog/`.

## 1. What the creatures actually sound like

### Villagers
- **Material:** 12 usable voice clips: idle ×3, yes ×3, no ×3, haggle ×3. Hurt and death sounds are excluded.
- **Length and pitch:** short (0.4–1.0 s) and low, around 95–110 Hz.
- **Texture:** only 30–65% voiced. A lot of the sound is breathy, nasal noise, with brightness around 2.3 kHz.
- **Character:** closed-mouth "hmm / hrr" hums. They're nasal, start with a puff of breath, and contain no vowels or
  words. That's why they never read as speech.
- **Intonation does the acting:**
  - *yes*: two parts, rising ("hm-HM").
  - *no*: falling, with a nasal "mm-mm".
  - *haggle*: a step up or down, like "considering".
  - *idle*: a single relaxed hum.
- **Variation in vanilla:** each play varies pitch only slightly, roughly ±10–20%. Babies play the same clips higher.
  There's no time-stretching or reshaping.
- **Origin:** I found no reliable source for how they were made. The common assumption is a human humming through
  the nose, then processed. The only page naming a performer looks AI-generated and contradicts itself, so I'm not
  relying on it. Minecraft's original sound designer, C418, built effects from everyday recordings. For example, the
  spider's screech was a manipulated water hose.

### Iron golem
- **No voice in vanilla.** Its sounds are:
  - footsteps: a low thud with a metal ring
  - damage: cracking and crunching metal, almost pure noise up around 6 kHz
  - repair: a clink
  - hit: a short metallic grunt
- **Identity:** a heavy, slow, hollow iron body. Its resonances, measured from its own sounds, sit at
  104, 176, 217, 275, 353, 410, 524, 866, 923 and 1009 Hz.
- **Vibe:** gentle giant made of iron. Everything it "says" should come from the iron, not from a throat.
- **Same class of creature:** Minecraft's newer Copper Golem talks in metallic clicks and whirs, not voice. In film,
  the Iron Giant's non-speech sounds are metal groans and servo whines. So a golem "grumble" should be a metal stress
  groan (an old ship hull, a heavy door hinge, a bending iron plate) under a soft body thud.

### Dogs (wolf)
- **Material:**
  - barks ×3: 0.3 s, 260–400 Hz
  - growls ×3: long, very low, 56–71 Hz, mostly noise
  - whine: 0.74 s, tonal, around 720 Hz
  - panting: 0.66 s
  - howls ×2: 3.5 s
- **Already covered:** barks, growls, whine, pant and howl are a good base.
- **Missing:** a soft "boof", a yip, whimper variants, a huff or sigh, and the chatty "awoo-woo" grumble.

## 2. Review of what I tried

| Attempt | Verdict | Why |
|---|---|---|
| Bark AI voices | ❌ wrong tool | Human speech models produce human speech, the "Kevin doing a villager" problem. |
| Villager A: reshape and stretch | ❌ cheap warp | Large pitch and time changes break the performance's natural timing, so it sounds processed. Only small changes (about ±10% pitch, ±15% length) stay believable. |
| Villager B: vocoder | ❌ wrong tool | Vocoders throw away the breath and detail that make the hum alive. The result is muffled and uncanny ("backrooms villager"). |
| Golem A: synthetic pulse through iron resonance | ❌ cheap synthesis | A buzzy synthetic pulse is exactly the retro N64 sound. Nothing in it is organic. |
| Golem B: villager dropped an octave | ⚠️ right direction, wrong source | It treats the golem as a villager, but the golem's identity is metal, not voice. |
| Dogs: combos and small pitch changes of real dog sounds | ❓ awaiting your feedback | Mostly stays within natural range, so it's the most likely to hold up. |

**What professionals say** (see sources): believable creatures come from **organic recorded material and
performance**, layered in bands (body, identity, texture) that move together. Synthesis works as *support*, not as
the main source. Every failed attempt above went the other way: synthesis or warping as the main source.

## 3. Approaches that can actually be good

1. **Vanilla sounds, smartly used** (guaranteed quality, limited range). Pick the right clip for the moment, keep
   pitch within vanilla's own range, and combine clips (e.g. *haggle → yes* for "hm? …hm-hm!"). It's believable by
   definition and safe to publish, because the game plays its own files.
2. **New organic recordings in the vanilla style** (best quality, needs source material). How pros do it:
   - **Villagers:** closed-mouth nasal hums recorded fresh, then matched to the vanilla clips' tone and pitch.
   - **Golems:** real metal recordings, such as heavy door hinges, bending sheet metal, big pipes, a creaking hull.
   - **Dogs:** real dog recordings.
   - **Sources:** free CC0 recordings from Freesound (public domain, fine to ship), or recordings you make yourself.
3. **Physical modelling for golem metal** (worth trying). A metal groan is a physical process (friction
   stick-slipping across a resonant body), not a voice. Simulating that process, tuned to the golem's measured
   resonances, can produce believable groans and creaks. This is a different class of technique from the buzzy
   pulse that failed.
4. **Small-range dog additions:** combos and gentle variants of real dog sounds (approach 1), with CC0 dog
   recordings for anything vanilla really lacks.

## Sources
- [Screen Rant: Minecraft composer shares origins of the game's sound effects](https://screenrant.com/minecraft-composer-sound-effects-origins/)
- [C418 (Wikipedia)](https://en.wikipedia.org/wiki/C418)
- [Minecraft Forum: Villager sounds, where did they come from?](https://www.minecraftforum.net/forums/minecraft-java-edition/discussion/186661-villager-sounds-where-did-they-come-from)
- [AnonJr/Minecraft-Villager-Voices (fan recreation method)](https://github.com/AnonJr/Minecraft-Villager-Voices)
- [GDC Vault: Creature Vocalizations, The Art of…](https://gdcvault.com/play/1034233/Creature-Vocalizations-The-Art-of)
- [The Monster Factory: Extreme Voices in Video Games](https://www.infernomusicconference.com/monster-factory)
- [Violet Recording: How to design a creature sound](https://violetrecording.com/how-to-design-a-creature-sound/)
- [designingsound.org: layering animal vocals](https://designingsound.org/2010/09/30/dogswar/)
- [Sound Ideas: Using layers to make one sound](https://sound-ideas.com/blog/Using-Layers-to-Make-One-Sound)
- [IRPR glossary: creature vocalization](https://sounddesign.irpr.agency/glossary/creature-vocalization/)
- [Gearspace: creating creature sounds](https://gearspace.com/threads/creating-creature-sounds.274941/)
- [Ircam: physical model of stick-slip in sound design](https://brahms.ircam.fr/en/media/x7aa847)
- [CILAMCE 2019: creep groan stick-slip model](https://publicacoes.softaliza.com.br/cilamce2019/article/download/7043/6064)
