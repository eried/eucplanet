# external-gps

External BLE GPS support (RaceBox) for ground-truth speed verification
and wheel calibration, plus Compare-tab tooling and the usual polish.

## What's new

**External GPS (RaceBox Mini / Mini S / Pro)**
- Pair in Settings → Integration → External GPS.
- Purple speed dot on the dial + "GPS X.X" readout under the main speed.
- `Ext GPS speed` column in trip CSVs, purple overlay on the trip chart.
- **E** indicator top-right: dim when stale, lit when sending, hidden when
  no external GPS is paired.

**Live data sources sheet (tap the GPS icon)**
- Phone / Wheel / External tabs + a Compare tab.
- Compare auto-decalibrates the wheel speed so wheel-vs-GPS delta reflects
  the real sensor offset, not the residual after the current calibration.
- A/B selection persists across sheet open/close; first compare entry
  seeds Wheel vs External (or Phone fallback).
- **Calibrate wheel** stays enabled whenever the wheel reports motion.

**Wheel parameters**
- Speed calibration range widened from ±5 % to ±15 %.
- Per-wheel profiles (keyed by BLE name) restore tiltback / alarm /
  calibration automatically on reconnect.

**Dashboard polish**
- Lock Wheel stays enabled at all speeds; tap while moving shows a
  "Slow down to lock the wheel" toast. Repository still hard-blocks the
  actual lock command on every entry path.

**Begode imperial fix**
- Begode firmware emits mph-scaled bytes when the wheel's screen is set
  to imperial. EUC Planet now reads the units flag and converts back to
  km internally so speed reads correctly in either app unit setting.

## Who should test this

- RaceBox owners: pairing, dial overlay, Compare tab, CSV column.
- Begode riders: flip the wheel display to imperial via the Begode app,
  then check EUC Planet reads correct speed in both metric and imperial
  app modes.
- Everyone else: confirm dashboard / Compare / Lock Wheel still behave
  normally without an external GPS paired.

## Known gaps

- No auto-connect for the external GPS on app start; tap Reconnect after.
- No reconnect-on-disconnect retry when the box goes out of range.
- Dial upper bound still scales to wheel max; a faster external source
  will pin at the top.

## How to install

Debug-signed CI build. Uninstall any Play Store install first, then
install this APK. Reinstalling from Play later overwrites this build.

## Feedback

Open an issue at https://github.com/eried/eucplanet/issues tagged
`branch:external-gps`.
