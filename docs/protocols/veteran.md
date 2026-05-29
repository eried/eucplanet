# Veteran (LeaperKim) BLE protocol

Reference for parser and command builder implementation. Covers Sherman, Sherman S,
Sherman Max, Sherman L, Patton, Patton S, Lynx, Lynx S, Abrams and Oryx.

The Veteran wire format is also used by recently rebranded Nosfet wheels (Apex, Aero,
Aeon) which run the same firmware family. Where this doc says "Veteran" it means
"any wheel that emits the `0xDC 0x5A 0x5C` magic header."

This document is original prose. Source code from WheelLog (GPLv3) was read for
research. No code was copied. See Attribution at the end.

## 1. GATT profile

Veteran reuses the generic HM-10 / 0xFFE0 service that Begode and KingSong also use.
There is no Veteran-specific UUID. Telemetry is a notify stream on `0xFFE1`; commands
are written to the same characteristic.

| Role            | UUID (full 128-bit)                    | Short |
|-----------------|----------------------------------------|-------|
| Service         | `0000ffe0-0000-1000-8000-00805f9b34fb` | 0xFFE0 |
| Notify char     | `0000ffe1-0000-1000-8000-00805f9b34fb` | 0xFFE1 |
| Write char      | `0000ffe1-0000-1000-8000-00805f9b34fb` | 0xFFE1 |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` | standard |

Procedure on connect: enable notifications on `0xFFE1` (write `01 00` to its CCCD),
then start receiving frames.

Distinguishing Veteran from Begode on the same UUIDs is done by sniffing the first
bytes of the notification stream:

| First bytes seen      | Wheel family |
|-----------------------|--------------|
| `0xDC 0x5A 0x5C`      | Veteran      |
| `0x55 0xAA`           | Begode       |

## 2. BLE name pattern

There is no canonical advertising prefix. Field reports show names such as
`GotWay_*`, `Veteran_*`, the wheel's serial, or a user-set custom name. Do not filter
on name. Either let the user pick from a scan list, or filter on advertised service
UUID `0xFFE0` and then disambiguate by the magic bytes above once the first
notification arrives.

## 3. Frame structure

Veteran frames are sent in pieces over BLE notifications and must be reassembled. A
20-byte MTU is typical; a single logical frame spans 2 to 4 notifications.

A reassembled frame looks like:

```
short (LEN <= 38, no CRC):
| DC | 5A | 5C | LEN | payload                        |
  off 0  1   2   3     4 .. LEN+3
  total buffer = LEN + 4 bytes

long (LEN > 38, with CRC):
| DC | 5A | 5C | LEN | payload         | crc32 (u32 BE) |
  off 0  1   2   3     4 .. LEN-1        LEN .. LEN+3
  total buffer = LEN + 4 bytes (CRC included)
```

Field rules:

| Field     | Size | Notes |
|-----------|------|-------|
| Magic     | 3    | Constant `DC 5A 5C`. Frame start. |
| Len       | u8   | Stored at offset 3. Total buffer size is always `LEN + 4` bytes (including magic, the LEN byte itself, payload, and the CRC trailer when present). |
| Payload   | varies | Short frames: `LEN` payload bytes at offsets 4..LEN+3. Long frames: `LEN - 4` payload bytes at offsets 4..LEN-1, with the CRC at LEN..LEN+3. All multi-byte fields big-endian unless noted. |
| CRC32     | u32 BE | Present when `LEN > 38` (newer firmwares with smart-BMS). Computed with the standard zlib CRC32 polynomial over `buff[0..LEN-1]` (the bytes preceding the CRC trailer). |

Reassembly logic the parser must implement:

1. Maintain a sliding state machine over the notification byte stream.
2. Watch for the magic `DC 5A 5C` triple. When seen, reset a new buffer with those
   three bytes already pushed.
3. The next byte is `LEN`. Push it.
4. Push subsequent bytes until the buffer holds `LEN + 4` bytes total.
5. If `LEN > 38`, the last four bytes of the buffer are a big-endian u32 CRC.
   Verify against `CRC32(buff[0..LEN-1])`. Drop the frame on mismatch.
6. If a packet stalls (no bytes for ~100 ms), drop the partial buffer and re-sync.

WheelLog also sanity-checks specific bytes during accumulation (for example
`buff[22] == 0x00`, `buff[30] in {0x00, 0x07}`, `buff[23] & 0xFE == 0x00`). Treat
these as soft re-sync triggers, not mandatory checks; they reject frames where the
magic was matched inside random payload.

## 4. Realtime telemetry

Standard live-data frame (no BMS extension), `LEN = 38`. All offsets are absolute
into the reassembled buffer.

| Offset | Type   | Field             | Scale / units                          |
|-------:|--------|-------------------|----------------------------------------|
| 0..2   | bytes  | magic             | `DC 5A 5C` |
| 3      | u8     | len               | declares frame length |
| 4..5   | u16 BE | voltage           | centivolts (raw / 100 = volts) |
| 6..7   | i16 BE | speed (raw)       | raw value is 0.1 km/h units (e.g. 250 = 25.0 km/h). Negative when reversing. |
| 8..11  | u32 word-swapped | trip distance | meters. Word-swapped: `(b10<<24) | (b11<<16) | (b8<<8) | b9` |
| 12..15 | u32 word-swapped | total distance | meters. Same word-swap encoding as trip. |
| 16..17 | i16 BE | phase current     | raw value is 0.1 A units. Sign convention: positive accelerating, negative regen. Some firmwares always emit positive; see settings below. |
| 18..19 | i16 BE | mosfet temperature | hundredths of degree Celsius (raw / 100 = degrees C) |
| 20..21 | u16 BE | auto-off timer    | seconds until idle shutdown |
| 22..23 | u16 BE | charge mode flag  | non-zero = charging |
| 24..25 | u16 BE | speed alert       | raw value is 0.1 km/h units (alert threshold) |
| 26..27 | u16 BE | speed tiltback    | raw value is 0.1 km/h units (tiltback threshold) |
| 28..29 | u16 BE | firmware version  | encoded as `MMmrr`: model = `ver / 1000`, minor = `(ver % 1000) / 100`, revision = `ver % 100` |
| 30..31 | u16 BE | pedals mode       | 0 = hard, 1 = medium, 2 = soft (mirrors the SETh / SETm / SETs commands) |
| 32..33 | i16 BE | pitch angle       | hundredths of degree (raw / 100 = degrees, positive forward) |
| 34..35 | u16 BE | hardware PWM      | 0..10000 (raw / 100 = duty percent). Only valid on `model >= 2`. |

There is no battery-percent byte in the frame. Battery percent is computed locally
from voltage using a per-model curve (see section 7). The `mode` and `light state`
asked for by the protocol brief do not exist as readback fields on Veteran; the
firmware only accepts write commands for those (see section 6).

### Speed and current sign

The hardware reports the absolute value on some firmwares. WheelLog exposes a
"GotwayNegative" preference with three options:

| Setting | Effect |
|---------|--------|
| 0 | force unsigned (`abs(speed)`, `abs(phaseCurrent)`) |
| 1 | use the i16 sign as-is |
| -1 | invert the sign (some hardware revisions report inverted) |

Recommend exposing the same toggle in the app, default 1.

### Word-swapped 32-bit fields

The trip and total distance encoding is unusual. Given bytes `b0 b1 b2 b3` at the
field offset, the value is:

```
value = (b2 << 24) | (b3 << 16) | (b0 << 8) | b1
```

Effectively two big-endian u16s where the high word is second and the low word is
first. This is a quirk of the wheel's serializer; treat it as a fixed encoding.

## 5. BMS frame (Lynx, Lynx S, Sherman L, Oryx, Patton family)

Wheels with smart-BMS firmware (`model >= 5`, plus some earlier Patton firmwares)
also emit longer frames (`LEN > 38`) interleaved with the standard telemetry frame.
These carry per-cell voltages and BMS temperatures. Each long frame has a tag byte
at offset 46 (`pnum`) that tells you which slice you got.

| `pnum` | Slice | Layload |
|-------:|-------|---------|
| 0, 4   | header | BMS pack currents at offsets 69 and 71 (each i16 BE / 100 = amps) |
| 1, 5   | cells 0..14  | 15 i16 BE values starting at offset 53, each / 1000 = volts per cell |
| 2, 6   | cells 15..29 | 15 u16 BE values starting at offset 53, each / 1000 = volts per cell |
| 3, 7   | cells 30..41 + temps | up to 12 cells starting at offset 59. Six temps at offsets 47, 49, 51, 53, 55, 57 (each i16 BE / 100 = degrees C) |
| 8      | reserved | newer packet type, contents not yet decoded |

Even values of `pnum` belong to BMS pack 1, odd values to BMS pack 2 (`pnum < 4` is
pack 1, `pnum >= 4` is pack 2 on dual-BMS wheels).

Cell counts per model:

| Model              | Cells |
|--------------------|------:|
| Sherman, Abrams, Sherman S | 24 |
| Patton, Patton S, Nosfet Aero | 30 |
| Lynx, Lynx S, Sherman L, Nosfet Apex, Nosfet Aeon | 36 |
| Oryx               | 42 |

## 6. Control commands

Commands are ASCII strings written to the notify/write characteristic, except for
the v3+ horn which is a 14-byte binary blob. Bytes go out as one BLE write each.

| Command          | Bytes (hex)                                                  | ASCII / Note |
|------------------|--------------------------------------------------------------|--------------|
| Beep (firmware model < 3) | `62`                                              | `b` |
| Beep (firmware model >= 3) | `4C 6B 41 70 0E 00 80 80 80 01 CA 87 E6 6F`       | binary, opaque magic. Sherman S and newer. |
| Light on         | `53 65 74 4C 69 67 68 74 4F 4E`                              | `SetLightON` (10 bytes) |
| Light off        | `53 65 74 4C 69 67 68 74 4F 46 46`                           | `SetLightOFF` (11 bytes) |
| Pedals hard      | `53 45 54 68`                                                | `SETh` |
| Pedals medium    | `53 45 54 6D`                                                | `SETm` |
| Pedals soft      | `53 45 54 73`                                                | `SETs` |
| Reset trip meter | `43 4C 45 41 52 4D 45 54 45 52`                              | `CLEARMETER` |

Notes:

- There are no documented commands for setting max speed, alarm speed, lock, or
  volume. The wheel's settings app on iOS / Android writes those, but their wire
  format has not been publicly reverse-engineered. Treat these as read-only fields.
- Pedals mode write echoes back at offset 30 of the next telemetry frame, so use
  that as a confirmation read.
- Light state has no readback. Track it locally after each write.
- `b` (single ASCII byte) is also used by Begode. It is safe to send to a Veteran
  with `model < 3` but ignored on Sherman S and newer; use the binary blob there.

## 7. Per-model differences

The firmware encodes the model in the `version` field at offsets 28..29:

```
ver = u16 BE
model = ver / 1000
minor = (ver % 1000) / 100
revision = ver % 100
```

| `model` | Wheel       | Cells | Pack voltage range (raw centivolts, 0..100%) |
|--------:|-------------|------:|----------------------------------------------|
| 0, 1    | Sherman / Sherman Max | 24 | 7935 .. 9870 |
| 2       | Abrams      | 24    | 7935 .. 9870 |
| 3       | Sherman S   | 24    | 7935 .. 9870 |
| 4       | Patton      | 30    | 9918 .. 12337 |
| 5       | Lynx        | 36    | 11902 .. 14805 |
| 6       | Sherman L   | 36    | 11902 .. 14805 |
| 7       | Patton S    | 30    | 9918 .. 12337 |
| 8       | Oryx        | 42    | 13886 .. 17272 |
| 9       | Lynx S      | 36    | 11902 .. 14805 |
| 42      | Nosfet Apex | 36    | 11902 .. 14805 |
| 43      | Nosfet Aero | 30    | 9918 .. 12337 |
| 44      | Nosfet Aeon | 36    | 11902 .. 14805 |

Battery percent (linear curve, simple variant):

```
if voltage <= V_min: percent = 0
else if voltage >= V_max: percent = 100
else: percent = round((voltage - V_min) / ((V_max - V_min) / 100))
```

Concrete divisor per family (kept as the original constants for fidelity):

| Family   | Formula |
|----------|---------|
| Sherman / Abrams / Sherman S | `round((v - 7935) / 19.5)` |
| Patton family                | `round((v - 9918) / 24.2)` |
| Lynx family / Sherman L      | `round((v - 11902) / 29.03)` |
| Oryx                         | `round((v - 13886) / 34.125)` |

A "better percents" non-linear variant exists in WheelLog that bends the curve at
roughly 90% to better approximate cell SoC; for a v0.x app, the linear version is
fine and matches the wheel's own LED display closer.

Other per-model behavior:

- `model >= 2` reports valid hardware PWM at offsets 34..35. For `model <= 1` ignore
  the field and compute PWM from speed + current locally.
- `model >= 5` (Lynx and friends) emit smart-BMS frames (section 5).
- `model < 3` accepts the legacy single-byte horn `b`. `model >= 3` requires the
  binary horn blob.
- Sherman Max is not a distinct model id. Field reports place it under `model = 0`
  or `model = 1` depending on firmware version. Display "Sherman" or "Sherman Max"
  using the wheel's reported name, not the model byte.
- Veteran does not expose ride-mode (rookie / intermediate / strict) over BLE; only
  pedals stiffness (h / m / s).

## 8. Capability summary

For our `WheelCapabilities` record:

| Capability       | Veteran value | Notes |
|------------------|---------------|-------|
| `hasHorn`        | true          | `b` or 14-byte blob (per model) |
| `hasLight`       | true          | `SetLightON` / `SetLightOFF` |
| `hasLock`        | false         | not exposed over BLE |
| `hasMaxSpeed`    | read-only     | values at offsets 24, 26 are read-only over BLE |
| `hasAlarmSpeed`  | read-only     | same as above |
| `hasVolume`      | false         | no command known |
| `hasDRL`         | false         | no separate DRL command |
| `needsAuthForLock` | n/a         | lock not supported |

Additional booleans worth tracking:

| Capability       | Value         | Notes |
|------------------|---------------|-------|
| `hasPedalsMode`  | true          | `SETh` / `SETm` / `SETs` |
| `hasResetTrip`   | true          | `CLEARMETER` |
| `hasPwmReadback` | model >= 2    | offsets 34..35 |
| `hasSmartBms`    | model >= 5    | per-cell voltages and BMS temps |
| `signedSpeed`    | configurable  | hardware varies, expose user toggle |

## 9. Open questions

- Does any firmware accept a write command to change max-speed, tiltback, or alarm
  thresholds? Plausibly yes (the official iOS app sets them) but the wire format is
  not publicly documented. Capture from the official app needed.
- The 14-byte horn blob for `model >= 3`: meaning of bytes 4..13 not understood.
  Possibly a session-randomized auth tag or a feature negotiation. Replay works in
  practice; treat as opaque.
- `pnum == 8` smart-BMS frame: unrecognized in current research, may carry charge
  cycles or balancer status.
- Older Sherman firmwares (pre-2020) reportedly used a shorter 24-byte payload
  without offsets 28..35 populated. If you see `LEN < 38`, fall back to: parse
  voltage / speed / distance / current / temp only and treat `model = 0`.
- Negative-current convention: some firmwares emit unsigned current and use a
  separate flag elsewhere; others emit i16 directly. Field-test required.
- "Mode" and "light state" are not in the readback frame; verify on real hardware
  whether any byte we currently treat as zero (e.g. offsets 36..37 in a 38-byte
  payload, or a reserved BMS slot) carries it on newer firmware.

## 10. Attribution

This document was reconstructed from publicly available open-source research:

- Primary source: WheelLog Android (`Wheellog/wheellog.android` on GitHub), GPLv3,
  by Ilya Shkolnik and contributors. The `VeteranAdapter.java` and
  `GotwayVirtualAdapter.java` files were the main references for frame layout, byte
  offsets, scale factors, command bytes, and per-model voltage curves.
- Secondary: WheelLog wiki and the Electric Unicycle Forum LeaperKim threads for
  cross-checking battery voltage ranges and model identifiers.

No code was copied. All offsets, formulas, and command bytes here are restated in
this project's idiom (`u8`, `u16 BE`, `i16 BE`, `u32 word-swapped`).

If this document is redistributed, please keep this attribution intact.
