# Motor Sound — SFX Sources

One-shot sound effects mixed via `SoundPool` on top of the looped sampled
engines. All clips are CC0 / public domain unless stated otherwise.
No attribution requirements beyond the credits dialog inside the app.

## Currently bundled (under `app/src/main/res/raw/`)

| File | Source | Page | License |
|---|---|---|---|
| `sfx_pop_crack.ogg` | BigSoundBank — Firecracker with wick #1 (s1137) | https://bigsoundbank.com/firecracker-with-wick-1-s1137.html | CC-BY 3.0 — credited in About dialog |
| `sfx_pop_snap.ogg` | BigSoundBank — Exploding bang-snaps (s0550) | https://bigsoundbank.com/bang-snaps-s0550.html | CC-BY 3.0 — credited in About dialog |

Both clips are pre-trimmed to ~2 s and load instantly into the SoundPool.

## Pending Freesound CC0 grabs (manual login required)

Freesound requires an account to download even CC0 clips. Grab when you next
sign in and drop them under `app/src/main/res/raw/` with the names below.

### Decel pops / backfires

| Target filename | Source | Trim |
|---|---|---|
| `sfx_pop_backfire.ogg` | CeebFrack — BACKFIRE.ogg (Freesound #105351) | none — already 2.04 s designed pop |
| `sfx_pop_smallcar.ogg` | nsstudios — backfiring vehicle (Freesound #351738) | slice the final pop, ~0.8 s |
| `sfx_pop_twostroke.ogg` | lovretta — Gilera Runner backfire cold idle (Freesound #140384) | slice ~0.5 s crackly pop from the FLAC |

### Engine-brake whine loops (all need 2-5 s seamless slices in Audacity)

| Target filename | Source | Use on |
|---|---|---|
| `sfx_brake_diesel.ogg` | chungus43A — EMD 567 diesel decel (Freesound #786067) | BIG_DIESEL, TRACTOR |
| `sfx_brake_turbo_v12.ogg` | EwanPenman11 — Turbo spool and BOV (Freesound #659544) | ASTON_MARTIN |
| `sfx_brake_overrun.ogg` | ulose2piranha — WRX exhaust overrun (Freesound #273334) | BROKEN_EXHAUST, QUAD_ATV |
| `sfx_brake_turbo_whine.ogg` | editboy23 — Supra dyno turbo whine (Freesound #496171) | CAR_CRUISE |
| `sfx_helo_rotor.ogg` | craigsmith — helicopter slow idle (Freesound #438604) | HELICOPTER (pitch-shifted decel) |
| `sfx_steam_release.ogg` | kyles — industrial steam release (Freesound #455783) | STEAM_LOCO |

### Trim recipe (Audacity)

1. Find a 2-5 s span where the sound is steady (no obvious attack/release).
2. Zero-crossing trim at both ends (Z key in Audacity).
3. Add a 100-200 ms crossfade on the wrap so the loop seam isn't audible.
4. Export as Ogg Vorbis, quality 4 (~96 kbps mono is plenty for SFX).

## Per-engine truth table

The `EngineProfile` flags drive both the SoundPool sidecar (whether to load the
asset) and the Settings UI (whether to show the row). Update both `supportsX`
booleans and the asset basename when a new clip lands.

| Engine | supportsPops | popSampleAsset | supportsBrakeWhine | brakeWhineSampleAsset |
|---|:---:|---|:---:|---|
| ASTON_MARTIN | ✓ | sfx_pop_crack | ✓ | sfx_brake_turbo_v12 |
| BROKEN_EXHAUST | ✓ | sfx_pop_crack | ✓ | sfx_brake_overrun |
| CAR_CRUISE | ✓ light | sfx_pop_snap | ✓ | sfx_brake_turbo_whine |
| QUAD_ATV | ✓ | sfx_pop_crack | ✓ | sfx_brake_overrun |
| LAWNMOWER | ✗ | — | ✗ | — |
| BIG_DIESEL | ✗ (no spark) | — | ✓ | sfx_brake_diesel |
| TRACTOR | ✗ (no spark) | — | ✓ | sfx_brake_diesel |
| HELICOPTER | ✗ (turbine) | — | ✓ | sfx_helo_rotor |
| STEAM_LOCO | ✗ (external combustion) | — | ✓ | sfx_steam_release |
| SAMPLED_V8_COBRA | ✓ | sfx_pop_crack | ✓ | sfx_brake_turbo_v12 |
| SAMPLED_VTWIN_DUCATI | ✓ | sfx_pop_crack | ✓ | sfx_brake_overrun |
| SAMPLED_DIESEL_IVECO | ✗ | — | ✓ | sfx_brake_diesel |
| SAMPLED_MOTORCYCLE | ✓ | sfx_pop_crack | ✓ | sfx_brake_overrun |
| SAMPLED_CITY_CAR | ✓ light | sfx_pop_snap | ✓ | sfx_brake_turbo_whine |
