# p6-fixes (v0.3.2-p6preview4)

## Round 3 fixes (preview4)

Real-hardware testing of preview3 confirmed speed-limit changes work
cleanly, but two issues remained: the temperature was wrong and frozen,
and the light only toggled ON. Both root-caused to wrong byte offsets.

### A. Temperature was reading a static config byte

preview3's `data[71]` MOS read was wrong. The labelled InMotion-app
capture (`docs/P6_CAPTURE_LABELS.md`) had ground-truth values right
there: MOS at offset 28 and **motor** at offset 30, both as `byte / 4`
in °C. Verified at v1:23: displayed MOS = 82 °F → data[28] = 111 →
27.75 °C; displayed motor = 124 °F → data[30] = 204 → 51.0 °C.

The earlier "data[71] walks 67 → 70 over a ride" observation came from
a short capture that happened to drift across that byte's natural
range. On the user's wheel, data[71] is a static config byte: the app
froze at 75 °F while the wheel's own display moved 75 → 81 under load.

Fix: read both MOS (offset 28) and motor (offset 30) at `byte / 4 = °C`.
The dashboard's "TEMP" pill shows `max(MOS, motor)`, which during a ride
is the motor — the value the user actually cares about.

### B. Light toggle only sent ON, never OFF

`toggleLight()` reads `_wheelData.value.lightOn` to decide whether to
send the ON or OFF write. Our P6 telemetry parser never populated
`lightOn`, so the UI thought the light was always off and the next
tap kept resending `60 50 01 01`.

Analysis of the four labelled `60 50` toggles in the capture
(13:25:27.085 ON, 29.885 OFF, 32.623 ON, 35.252 OFF) found the state
in **bit 1 of byte 84**: 0x02 across every frame inside an ON window,
0x00 across every frame inside an OFF window (248×0x00 vs 3×0x02
across the full 251-frame log, matching exactly).

Fix: parse `(data[84] & 0x02) != 0` as `lightOn`. The toggle now
correctly inverts the current state on each tap.


## Round 2 fixes (preview3)

Real-hardware testing of preview2 surfaced three more bugs, traced to
deeper analysis of the labelled capture (`docs/P6_CAPTURE_LABELS.md`).

### A. Light still didn't toggle — auth handshake expires

The connect-auth bytes are 100% correct (verified bit-identical to the
InMotion app), but the app re-runs the handshake ~1× per 6 s during a
session. Our one-shot prime at connect expired before the user's first
control tap, so writes were silently dropped at the wheel.

Fix: `runPollingLoop` now re-runs the handshake every 24 polls (~6 s)
for adapters where `requiresConnectAuth()` is true. Same byte payload,
just re-fired periodically to keep the control endpoint primed.

### B. Temperature read at the wrong byte (data[70] is a counter)

Earlier `data[70]` analysis matched 72°F by coincidence — the parked
NEW CAPTURE had the counter parked at 78 / mid-cycle near 72. In a long
ride, `data[70]` cycles 0..255 once per second of motion, producing
the user-reported 225°F bogus reading.

The real MOS sensor lives at `data[71]`: 72°F across 181/182 frames in
the parked capture, monotonic 67→68→69→70 walk over a 25-min ride —
the smooth thermistor curve a real sensor produces.

Fix: read `data[71]` instead. Sanity-gate to 50..140°F so reassembly
artefacts from multiplexed BLE frames don't pollute the dashboard.
Motor and driver-board temperatures are not in the realtime stream
on this firmware; we expose only the MOS reading until the others
are located.

### C. Speed change "bugged out the wheel"

`60 3e [val 00 00]` is **not** a "commit max-speed to flash" packet —
it's the alarm-speed setter. Our 3-write sequence
(`60 21 [tilt]`, `60 3e [tilt]`, `60 3e [alarm]`) was overwriting the
alarm with the tiltback value transiently before the proper alarm
write landed. Three back-to-back writes also tripped firmware
debounce.

The InMotion app sends only `60 21 [tilt]` followed by `60 3e [alarm]`
(when both change), or `60 21 [tilt]` alone (max-speed only), or
`60 3e [alarm]` alone (alarm only). Multiple mid-drag `60 21` writes
with no commit were honoured by the wheel and persisted across
reboots — `60 21` alone is sufficient.

Fix: `setMaxSpeedCommit` now returns null on P6 (was sending the
redundant `60 3e [tilt]`). The `setMaxSpeed` + `setAlarmSpeedCommit`
pair matches what the InMotion app sends.



## What this branch fixes

Three concrete P6 bugs found by analysing a labelled real-hardware capture
(`FINALP6/NEW CAPTURE/btsnoop_hci.log` + matching screen recording, with
the InMotion app's "Detailed Data" page providing ground-truth values).

This branch is built directly on top of `main` 0.4.0-preview1, so V14 /
V12 / Wear OS / multi-wheel preview adapters are all preserved unchanged.
The only path it touches is the InMotion P6.

### 1. Temperatures were wrong

The previous parser read `data[28]/4` for MOS and `data[30]/4` for motor
in the 0x87 realtime frame. Those bytes are not temperatures — they are
the **speed-alarm field** (`uint16 LE` in 0.01 km/h, fixed at 13679 =
85 mph for our wheel). Across 2,300+ frames in the long ride capture,
`data[28]` was constant at 111. The /4 reading happened to land near a
plausible Celsius value in the original short capture by coincidence.

The real **MOS sensor** is at `data[70]` as a raw Fahrenheit byte:

```kotlin
val mosF = data[70].toInt() and 0xFF
val mosC = (mosF - 32) * 5f / 9f
```

Verified against:
- Parked wheel labelled MOS = 72 °F → `data[70]` reads 0x48 = 72 across
  the entire NEW CAPTURE (181/181 frames).
- 25-min ride OLD capture: `data[70]` drifts 67-80 °F (19-27 °C),
  warming under load — physically correct thermistor signal.

**Motor and driver-board temps do not appear in the realtime 0x87 stream
on this firmware.** Every candidate offset is either a static config
byte or a wrap-around counter. The InMotion app shows those as 79 °F
on a parked wheel which is most likely a cached default rather than a
live sensor read. They are therefore no longer reported on P6 until a
different request unlocks them.

### 2. Lights / horn / max-speed were silently ignored until connect-auth

Our `setP6Light` byte output is **byte-for-byte identical** to what the
InMotion app sends — `aa aa 16 06 02 21 60 50 [v v 03]` — but the wheel
silently drops control writes at the L2CAP layer until a password
handshake has run once after connect. The InMotion app does this on
every connect; we previously only did it on demand when locking.

Added `requiresConnectAuth(): Boolean` to `WheelAdapter` (default
false), set true in the P6 path of `InMotionV2Adapter`. The polling
loop in `WheelRepository.runPollingLoop` now calls
`runConnectAuthHandshake()` once between the init sequence and the
first realtime poll for wheels that need it. V14 family wheels are
unaffected.

The handshake is a fixed echo (the wheel returns a 16-byte "encrypted"
blob and accepts the same blob back), so adding it doesn't change the
security posture — it just primes the wheel's control endpoint.

### 3. Speed-limit slider was clamped to 56 mph on P6

`_maxSpeedCap` defaulted to 90 km/h (the V14-era fallback) and was only
updated when the wheel sent a `DecodeResult.ModelName` event back. On
P6 that fires when the info-bundle response (`02 86 …`) lands, which
needs a full BLE round-trip after init. Until then the slider was
capped at 90 km/h ≈ 56 mph, regardless of the wheel's real ceiling.

The P6 model entry also had `maxSpeedKmh = 130` (≈ 81 mph) but the
wheel's own InMotion app reports 93 mph (≈ 150 km/h) as its hardware
max. Two fixes:

- `notifyConnectingTo` now returns a `DecodeResult.ModelName` when the
  BLE name alone is enough to identify the wheel (P6's `P6-XXXXXXXX`
  pattern). `BleConnectionManager` emits it immediately so the slider
  cap updates before any BLE traffic.
- `InMotionV2Model.P6.maxSpeedKmh` bumped 130 → 150 km/h to match the
  wheel's actual ceiling. The serial-bearing `ModelName` from the
  info-bundle response still fires later as before.

### 4. Lock taps could clobber each other's pending auth

`pendingAuthKeyDeferred` and `pendingAuthConfirmDeferred` were nullable
singletons assigned by `authenticateAndLock`. Two near-simultaneous
`toggleLock()` calls (rapid taps, or a connect-time auth racing a
manual tap) both wrote the singletons; the first call's deferred got
overwritten and timed out without ever reaching the `setLock` write —
matching the symptom "lock looks like it took, then nothing happened".

Wrapped `authenticateAndLock` body in `authMutex.withLock { … }` so
concurrent taps queue up cleanly. Each handshake completes its full
request → key → verify → confirm → lock cycle before the next runs.

## Other deliverables

- Added `setP6AutoHeadlight(on)` builder (`60 2f [v]`) for the
  General Settings → Lighting → Auto Headlight switch. Verified on the
  wire (5 toggles in the capture, frames `2f 01 7e` for ON and
  `2f 00 7f` for OFF). UI wiring to follow.
- New analysis tools under `tools/` (`p6_new_writes.py`,
  `p6_new_realtime.py`, `p6_temp_search.py`) for replaying captures
  during future protocol work.

## What still needs verification

- The auth-on-connect change should be tested on a real V14 to confirm
  no regression. The default-false flag means V14 takes the same code
  path as before, but worth a sanity test.
- Whether the wheel re-locks the control endpoint after some idle
  window. If so we'll need to repeat the handshake periodically; the
  capture only spans 3 minutes which isn't enough to tell.
- Motor and driver-board temperatures may live in a different request
  family (e.g. an info-bundle or settings sub-cmd we don't poll). A
  longer hot-wheel capture comparing post-ride values to telemetry
  bytes could reveal a different field.

## Feedback

File issues at https://github.com/eried/eucplanet/issues. Please tag
P6-specific issues with `wheel:p6` if you can.
