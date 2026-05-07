# external-gps

## What this branch adds

External BLE GPS box support for high-accuracy speed and position logging. The
first (and only) implementation is **RaceBox** — Mini, Mini S, Pro. The wheel
and the GPS box hold separate BLE connections, so pairing one doesn't affect
the other.

When a RaceBox is paired and connected:

- A small purple **dot on the speedometer dial** marks the external GPS speed
  alongside the wheel's needle.
- A **"GPS X.X" readout** sits under the main speed number, in the same purple
  so the two read as a pair.
- Trip CSVs gain one new column at the end: **`Ext GPS speed`**. Empty when
  no device is paired so analysis tools can tell "not connected" from "0 km/h".
- The trip-detail speed chart adds a thin purple overlay line for the external
  GPS speed (only when the CSV has data for it).

Pairing UI lives in **Settings → Integration → External GPS**.

## Who should test this

You need a RaceBox device to exercise the actual protocol. Without one you can
still:

- Check that the Integration tab shows the new section.
- Confirm the dashboard is unchanged when no device is paired.
- Inspect a trip CSV from a normal recording — the new trailing column should
  exist with empty cells.

## Known limitations

- **No real-device verification yet.** The UBX-NAV-PVT parser is implemented
  to spec but has not been confirmed against an actual RaceBox. Reach out if
  it doesn't decode.
- **Auto-reconnect on app restart is not wired yet.** You'll need to tap
  "Reconnect" after pairing if the connection drops or the app restarts.
- **No reconnect-on-disconnect retry.** If the GPS box goes out of range,
  the connection won't come back automatically.
- **Dial gauge upper bound** still follows the wheel's max speed. If you
  paired RaceBox to a moped that goes 80 km/h but the wheel max is 50, the
  dot will pin at the top of the dial.

## How to install

This is a **debug-signed build** (CI). It can't update an existing Play Store
install in place — uninstall the Play version first, then install this APK.
Reinstalling the Play Store version later will overwrite this branch build.

## Provide feedback

Open an issue at https://github.com/eried/eucplanet/issues with the tag
`branch:external-gps`.
