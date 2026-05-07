# multi-wheel-support

## What this branch adds

Foundation work for supporting wheels beyond the V14. The protocol layer
splits into per-family adapters and the dashboard learns to surface its
state for any of them. Concretely shipped here:

- **InMotion V2 model registry** covering V11 / V11Y / V12 HS / HT / Pro /
  V12S / V13 / V13 Pro / V14 50GB / V14 50S / V9 / P6. The wheel reports
  its model ID on connect and the registry maps it to the right command
  variant (horn opcode, max-speed packet shape, etc.).
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
- **Owners of any other InMotion wheel** (V11, V12, V13, V9, P6): try
  connecting. Telemetry decoding outside the V14 family is unverified,
  so expect wrong values. If anything works or fails, tap the orange
  banner to fire off a wheel report.
- **No-wheel testers**: in the connect screen, scroll past the BLE list to
  the "Simulate a wheel" section. Tap V14 or P6 to drive the dashboard
  with synthetic telemetry.

## Known limits

- **P6 telemetry on real hardware is not parsed yet.** The simulator
  pretends the P6 speaks V14-shape framing so the dashboard renders, but
  on a real P6 the wheel uses a different binary layout and most fields
  will read zero. Fix is pending a labeled BLE capture from a real P6
  owner, see `docs/BLE_CAPTURE_GUIDE.md`.
- **KingSong, Veteran, Begode/Gotway, InMotion V1 family (V8 / V10)**:
  their adapters aren't built yet, so connecting won't work. They appear
  in the wheel-report dropdown for users to manually pick if they want
  to file feedback.

## Feedback

Tap the orange banner on the dashboard for a prefilled GitHub issue, or
open one manually at
https://github.com/eried/eucplanet/issues/new?template=wheel_report.yml
