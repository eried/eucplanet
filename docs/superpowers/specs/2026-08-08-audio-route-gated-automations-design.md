# Audio-route-gated automations - design

**Date:** 2026-08-08
**Branch:** next-version (feature work branches off next-experimental per repo rule 15)

## Goal

Let the rider restrict two speed-driven audio automations so they act only when
audio is playing to an external output (headphones, Bluetooth, wired, or USB) and
not the phone's built-in speaker:

1. **Auto play/pause music** (`MediaControlSettings`, evaluated in
   `AutomationManager.evaluateMediaControl`).
2. **Periodic status announcements** (the voice loop in
   `WheelService.startVoiceLoop`, gated today by `voiceAnnounceWhen`).

Both gates are **off by default**, so nothing changes for existing users.

## Decisions (locked with the rider)

- **Route filter = any external output, not the phone speaker.** Bluetooth
  (A2DP / BLE), wired headphones/headset, and USB all qualify. This is exactly
  the set `EngineSoundEngine.isHeadphonesActive()` already tests.
- **Per-feature toggles, local to each feature** (not one shared global). The
  rider can gate music but not announcements, or vice versa. This matches repo
  rule 1 (per-feature values stay local; only true global tunables go in
  Advanced).
- **Label:** "Only on headphones or Bluetooth", with a caption clarifying it
  skips the phone speaker. No em-dashes (repo rule 2).

## Architecture

### Unit 1 - shared route helper

A small stateless helper is the single source of truth for "is an external audio
output currently active".

- **New file:** `app/src/main/java/com/eried/eucplanet/audio/AudioOutput.kt`
  ```kotlin
  object AudioOutput {
      /**
       * True when audio is currently routed to an external output device
       * (Bluetooth A2DP/BLE, wired headphones/headset, or USB) rather than the
       * phone's built-in speaker. Point-in-time poll of the current output
       * devices, matching EngineSoundEngine's original headphone check.
       */
      fun isExternalActive(audioManager: AudioManager): Boolean {
          val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
              ?: return false
          return devices.any {
              it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
              it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
              it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
              it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
          }
      }
  }
  ```
- **Refactor:** `EngineSoundEngine.isHeadphonesActive()` (around line 606) becomes
  a one-line delegate to `AudioOutput.isExternalActive(audioManager)`, so the
  device-type list lives in exactly one place. Its existing behavior is unchanged.

Interface: consumes an `AudioManager`; returns `Boolean`. No state, no listener
(a point-in-time poll is enough - both callers already run on a periodic tick).

### Unit 2 - media-control gate

- **Setting:** add to `MediaControlSettings` (AppSettings.kt ~971):
  ```kotlin
  val requireExternalOutput: Boolean = false,
  ```
  Nested in the existing group, so `AppSettings.copy()` stays under the 255-arg
  dex limit (repo rule 8). Persisted in `SettingsJson` inside the existing
  `mediaControl` object (write + read).
- **Evaluation:** in `AutomationManager.evaluateMediaControl` (line 254), right
  after the existing "only act on a live wheel connection" guard, add:
  ```kotlin
  if (mc.requireExternalOutput && !AudioOutput.isExternalActive(audioManager)) {
      mediaPauseCandidateSinceMs = 0L
      mediaResumeCandidateSinceMs = 0L
      return
  }
  ```
  `AutomationManager` already owns an `audioManager` (line 52). Treating "not
  external" like "not connected" (early return + reset candidate timers) means
  the feature is fully inert on the phone speaker: it neither pauses nor resumes.
  `mediaAutoPaused` is intentionally left as-is, so if the rider paused on
  Bluetooth, then briefly drops the route, reconnecting and speeding up still
  resumes what this feature paused.

### Unit 3 - announcements gate

- **Setting:** add a top-level field beside `voiceAnnounceWhen` (AppSettings.kt
  ~63):
  ```kotlin
  // Extra AND condition on the periodic report: when true, only speak while
  // audio is on an external output (headphones / Bluetooth / wired / USB), not
  // the phone speaker. Independent of voiceAnnounceWhen.
  val voiceAnnounceRequireExternal: Boolean = false,
  ```
  Persisted in `SettingsJson` (write + read, `optBoolean` with `base` default).
- **Evaluation:** in `WheelService.startVoiceLoop` (line 581), extend the gate as
  an additional AND after the existing `allowed`:
  ```kotlin
  if (!allowed) continue
  if (settings.voiceAnnounceRequireExternal &&
      !AudioOutput.isExternalActive(audioManager)) continue
  ```
  `WheelService` gains a lazy `audioManager` (`getSystemService(AUDIO_SERVICE)`).
  The physical output route is independent of `voiceOutputChannel`
  (MEDIA/NOTIFICATION/ALARM), so this check is correct regardless of which stream
  the voice uses.

### Unit 4 - UI (both off by default)

- **Media control:** a toggle row in the media-control block on the Automations
  screen (`AutomationsContent.kt`, after the resume section ~286). Standard
  `Switch` with explicit on-colors (repo rule 4/6), wired through a new
  `SettingsViewModel` setter that `copy()`s `mediaControl`.
- **Announcements:** a toggle row in the "Report status" section
  (`SettingsScreen.kt` ~6354, under the `AnnounceWhenSelector`). New
  `SettingsViewModel.updateVoiceAnnounceRequireExternal` setter.
- Both use the same localized strings.

## Strings (localized to all locales, repo rule 12)

Two label/caption pairs (or one shared pair reused in both spots):

- `automation_require_external` = "Only on headphones or Bluetooth"
- `automation_require_external_caption` = "Skip the phone speaker. Also covers
  wired and USB audio."

Use rider terminology; keep labels short so they fit. Translate to every
supported locale.

## Data flow

```
telemetry tick / voice interval
  -> AutomationManager.evaluateMediaControl(settings)
       guard: connected? external-output-required -> AudioOutput.isExternalActive
       -> pause/resume via dispatchMediaKeyEvent
  -> WheelService.startVoiceLoop
       gate: voiceEnabled -> voiceAnnounceWhen -> require-external -> AudioOutput
       -> VoiceService.announceStatus
```

`AudioOutput.isExternalActive` reads live `AudioManager` output devices each call.

## Error handling / edge cases

- `getDevices` returning null -> helper returns false (gate closed = automation
  idle), the same conservative default `isHeadphonesActive` uses today.
- Route changes mid-ride are picked up on the next tick / next interval (no
  listener needed). Documented as a poll, not push.
- Media control: dropping the external route while auto-paused does not force a
  resume; playback stays wherever it is, consistent with the feature's existing
  "never blast audio" stance (`resetMediaControl` docs).

## Testing

- **Unit (`AudioOutput`):** with a mocked/faked `AudioManager` returning a device
  list, assert true for each external `TYPE_*`, false for
  `TYPE_BUILTIN_SPEAKER` only, false on null.
- **Media-control gate:** extend `AutomationManager` test coverage - with
  `requireExternalOutput = true` and speaker-only output, a slow speed does NOT
  send a pause key; with an external device present it does. Verify candidate
  timers reset on the gated path.
- **Announcement gate:** unit-test the `allowed && requireExternal` boolean
  decision (extract the predicate if needed to keep it testable without the full
  service).
- **Drift guard:** existing `SettingsJsonDriftGuardTest` enforces both new fields
  are serialized; run it.
- **Build:** grep for `BUILD SUCCESSFUL` (repo rule 14).

## Out of scope (YAGNI)

- Gating the separate speed-based auto-**volume** feature (rider asked only about
  play/pause and announcements).
- Distinguishing Bluetooth from wired/USB, or a per-route multi-select. The rider
  chose "any external".
- A live `AudioDeviceCallback` route listener. The per-tick poll is sufficient.
