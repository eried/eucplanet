# p6-fixes

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

### 3. Lock taps could clobber each other's pending auth

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
