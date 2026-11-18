# Motor sound, recordings and how they become engines

The recorded engines in the Motor sound generator are rebuilt from real
recordings. Nothing is synthesised for them: the app plays grains cut from
the recording, laid down one engine cycle apart, closer together as the
wheel speeds up. The pipes keep their real pitch and the firing rate follows
the wheel, which is what a real engine does.

All recordings are CC0 / public domain. No attribution is required beyond
the credits dialog inside the app, which names them anyway.

## The pipeline

1. Recordings live in `tools/engine-sounds/src/` (never in the APK).
2. `tools/engine-sounds/grains.json` says which recording(s) make which
   engine, where the start and the pops are, and what to leave out.
3. `python tools/engine-sounds/grains.py` tracks each recording's cycle
   rate (YIN period tracker with a continuity path), cuts two-cycle Hann
   grains centred on the combustion pulse (so grains from anywhere in the
   bank line up pulse on pulse when the app crossfades or jumps), groups
   them into bands a quarter octave apart, sets each band's level (idle at
   -16 dB rms, 3 dB louder per octave of revs), and writes `app/src/main/res/raw/grain_<key>.bin`
   (about 0.2 to 1.2 MB each, 16-bit at 22050 Hz). `--report` prints what
   the tracker heard, two seconds at a time. Before the cut, rumble under
   `rumbleHz` (default 70) is removed and the low end's rhythm is copied into
   the band a phone speaker plays (`speakerLift`, what a bass enhancer does),
   so headphones get the real low end and the speaker still gets the chop.
   `average: 3` averages each grain with its neighbours in the recording:
   the engine repeats cycle to cycle, wind and hiss do not, so noise drops
   4.8 dB and the engine is untouched (used for recordings made in the open).
4. `GrainBank` reads a bank on the phone (and in unit tests, straight from
   `res/raw`), `EngineSynth.renderGrains` plays it. `EngineProfile.grainAsset`
   names the bank an engine uses. With the throttle shut at speed the grains
   play quieter and duller with a faint gear whine (the recording was made
   under power); a pop is the recording's own backfire, a bang through a
   pipe the size of the engine's, or, for the steam engine, a toot of the
   synthesised whistle.

`GrainBankTest` checks every bank the catalog names ships and reads, and
that the engine fires at the recording's own rate at idle and twice that
at twice the revs. `EngineSynthSpectrumTest` holds the recorded engines to
the loudness, speaker-band and screech bars (a looser idle sub-bass bar, a
real V8 idles at 46 Hz) and checks the overrun and the pops add no hiss.

## Recordings (under `tools/engine-sounds/src/`)

| File | Engine(s) | Source | License |
|---|---|---|---|
| `engine_v8_cobra.ogg` | V8 muscle | BigSoundBank, AC Cobra Engine (s0534) https://bigsoundbank.com/ac-cobra-engine-s0534.html | CC0 |
| `engine_vtwin_ducati.ogg` | V-twin | BigSoundBank, Starting a motorcycle, Ducati (s0884) | CC0 |
| `src_bsb_0187.ogg` | Inline-4 | BigSoundBank, Citroen Saxo engine (s0187) | CC0 |
| `src_ia_vanquish.ogg` | V12 (Italian) | Internet Archive, Aston Martin Vanquish V12 engine | Public domain |
| `src_bsb_0712.ogg` | Quad ATV (hidden; the listed Quad bike is synthesised) | BigSoundBank, Quad (s0712) | CC0 |
| `src_bsb_1146.ogg`, `src_bsb_1147.ogg` | Diesel truck | BigSoundBank, Iveco Daily 3.0 small and big accelerations (s1146, s1147) | CC0 |
| `engine_tractor.ogg` | Tractor (hidden; the listed Tractor is synthesised) | BigSoundBank, Tractor engine | CC0 |
| `engine_steam_loco.ogg` | Steam locomotive | BigSoundBank, Steam locomotive | CC0 |
| `engine_damaged_muffler.ogg` | Broken exhaust | BigSoundBank, Car with a damaged muffler | CC0 |
| `engine_motorcycle.ogg` | Small dirt bike | BigSoundBank, Motorcycle (s0281) | CC0 |
| `src_bsb_0763.ogg` | Lawn mower (hidden) | BigSoundBank, Mower (s0763) | CC0 |
| `engine_big_diesel.ogg` | Big diesel (hidden) | BigSoundBank, Big diesel engine, Kia Carnival 2.9 (s0967) | CC0 |

Kept for a later cut (not used by any bank yet): `engine_aston_martin.ogg`,
`engine_car_cruise.ogg`, `engine_citycar_saxo.ogg`, `engine_diesel_iveco.ogg`,
`engine_helicopter.ogg`, `engine_lawnmower.ogg`, `engine_quad_atv.ogg`,
`src_bsb_0281.ogg`, `src_bsb_0291.ogg`, `src_bsb_0872.ogg`, `src_bsb_0966.ogg`
(Peugeot 107, s0966), `src_fs_119774_moto_start.ogg`,
`src_fs_256808_tractor_clean.ogg`, `src_fs_559340_heli_long.ogg`,
`src_fs_784612_bulleid.ogg` (Freesound, CC0), `src_ia_v12vantage.ogg`
(Internet Archive), `sfx_pop_crack.ogg` and `sfx_pop_snap.ogg` (BigSoundBank
s1137 and s0550, CC-BY 3.0, credited in the About dialog).

## Wanted

Fixed-position recordings, idle plus a rev or a run at speed, at least
ten seconds, CC0: a two-stroke (moped, dirt bike, chainsaw), a flat-six, an
inline-six, a jet or turbine spooling up, a V10 or V12 at high revs. The
synthesised engines stand in for these until a recording turns up.
BigSoundBank has none of them (checked November 2026); Freesound has
candidates but needs a login to download.
