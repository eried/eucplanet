# multi-wheel-support

## What this branch adds

Foundation work for supporting wheels beyond the V14. The protocol layer
splits into per-family adapters and the dashboard learns to surface its
state for any of them. Concretely shipped here:

- **InMotion V2 model registry** covering V11 / V11Y / V12 HS / HT / Pro /
  V12S / V13 / V13 Pro / V14 50GB / V14 50S / V9 / P6. The wheel reports
  its model ID on connect and the registry maps it to the right command
  variant (horn opcode, max-speed packet shape, etc.).
- **P6 connect path.** The scan now lists `P6-XXXXXXXX` peripherals, and
  the InMotion V2 adapter switches to the P6's extended-routing-only
  command set when it sees that name. Voltage, discharge current, and a
  rough battery estimate come through; richer telemetry parsing is the
  remaining work tracked under `docs/BLE_CAPTURE_GUIDE.md`.
- **Wheel simulator** in the connect screen. Two virtual wheels (V14 and
  P6) feed canned BLE responses through the real adapter pipeline, so the
  whole UI works without hardware. Useful for translation, layout, and
  capability-gating work.
- **Experimental banner** on the dashboard for any wheel that isn't a
  verified V14. Tapping it opens a prefilled GitHub Issue (`Wheel report`
  template) with the connected wheel and firmware already filled in.
- **Wheel report template** at `.github/ISSUE_TEMPLATE/wheel_report.yml`
  with structured fields for wheel model, firmware, status, what works,
  details, and phone OS.

## Who should test this

- **V14 owners**: confirm that nothing changed for you. The banner stays
  hidden, the dashboard reads the same values, horn / light / lock /
  safety mode still work.
- **P6 owners**: connecting now works. Confirm the dashboard reports a
  plausible pack voltage (around 230–240 V at full charge) and that
  battery current swings positive when accelerating. Speed and the
  remaining telemetry will read zero until the byte offsets are pinned —
  the orange banner walks you through filing a labeled capture.
- **Owners of any other InMotion wheel** (V11, V12, V13, V9): try
  connecting. Telemetry decoding outside the V14 family is unverified,
  so expect wrong values. If anything works or fails, tap the orange
  banner to fire off a wheel report.
- **No-wheel testers**: in the connect screen, scroll past the BLE list to
  the "Simulate a wheel" section. Tap V14 or P6 to drive the dashboard
  with synthetic telemetry.

## Known limits

- **P6 real-hardware support is preliminary.** Connecting to a real P6
  now works (the BLE name `P6-XXXXXXXX` puts the adapter on the
  extended-routing protocol the wheel actually speaks) and the dashboard
  shows live voltage and discharge current plus a rough battery estimate
  from the pack voltage. The remaining telemetry — speed, PWM, motor
  temperature, trip distance, the per-pack battery split — is still
  unmapped because the data block's byte layout differs from V14 and we
  only have parked captures so far. Help us pin those offsets by recording
  a labeled session (`docs/BLE_CAPTURE_GUIDE.md`) while riding.
- **P6 settings, locking, and safety-mode max-speed control are
  disabled** until the matching control packets are reverse-engineered.
  The UI doesn't gate them yet, so tapping those buttons on a P6 is a
  no-op — that should be obvious from the wheel not responding.
- **KingSong, Veteran, Begode/Gotway, InMotion V1 family (V8 / V10)**:
  their adapters aren't built yet, so connecting won't work. They appear
  in the wheel-report dropdown for users to manually pick if they want
  to file feedback.

## Feedback

Tap the orange banner on the dashboard for a prefilled GitHub issue, or
open one manually at
https://github.com/eried/eucplanet/issues/new?template=wheel_report.yml
