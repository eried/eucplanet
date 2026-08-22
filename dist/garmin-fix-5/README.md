# Garmin fix-5 test builds

Watch-side builds from branch `garmin-fix-5-combo`, commit `c65a4ae4`. Any
recent phone build pairs with them; for the input log use the phone APK from
the same branch pre-release.

## Pick your file

| Watch | File |
|---|---|
| fenix 8 47mm / 51mm (AMOLED), tactix 8, quatix 8 | `EucPlanet-fix5-combo-fenix847mm.prg` |
| fenix 8 43mm | `EucPlanet-fix5-combo-fenix843mm.prg` |
| fenix 8 Pro 47mm | `EucPlanet-fix5-combo-fenix8pro47mm.prg` |
| fenix 8 Solar 47mm | `EucPlanet-fix5-combo-fenix8solar47mm.prg` |
| fenix 8 Solar 51mm | `EucPlanet-fix5-combo-fenix8solar51mm.prg` |
| Instinct 2 / 2S / 2X | `EucPlanet-fix5-combo-instinct2.prg` |

Install: connect the watch over USB, copy the file into its `GARMIN/Apps`
folder, disconnect. Details in `docs/GARMIN_SETUP.md`.

## What is in fix-5

- 1.5 s heartbeat plus per-frame acks: live status stays up, dial updates in
  real time (confirmed in the field).
- Touch buttons show their binding, bigger targets, white press flash.
- Screen taps can no longer fire the physical Button 1/2 bindings. On touch
  watches, onSelect / onMenu only dispatch after a real key event; keyless
  (tap-synthesized) calls are ignored and logged. Non-touch watches are
  unaffected.
- Input reporting: while the phone's Service Mode records, every watch input
  lands in the log as `garmin input:` lines with the callback and binding.

## What to verify

1. Touchscreen enabled, Service Mode recording on the phone.
2. Tap the screen anywhere: nothing should happen; the log shows
   `onSelect ignored (tap-synthesized, no key)`.
3. Press the physical Start button: the bound action fires; the log shows
   `onKey k=...` then `onSelect act=...`.
4. Ride: speed and battery should track in real time, status stays live.

Share the diagnostics `.txt` afterwards.
