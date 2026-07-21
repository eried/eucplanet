# Custom AVAS / engine sound — design

- Date: 2026-07-21
- Branch: `feature/custom-avas-sound` (based on `next-version`)
- Status: approved, pending implementation plan

## Goal

Let a rider use their own external audio files for the virtual engine
(AVAS) sound, the same way the built-in "Sampled ..." engines work, instead
of only the baked-in presets. One new "Custom" engine in the existing engine
picker, with a small editor for choosing files.

## Background

The engine-sound feature lives in `app/src/main/java/com/eried/eucplanet/audio/`.
A sound is an `EngineProfile` selected by its `key`, stored in
`AppSettings.engineType`. Three render paths, chosen by which profile fields are
set (`EngineSoundEngine.start()`):

- procedural `EngineSynth` (`kind = SYNTH/ICE`, no samples),
- single sampled asset, looped + pitch-shifted (`SampledEnginePlayer`, uses
  `sampleAssetBase`),
- multi-section composition (`CompositionEnginePlayer`, uses `sampleSections`)
  crossfading `idle_loop` / `rev_loop` with one-shot `startup` / `decel` /
  `shutdown`. All 14 built-in "Sampled ..." presets use this path.

Both sampled players resolve audio by **raw-resource name** via
`getIdentifier(name, "raw", ...)`. `CompositionEnginePlayer.SectionPlayer` already
builds a `MediaItem.fromUri(...)`, so it can play any `content://` / `file://`
URI with a one-line datasource change. That is the integration point.

## Requirements

1. A "Custom" entry in the engine picker (`EngineTypePicker`, `SettingsScreen.kt`).
2. Selecting it reveals a Custom editor with:
   - one **main sound file** field (the idle / default, used for everything),
   - a **"Modulate pitch with speed"** checkbox (default on),
   - when the checkbox is OFF, per-section slots become enabled: `rev`,
     `startup`, `decel`, `shutdown`, `pops` (all optional).
3. Files are chosen with the system file picker (SAF), **referenced** by URI
   (not copied), with a persistable permission grant.
4. Each file field shows a live status: OK / not found / failed to load.
5. Preview / test plays the real configured custom sound.
6. Empty state: if no main file is set, the engine stays silent and the main
   field shows a "Required" status. The selection stays on Custom; we do not
   auto-revert and do not fall back to a preset.

## UI design

Rendered inside `EngineSoundSection` (`SettingsScreen.kt`), below the engine
picker, only when `engineType == "CUSTOM"`.

- **Main sound file** row: label, "Pick file" button (SAF `OpenDocument`,
  `audio/*`, pattern from `RecordingScreen.kt:151`), chosen filename, a clear
  (X) button, and a status chip.
- **☑ Modulate pitch with speed** checkbox directly under the main file, with a
  one-line hint: "Loops one file and raises its pitch as you speed up."
  - **Checked (default)** -> single-file mode. Section slots are hidden. The
    main file loops and pitch-shifts 0.6x..1.8x with speed.
  - **Unchecked** -> multi-section mode. The section slots below become enabled.
    Main file = `idle_loop`; sections crossfade by speed; no pitch shift.
- **Section slots** (shown/enabled only when the checkbox is off): `rev`,
  `startup`, `decel`, `shutdown`, `pops`. Each row mirrors the main file row
  (label, pick, filename, clear, status). All optional; a missing slot falls
  back to the main/idle sound.
- **Preview** button (existing `previewEngine` path) plays the real config.
- Status chip states: `OK` (green), `Required` (amber, main file only when
  empty), `File not found` (red, URI no longer resolvable), `Can't play`
  (red, decoder/prepare failure).

Colors come from `MaterialTheme.appColors.*` (no hardcoded values); strings are
localized; clearing a slot is the reset affordance; the editor sets
`dismissOnClickOutside = false` if hosted in a dialog.

## Data model

Two new fields on `AppSettings` (Motor-Sound block), following the existing
per-feature engine fields (not AdvancedSettings):

- `engineCustomModulatePitch: Boolean = true` — the checkbox.
- `engineCustomSounds: String = ""` — JSON map of slot -> URI string, e.g.
  `{"idle_loop":"content://...","rev_loop":"content://...","decel":"content://..."}`.
  Empty string = nothing chosen. Slot keys reuse the section names already used
  by `CompositionEnginePlayer` (`idle_loop`, `rev_loop`, `startup`, `decel`,
  `shutdown`) plus `pops`.

A single reserved profile key `"CUSTOM"` is added; `engineType = "CUSTOM"`
selects the custom engine. Keeping the map in one JSON string keeps `AppSettings`
within the 255-arg limit (+2 fields total).

Persistence: both fields added to `SettingsJson.kt` serialize (~line 218) and
deserialize (~line 482), plus setters in `SettingsViewModel`. Persistable URI
permission (`takePersistableUriPermission`) is taken on pick so references
survive reboots.

## Player integration

- `EngineProfile` / `SampleSection`: add an optional `uri: String? = null` to
  `SampleSection`. When set, the section is loaded from the URI instead of a raw
  resource; the whole file is the clip (no start/end windowing, no waveform
  editor). Add a `pitchModulated: Boolean = false` flag to `EngineProfile`.
- Build a synthetic `CUSTOM` `EngineProfile` at runtime from the stored fields:
  - `sampleSections` populated from `engineCustomSounds` (URI-backed sections),
  - `pitchModulated = engineCustomModulatePitch`.
  - In single-file mode only `idle_loop` is present; multi-section mode adds the
    chosen slots.
- `EngineSoundEngine`: resolve `CUSTOM` to the synthetic profile (a
  `buildCustomProfile(settings)` helper) rather than `EngineProfile.byKey`. The
  existing `sampleSections != null` branch already routes to
  `CompositionEnginePlayer`, so no new player branch is required.
- `CompositionEnginePlayer.SectionPlayer.prepare()`: if `section.uri != null`,
  build `MediaItem.fromUri(Uri.parse(section.uri))` with a
  `DefaultDataSource.Factory(context)` (plays `content://` / `file://`); skip the
  raw-resource lookup and `ClippingMediaSource` (use the whole file). Report
  prepare success/failure so the UI can show per-slot status.
- `CompositionEnginePlayer.update()`: when `profile.pitchModulated` is true (and
  there is no `rev_loop`), set the idle section speed from `rpmNorm`
  (0.6x..1.8x), matching `SampledEnginePlayer`. Built-in profiles keep
  `pitchModulated = false`, so their behaviour is unchanged.

Capability flags on the custom profile match other sampled engines:
`supportsMuffler = false`, `supportsBrakeWhine = false`; `supportsPops` true when
a `pops` file is set.

## Edge cases

- No main file: silent, "Required" status, selection stays Custom.
- URI no longer resolvable / permission revoked: per-slot "File not found";
  engine plays whatever remaining slots resolve (idle missing -> silent).
- Decoder failure on an odd format: "Can't play" status; that slot is skipped.
- Backup/restore or sync to another device: URIs may not resolve on a different
  device; status surfaces this and the rider re-picks. (Documented, acceptable.)

## Localization

New strings in `strings.xml` (all locales): `engine_source_custom`,
`engine_custom_modulate_pitch`, `engine_custom_modulate_pitch_hint`,
`engine_custom_pick_file`, `engine_custom_slot_main`, `engine_custom_slot_rev`,
`engine_custom_slot_startup`, `engine_custom_slot_decel`,
`engine_custom_slot_shutdown`, `engine_custom_slot_pops`,
`engine_custom_status_ok`, `engine_custom_status_required`,
`engine_custom_status_missing`, `engine_custom_status_error`.

## Testing

- JSON encode/decode of `engineCustomSounds` (round-trip, empty, malformed).
- `buildCustomProfile`: single-file mode yields an `idle_loop`-only profile with
  `pitchModulated = true`; multi-section yields the chosen sections with
  `pitchModulated = false`.
- URI-backed `SampleSection` selects the URI datasource path (unit-level guard
  on the `uri != null` branch decision).
- `SettingsJson` round-trip includes the two new fields.

## Out of scope (future)

- Per-slot start/end trimming / waveform editor (whole file only for v1).
- Copying files into app storage (referenced by URI for v1, per rider choice).
- Custom pop variants list / brake-whine sidecar for custom engines.
- Multiple saved custom profiles (one custom slot for v1).

## Note: unrelated fix riding on this branch

While setting up this branch, an InMotion V1 connection bug was found and fixed
(the tester's V10F could not connect). Root cause: `BleProfile` modeled a single
service, but InMotion V1 splits notify (`0xFFE4` under `0xFFE0`) and write
(`0xFFE9` under `0xFFE5`) across two services, so the write characteristic
lookup returned null and every V1 wheel's connect was torn down. Fixed by adding
`BleProfile.writeServiceUuid` and resolving the write characteristic from it in
`BleConnectionManager`. Guarded by `BleProfileTest`. This is independent of the
custom-sound feature and could be split into its own commit/PR.
