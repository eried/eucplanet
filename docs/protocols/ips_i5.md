# IPS protocol (i5 and eWheel-service siblings)

Status: **decoded from the official app**, hardware-confirmation pending. This
documents the wire format only, in our own words and tables. No third-party
source code is reproduced; UUIDs, byte offsets and scalings are facts.

## Source

Decompiled from the official **iAmIPS 4.4.2** Android app (package
`com.iamips.ipsapp`, `iAmIPS-4.4.2.apk`), the last release before IPS went
defunct. IPS is a Shenzhen/Singapore brand (not French - the APK is mirrored
by the French shop EspritRoue, which is the likely source of that impression).
Everything below is read out of the app's own `GattAttributes`, `UUIDDatabase`,
`Utils`, `VehicleInfo` and the per-value fragments. This supersedes the older
2015 XIMA/Lhotz `FF00` sniff: the i5 app uses a completely different,
richer GATT layout.

## GATT layout

This is NOT a write-opcode-poll protocol like KingSong/Begode. It is a
**characteristic-per-value** design: each telemetry field is its own GATT
characteristic that the app either subscribes to (notify) or reads on a timer.

- Primary service (`EWHEEL_SERVICE`): `0000eb00-0000-1000-8000-00805f9b34fb`
  - Confirm against a real i5 with nRF Connect; the app matches this UUID at
    service discovery, but the eb00-service / 90xx-characteristic split is
    unusual enough to verify.
- Firmware bootloader service (DFU, not needed for telemetry):
  `00060000-f8ce-11e4-abf4-0002a5d5c51b`

All characteristics are `0000XXXX-0000-1000-8000-00805f9b34fb`:

| UUID | Name | App access | Payload / decode |
|------|------|-----------|------------------|
| 9000 | CONTROL | write | command channel |
| 9001 | STATUS | read | `byte[0]` = error/status code (0 = OK; else index into the app's err_message table) |
| 9002 | SPEED | **notify** + read | **float32 LE @0 = speed in km/h** |
| 9003 | POWER/CURRENT | **notify** + read | **float32 LE @1 = current (A)**; byte[0] is a leading flag |
| 9004 | DISTANCE (trip) | read | **float32 LE @0 = km** |
| 9005 | TOTAL_MILEAGE | read | **float32 LE @0 = km** |
| 9006 | BATTERY_LEVEL | read | `byte[0]` = battery % |
| 9007 | BATTERIES_VOLTAGE | read | **16 × float32 LE** = per-cell voltages (cells 1..16) |
| 9008 | DRIVER_TEMPERATURE | read | float32 LE = temperature |
| 9009 | LIGHTS_MODE | read/write | `byte[0]` = light-mode index (write to set) |
| 900a | MAX_SPEED | read/write | `byte[0]` = speed-limit **option code** (not km/h; app maps it through its top_speed table) |
| 900b | RUN_MODE | read/write | `byte[0]` = ride-mode index |
| 900c | RESET_BALANCE | write | pedal-balance reset trigger |
| 900d | VEHICLE_INFO | read | 40-byte struct (below) |
| 900e | DRIVER_VERSION | read | driver-board firmware version |
| 900f | UPGRADE | firmware DFU |
| 902f | FACTORY_CHANNEL | factory / calibration |
| 903a | DEVICE_NAME | read/write | rename the wheel |
| 903c | BMS_INFO | read | floats: `@4`=BMS temp, `@0`=id1, short`@4`, `@6`, `@7` |
| 903d | BLACKBOX | read | event log |
| 903e | TIME_SYNC | write | set wheel clock |
| 9500 | DEBUG | read/write | debug channel |

### VEHICLE_INFO (900d), 40-byte struct

| Offset | Type | Field |
|--------|------|-------|
| 0..20  | ASCII (trimmed) | vehicle type / model string |
| 20..33 | ASCII | serial number (13 chars) |
| 33..37 | bytes | BLE firmware version (`b[33].b[34].b[35]`) |
| 37..39 | uint16 LE | battery watt-hours |
| 39     | byte | battery cell count |

### Byte helpers (from the app's `Utils`)

- float32: little-endian IEEE-754 -
  `(b0 & 0xFF) | (b1<<8) | (b2<<16) | (b3<<24)` then `intBitsToFloat`.
- uint16: little-endian `(b0 & 0xFF) | (b1<<8)`.

So speed, trip and total mileage are plain LE float32 already in km/h / km -
no scaling factor. Current is LE float32 at offset **1**. Battery % and the
mode/limit fields are single bytes.

## How the app drives it

- **Subscribes (notify)** to SPEED (9002) and CURRENT (9003).
- **Reads on a timer** DISTANCE (9004), TOTAL (9005), BATTERY (9006), STATUS
  (9001), VEHICLE_INFO (900d), LIGHTS (9009), RUN_MODE (900b), MAX_SPEED
  (900a), and BATTERIES_VOLTAGE (9007).
- Writes single bytes to LIGHTS (9009), MAX_SPEED (900a), RUN_MODE (900b) to
  change settings; RESET_BALANCE (900c) and TIME_SYNC (903e) are write
  triggers. There is no software lock.

## What this means for EUC Planet

The current adapter architecture (`BleProfile` = one service + one write char +
one notify char; the poll loop writes a command frame and parses a single
notification stream) does **not** fit a characteristic-per-value wheel. Wiring
the i5 in needs a BLE-layer extension so an adapter can declare a set of
notify characteristics and a set of periodically-read characteristics, and get
each result routed to it tagged with its source UUID. See the adapter's TODO
and the branch notes. Until then `IpsAdapter` carries the decode helpers and
the full UUID map but emits nothing to the dashboard.

## Still needs a real i5 (quick)

1. nRF Connect scan: the i5's exact **advertised name** (for the scan filter)
   and confirmation of the **eb00 service + 90xx characteristics**.
2. One short capture to sanity-check the float scalings against the app's
   displayed speed / battery / voltage.

## Attribution

Protocol facts are restated in our own words from a static read of the
official iAmIPS 4.4.2 app for interoperability with defunct IPS hardware. No
app source code, and no GPL code, is reproduced here. This app is MIT-licensed.
