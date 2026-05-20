# size-optimization

Shrinks the app from 86 MB to roughly 18 MB with no behaviour change. Two
independent levers: R8 code shrinking and engine-sound audio re-encoding.

## What's new

**R8 code shrinking**
- Release builds now run R8 with `isMinifyEnabled` and `isShrinkResources`.
  Dead-code elimination strips the ~4,900 unused `material-icons-extended`
  icons (only 67 are used) plus every unreachable path in Compose, Hilt,
  Room, ExoPlayer and play-services. DEX drops from 53 MB to 5.6 MB.
- Obfuscation stays OFF (`-dontobfuscate`). Class and method names are
  kept, so Service Mode diagnostic dumps and crash traces stay readable
  without a mapping file.
- Keep rules added for the Flic2 SDK and for enum `valueOf()` lookups.

**Engine-sound audio re-encode**
- The engine clips were mono drones encoded at 210 to 290 kbps. They are
  re-encoded to roughly 96 kbps mono Vorbis; the two `.mp3` source clips
  are converted to `.ogg`. Audio drops from 30 MB to 10.7 MB.
- Clip durations are preserved to the millisecond, so every engine-sound
  composition still lines up exactly. No audible change is expected on a
  phone speaker, but verify by ear.

Release APK: 86.4 MB to 17.6 MB.

## Who should test this

- Everyone: confirm the app installs, opens, connects to a wheel and the
  dashboard updates normally.
- Motor-sound users: cycle through the engine presets and the multi-section
  compositions; confirm every preset still plays cleanly with no gaps,
  clicks or wrong-pitch sections.
- If a crash happens, confirm the Service Mode diagnostic dump still shows
  readable class names (that is the point of keeping obfuscation off).

## Known gaps

- Four `src_fs_*` source clips were already at a low bitrate and were left
  untouched. Further size could be recovered by trimming the unused head
  and tail of the long source clips, but that needs offset recomputation
  and was left out of this branch.
- The Wear OS module is unchanged; only the phone app is optimized.

## How to install

Release-signed build. Install the APK directly; it replaces any earlier
EUC Planet install. Reinstalling from Play later overwrites this build.

## Feedback

Open an issue at https://github.com/eried/eucplanet/issues tagged
`branch:size-optimization`.
