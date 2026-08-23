# InMotion V1 metrics: consumption, range, and what a charge added

Three defects a V8S rider hit, plus the simulator that reproduces them.

## The report

- The Battery screen showed **"Added +54.0 %"** and, under it, **"Used 4 Wh"**.
  Four watt-hours is not what three hours of charging did; it is the wheel's own
  standby draw, presented as if it were the charge.
- **CONSUMPTION and RANGE read "--"** after a trip. During the ride they showed a
  number "a couple of times, for a very quick time", and the numbers were single
  digits against a real 15 Wh per km.

## What was wrong

**A telemetry frame threw away every number the phone worked out.** The decoded
frame is built from what the parser returned, so each field the parser does not
fill arrives at its default. Six of them are not the wheel's to report: the IMU
g-force, the forward-G estimate, and the two ride-efficiency figures, all written
by loops in `WheelRepository` on their own cadence. The efficiency loop wrote
them once a second and the next frame, 250 ms later on an InMotion V1, put NaN
back. That is the "answers for an instant, then blank" the rider described.
`WheelData.carryPhoneSideFrom` now names the rule in one place.

**Braking looked like a reconnect.** The efficiency window restarted whenever net
energy fell, and net energy falls every time a rider brakes. The comment above it
described the consumed counter, which really is monotonic; the code tested the
net. On a simulated V8S city ride the window was wiped 296 times in 45 minutes
and the tiles were blank two thirds of the time, showing a number only when a
stretch of road happened to survive long enough to cross the distance floor,
which is where the single digits came from.

**The window aged on the wall clock.** A rate needs ground covered, so ageing
samples out by time empties the window the moment the wheel stops, which is
exactly when a rider looks at the dashboard. It now ages in riding time: standing
still freezes the window rather than draining it, and a half hour of standing
ends the hold, because by then it describes a ride that is over.

**A charge with no charge current was never seen as a charge.** InMotion wheels
report nothing useful while charging: the V14 and P6 sit at about 0 A, and a V8S
keeps reporting its board's idle draw, +0.02 A, for the whole session. Detection
fell back to the pack percentage climbing, but that fallback was gated on the
model name containing "P6", so a V1 charge was read as idle for its whole
session, which is why the "Used" row (already meant to hide while charging) was
on screen showing that idle draw.

## What changed

- **`WheelCapabilities.reportsChargeCurrent`**, false for both InMotion
  generations, replaces the P6 model-name gate. A wheel that states a charge flag
  never reaches the detector while the flag is set, so this only adds detection
  where there was none.
- **`ChargeRiseDetector`**, extracted from `deriveChargeStatus` and rewritten for
  a percentage that arrives in whole numbers. A fixed 45-second horizon against a
  whole-number percentage reads either 0 or 1.33 %/min depending on where the step
  lands, so it alternated on and off all through a charge. It now waits for the
  pack to gain a point and divides by however long that took, and latches only
  after three minutes of climbing without a break, so the voltage rebound after a
  rider steps off a V1 cannot be mistaken for a charger.
- **`ChargeEnergy.stepWh` integrates nothing while a currentless wheel charges.**
  Filing the board's idle draw either way is worse than filing nothing.
- **"Charged (est.)"** on the Battery screen instead, from the percentage the
  charge added and the pack size the rider already enters for the range estimate.
  Rough, and labelled as an estimate, but a rider watching +54 % go by is better
  served by "about 540 Wh" than by silence. (An earlier attempt at this,
  60ce4b6c, was reverted in fcac0563 because it needed an Advanced setting of its
  own; the per-wheel Capacity field has existed since.)
- **`RideEfficiencyTracker`**, the window extracted out of `WheelRepository` so a
  whole ride can be replayed against it.

## The simulator

`InMotionV1VirtualWheel` (scan screen, Virtual wheels, Service Mode only) emits
real V1 wire bytes: the `AA AA ... 55 55` envelope with `0xA5` stuffing around a
16-byte CAN prefix and an extended payload, decoded by the unchanged adapter and
parser. Both defects only appear over time, so the script runs long: 40 minutes of
city riding with regen on every brake, a two-minute park, then two hours on a
charger that never reports a current. It loops.

The pack is modelled rather than scripted, a state of charge in watt-hours that
the ride spends and the charger refills, mapped back to a terminal voltage through
an open-circuit curve and an internal resistance. Battery percent is not on the
wire on this family, so the app derives it from that voltage, sag under load and
rebound at a standstill included, and the rebound is what the charge detector has
to tell apart from a real charger.

## Verified

`./gradlew :app:testDebugUnitTest`: 802 tests, 0 failures. `:app:assembleDebug`
BUILD SUCCESSFUL.

Both regressions were confirmed to fail the new tests before the fix: restoring
the net-energy restart check fails five of the eight tracker tests, and restoring
wall-clock ageing fails the end-of-trip one.

On a Pixel-class AVD (API 36) against the virtual V8S, English, miles:

- CONSUMPTION reads 26 Wh/mi and RANGE 36 mi within two minutes of connecting,
  both with a filled sparkline, and both hold through the braking phases that
  used to blank them.
- The rest of the InMotion V1 surface still decodes off the simulator's bytes:
  model V8S, firmware 1.2.22, tiltback 45 km/h, odometer, trip, speed, voltage,
  current sign on regen.
