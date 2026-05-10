# KingSong BLE Protocol

Protocol reference for KingSong electric unicycles (KS series and S/F series).
Implementation target: Kotlin parser and command builder in EUC Planet.

This document describes the wire format only. No code is reproduced from
upstream projects. See "Attribution" at the end of the file for sources.

Status: research-grade. Several offsets and command effects vary by
firmware. Items marked "Unconfirmed" should be validated against a labelled
BLE capture before being relied on in production code.


## 1. GATT profile

| Role | UUID |
| --- | --- |
| Service | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| Notify (wheel -> phone) | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| Write (phone -> wheel) | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` |

Notes:
- KingSong uses a single characteristic `0xFFE1` for both notify and write.
  Subscribe to notifications via the standard CCCD, then issue write commands
  to the same characteristic handle.
- Some F22 Pro firmwares send extended BMS frames over multiple notify
  packets that exceed the default 23-byte ATT MTU; request a larger MTU
  (e.g. `247`) on connect to receive `0xD0` BMS payloads intact.


## 2. BLE name pattern

Advertised name formats observed in the wild:

| Pattern | Example | Notes |
| --- | --- | --- |
| `KS-<model>` | `KS-S22`, `KS-S18`, `KS-16X`, `KS-F22P` | Most KingSong wheels |
| `KS-<model>-<suffix>` | `KS-S22-XXXX` | Some firmwares append a serial suffix |
| `RW` (BT name) or `ROCKW...` (advertised) | `ROCKW...` | Rockwheel hardware that speaks the KingSong protocol; should be routed to this adapter |

Detection strategy:
1. Probe for service `0xFFE0` containing characteristic `0xFFE1`.
2. If the device name starts with `KS-` or matches the Rockwheel patterns,
   treat as KingSong.
3. After connect, send the name request (command `0x9B`, see section 6) and
   parse the model string from the reply (see section 4, frame `0xBB`).


## 3. Frame structure

KingSong frames are fixed-length 20-byte BLE notifications and writes.

```
offset  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19
        AA 55 <-------------- payload (14 bytes) -------> TT 14 5A 5A
```

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | u8 magic[0] | `0xAA` |
| 1 | u8 magic[1] | `0x55` |
| 2..15 | 14 bytes | Type-specific payload |
| 16 | u8 type | Frame type / command code (see sections 4 and 6) |
| 17 | u8 trailer[0] | Usually `0x14`. For some replies it carries a sub-page index (e.g. BMS) |
| 18 | u8 trailer[1] | `0x5A` |
| 19 | u8 trailer[2] | `0x5A` |

Byte order:
- 16-bit fields use **u16 LE / i16 LE** as noted per field.
- The 32-bit total-distance field uses an unusual **word-swapped LE32**:
  the 16-bit halves are stored most-significant-half first, and each half
  is itself little-endian. See section 4.

Checksum: **none**. There is no CRC or sum byte. Frames are validated by
matching the magic header `0xAA 0x55` and the trailer pair at offsets 18
and 19 (`0x5A 0x5A`). Some implementations also check `data[17] == 0x14`
on writes; replies vary.

Caveat on the trailer: forum write-ups occasionally describe the trailer as
"`5A 5A 5A 5A`". Public adapter source consistently uses `0x14 0x5A 0x5A`
at offsets 17..19 for outbound commands. Prefer the 3-byte form.


## 4. Inbound frames (wheel -> phone)

The frame type byte at offset 16 selects the payload layout.

### 4.1 Live telemetry, type `0xA9`

Sent continuously by the wheel.

| Offset | Bytes | Type | Field | Units / scale |
| --- | --- | --- | --- | --- |
| 2..3 | 2 | u16 LE | voltage | hundredths of a volt (raw / 100 = V) |
| 4..5 | 2 | u16 LE | speed | hundredths of km/h (raw / 100 = km/h). See note on signedness. |
| 6..9 | 4 | word-swapped LE32 | total distance since power-on, or total mileage (varies, see open questions) | meters |
| 10..11 | 2 | i16 LE | current | hundredths of an amp; signed (negative = regen) |
| 12..13 | 2 | i16 LE | temperature | raw / 340 + 36.53 in some firmwares; raw / 100 in others. Unconfirmed; firmware-dependent. |
| 14 | 1 | u8 | mode | Numeric pedal mode index. Only valid when offset 15 equals `0xE0`. |
| 15 | 1 | u8 | mode-valid sentinel | `0xE0` means the `mode` byte is meaningful in this packet. |
| 16 | 1 | u8 | type | `0xA9` |

Notes:
- "Word-swapped LE32" means: given input bytes `[b6, b7, b8, b9]`, the
  decoded value is `(b7 << 24) | (b6 << 16) | (b9 << 8) | b8`. This is what
  the public reference parser computes; it is NOT a standard LE u32. Treat
  carefully when implementing.
- Speed has been reported as unsigned in all observed captures. Reverse
  motion is not currently flagged in the live frame on KingSong; the wheel
  reports magnitude only. Unconfirmed for S22 firmwares with the new
  sensorless reverse mode.
- Battery percent is **not** transmitted by the wheel. Apps compute it
  client-side from voltage using a per-pack-class lookup. See section 4.5.
- Light state, lock state, and alarm-active state are **not present** in
  the live telemetry frame on documented firmwares. Light state is sent
  back via the settings frame after a write; lock state is not exposed at
  all over BLE in the public adapter (see section 8 and section 9).

### 4.2 Trip / second-temperature frame, type `0xB9`

| Offset | Bytes | Type | Field | Units / scale |
| --- | --- | --- | --- | --- |
| 2..5 | 4 | word-swapped LE32 | trip distance (since power-on) | meters |
| 8..9 | 2 | u16 LE | top speed (this trip) | hundredths of km/h |
| 12 | 1 | u8 | fan status | `0` = off, `1` = on |
| 13 | 1 | u8 | charging status | `0` = not charging, non-zero = charging |
| 14..15 | 2 | i16 LE | temperature 2 (board / motor depending on model) | same scale as offset 12..13 in `0xA9` |
| 16 | 1 | u8 | type | `0xB9` |

### 4.3 Name / model / version, type `0xBB`

| Offset | Bytes | Type | Field |
| --- | --- | --- | --- |
| 2..15 | up to 14 | ASCII, NUL-terminated | model string e.g. `KS-S22-141` |
| 16 | 1 | u8 | type `0xBB` |

Parsing rule (per the public reference):
- Treat the ASCII payload as `<model>-<version_int>`.
- The version is the final dash-separated token, parsed as integer and
  divided by `100.0`. Example: `KS-S22-141` -> model `KS-S22`, version `1.41`.

### 4.4 Serial number, type `0xB3`

| Offset | Bytes | Type | Field |
| --- | --- | --- | --- |
| 2..15 | 14 | ASCII | serial bytes 0..13 |
| 17..19 | 3 | ASCII | serial bytes 14..16 (re-using trailer slots) |
| 16 | 1 | u8 | type `0xB3` |

Implementation note: bytes 14..16 of the serial overlap the trailer region.
The reference implementation assembles the 17-character serial from
`payload[2..15] + frame[17..19]`, which means inbound `0xB3` frames do NOT
follow the standard `0x14 0x5A 0x5A` trailer convention. Validate inbound
frames by header magic only when the type is `0xB3`.

### 4.5 Battery percent (computed client-side)

The wheel does not transmit battery percent. The app classifies the wheel
into a voltage class by model string and applies a piecewise-linear curve
from voltage to percent.

Voltage classes used by the public reference:

| Class | Members (model strings) | Nominal pack |
| --- | --- | --- |
| 84 V | `KS-18L`, `KS-18LH`, `KS-18LY`, `KS-16X`, `KS-16XF`, `KS-S16`, `KS-S16P`, `KS-S18`, plus Rockwheel (`RW`, `ROCKW...`) | 20S |
| 100 V | `KS-S19` | 24S |
| 126 V | `KS-S20`, `KS-S22` | 30S |
| 151 V | `KS-F18P` | 36S |
| 176 V | `KS-F22P` | 42S |
| 67.2 V | (default fallback for older KS-14 and KS-16 family without S/F) | 16S |

Suggested mapping for our parser: store a struct `{ minV, maxV, curve }`
keyed by model string, then linear-interpolate between known break points.
Values from the public reference are documented in section 7.

### 4.6 CPU and PWM, type `0xF5`

| Offset | Bytes | Type | Field | Units |
| --- | --- | --- | --- | --- |
| 14 | 1 | u8 | CPU load | percent |
| 15 | 1 | u8 | output level | percent (multiply by 100 to get a 0..10000 PWM tick value matching other apps' "PWM*100" convention) |
| 16 | 1 | u8 | type | `0xF5` |

### 4.7 Speed limit, type `0xF6`

| Offset | Bytes | Type | Field | Units |
| --- | --- | --- | --- | --- |
| 2..3 | 2 | u16 LE | dynamic speed limit (current PWM-derived ceiling) | hundredths of km/h |
| 16 | 1 | u8 | type | `0xF6` |

### 4.8 Alarms and max-speed reply, types `0xA4` and `0xB5`

| Offset | Bytes | Type | Field |
| --- | --- | --- | --- |
| 4 | 1 | u8 | alarm 1 speed (km/h) |
| 6 | 1 | u8 | alarm 2 speed (km/h) |
| 8 | 1 | u8 | alarm 3 speed (km/h) |
| 10 | 1 | u8 | wheel max speed / tiltback (km/h) |
| 16 | 1 | u8 | type `0xA4` (settings push) or `0xB5` (settings reply) |

Behaviour: when the wheel emits `0xA4` it expects the app to echo the same
20 bytes back with offset 16 changed to `0x98`. This is a handshake: the
public reference does that automatically on receipt. `0xB5` is the same
layout but does not require an echo.

### 4.9 BMS frames, types `0xF1` (BMS1) and `0xF2` (BMS2)

Multi-page frames; the page index is at offset 17. For models with the
extended BMS, the wheel sends page `0xD0` with a variable-length payload.

| Page | Layout summary |
| --- | --- |
| `0x00` | u16 LE voltage `/100` V at 2..3, u16 LE current `/100` A at 4..5, u16 LE remaining capacity `*10` mAh at 6..7, u16 LE factory capacity `*10` mAh at 8..9, u16 LE full cycles at 10..11 |
| `0x01` | Seven u16 LE temperatures at 2..15, each `(raw - 2730) / 10.0` celsius (T1..T6 and MOS) |
| `0x02..0x06` | Cell voltages, seven cells per page, each u16 LE `/1000` volts, packed at offsets 2, 4, 6, 8, 10, 12, 14. Page `0x06` only has cells 28..29 plus the env-MOS temperature at offset 10. |
| `0xD0` | Extended layout: cell count at offset 21, cell voltages start at offset 22 (u16 LE `/1000`), then a temperature block, then current, voltage, remaining percent, full cycles, factory capacity, and two ambient temperatures. Length-prefixed via offset 15. Layout is firmware-specific; see open questions. |

Page selection details follow the same convention used by Smart BMS in
other EUCs and are not load-bearing for ride-time telemetry. Implement
pages `0x00`, `0x01`, and the cell pages first; treat `0xD0` as a stretch
goal.

### 4.10 BMS serial (`0xE1`/`0xE2`) and firmware (`0xE5`/`0xE6`)

Same layout as the wheel serial frame `0xB3`: 14 ASCII bytes at offsets
2..15 plus 3 more at offsets 17..19. `0xE1`/`0xE5` is BMS1, `0xE2`/`0xE6`
is BMS2.


## 5. Settings frames

KingSong does not send a single dedicated "settings dump" frame; instead
each settable parameter rides on its own type. The four ride-relevant
settings come back together in the `0xA4`/`0xB5` frame above:

| Field | Source |
| --- | --- |
| Tiltback / max speed | `0xA4`/`0xB5` offset 10 |
| Alarm 1 / 2 / 3 | `0xA4`/`0xB5` offsets 4 / 6 / 8 |
| Pedal mode | last `0xA9` frame, byte 14, valid only when byte 15 == `0xE0` |
| LED mode | not echoed back over BLE in public reference |
| Strobe mode | not echoed back over BLE in public reference |
| Headlight on/off/auto | not echoed back; app tracks own state |

To read the current alarm and tiltback values, send command `0x98` (see
section 6). The wheel replies with a `0xA4` or `0xB5` frame.


## 6. Outbound commands (phone -> wheel)

All commands are 20-byte frames following section 3. Bytes not listed are
zero. The trailer is `0x14 0x5A 0x5A` at offsets 17..19 unless noted.

Compact reference:

| Command | Offset 16 | Other bytes | Effect |
| --- | --- | --- | --- |
| Beep / horn | `0x88` | (none) | Single beep |
| Light mode set | `0x73` | `data[2] = mode + 0x12`, `data[3] = 0x01` | Sets headlight mode (off / on / auto). See note. |
| LED mode set | `0x6C` | `data[2] = mode` | Sets decorative LED program (model-dependent) |
| Strobe mode set | `0x53` | `data[2] = mode` | Sets strobe pattern |
| Pedal mode set | `0x87` | `data[2] = mode (0..2)`, `data[3] = 0xE0`, `data[17] = 0x15` | Sets ride mode (soft / medium / hard); note non-standard `data[17]` |
| Wheel calibration | `0x89` | (none) | Triggers gyro calibration. Only safe when wheel is on its side. |
| Power off | `0x40` | (none) | Powers the wheel off |
| Set max speed + alarms | `0x85` | `data[2] = alarm1`, `data[4] = alarm2`, `data[6] = alarm3`, `data[8] = max` (all in km/h) | Writes all four speed settings in one frame |
| Read max speed + alarms | `0x98` | (none) | Wheel replies with `0xA4` (or `0xB5` on later firmwares) |
| Request name / model | `0x9B` | (none) | Wheel replies with `0xBB` |
| Request serial number | `0x63` | (none) | Wheel replies with `0xB3` |
| Charge limit | `0x8A` | `data[2] = 0x09`, `data[4] = percent` | Sets charge cutoff (e.g. 80, 90, 100) |
| Standby delay | `0x3F` | `data[2] = 0x01`, `data[4..5] = u16 LE seconds` | Idle auto-poweroff (e.g. 3600 = 60 minutes) |
| Gyro switch-off angle | `0x8A` | `data[2] = 0x03`, `data[4..5] = u16 LE tenths-of-degree` | Pedal-cutoff lean angle, e.g. `501` = 50.1 deg |
| Gyro front trim | `0x8A` | `data[2] = 0x01`, `data[4..5] = i16 LE tenths-of-degree` | Pedal pitch trim, e.g. `-32` = -3.2 deg |
| BMS1 serial request | `0xE1` | `data[17..19] = 0x00` | Reply on type `0xE1` |
| BMS2 serial request | `0xE2` | `data[17..19] = 0x00` | Reply on type `0xE2` |
| BMS1 firmware request | `0xE5` | `data[17..19] = 0x00` | Reply on type `0xE5` |
| BMS2 firmware request | `0xE6` | `data[17..19] = 0x00` | Reply on type `0xE6` |
| BMS1 deep data | `0xE3` | `data[17..19] = 0x00` | Triggers `0xF1` page run |
| BMS2 deep data | `0xE4` | `data[17..19] = 0x00` | Triggers `0xF2` page run |

Notes:
- Light mode `0` = off, `1` = on, `2` = auto. The public reference encodes
  this by writing `mode + 0x12` to `data[2]` (so `0x12`/`0x13`/`0x14`).
- Pedal mode values are nominally `0` = soft, `1` = medium, `2` = hard
  (KingSong calls these "Soft", "Medium", "Hard" or sometimes "Comfort",
  "Standard", "Sport" in different firmware UIs). Confirm against the
  on-wheel announcement when changing.
- The public reference does NOT implement a horn-on / horn-off pair;
  `0x88` triggers a single short beep.

Example hex dumps (full 20-byte frames):

```
Beep:
  AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 88 14 5A 5A

Headlight ON (mode = 1):
  AA 55 13 01 00 00 00 00 00 00 00 00 00 00 00 00 73 14 5A 5A

Pedal mode = 2 (hardest):
  AA 55 02 E0 00 00 00 00 00 00 00 00 00 00 00 00 87 15 5A 5A
                                                       ^^ note non-standard

Set tiltback 50, alarms 35/40/45:
  AA 55 23 00 28 00 2D 00 32 00 00 00 00 00 00 00 85 14 5A 5A
        |a1|   |a2|   |a3|   |max|

Read tiltback / alarms:
  AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 98 14 5A 5A

Power off:
  AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 14 5A 5A
```


## 7. Per-model quirks

| Model | Quirk |
| --- | --- |
| `KS-18L` | When the wheel reports distance in miles internally, the app may need to multiply distance fields by `0.83` (legacy "18L scaler"). User-toggleable; default is metric. |
| `KS-16X`, `KS-16XF` | 84 V class, treat like 18L for battery curve. |
| `KS-S16`, `KS-S16P`, `KS-S18` | 84 V class. |
| `KS-S19` | 100 V class. Confirm cell count = 24S. |
| `KS-S20`, `KS-S22` | 126 V class, 30S. S22 ships with newer firmware that emits the extended BMS frame `0xD0`; request a larger MTU. |
| `KS-F18P` | 151 V, 36S. Extended BMS. |
| `KS-F22P` | 176 V, 42S. Extended BMS. |
| Rockwheel (`RW`, `ROCKW...`) | Speaks the KingSong wire format closely enough that WheelLog routes it through the KingSong adapter. Voltage curve treated as 84 V. |
| Older `KS-14` / `KS-16` (non-S/F) | 67.2 V / 16S. No extended BMS, no `0xF5`/`0xF6` on the oldest firmwares. |

S20 (mentioned in the task) does NOT have its own model code distinct from
the string "KS-S20". KingSong does not currently ship a wheel called "Lynx"
(that name belongs to a Begode wheel); the `KS-S20` reports as `KS-S20`.

Wheel "model ID" exposed over BLE is the ASCII model string in frame
`0xBB`, not a uint16. There is no numeric model code in the documented
KingSong protocol, contrary to what some forum posts suggest. If a
firmware does carry a numeric ID we have not seen it.


## 8. Capability summary

For our `WheelCapabilities` struct, KingSong (any current model) maps to:

| Field | Value | Notes |
| --- | --- | --- |
| `hasHorn` | true | Single beep via `0x88` |
| `hasLight` | true | `0x73` + mode byte; off / on / auto |
| `hasLock` | false | No documented lock command in the public protocol. Some KS firmwares expose lock through the official app's password feature, but it is not exposed over BLE in any open-source adapter. Treat as unsupported. |
| `hasMaxSpeed` | true | `0x85` byte 8 (km/h) |
| `hasAlarmSpeed` | true | `0x85` bytes 2 / 4 / 6, three independent alarms |
| `hasVolume` | false | Not exposed over BLE; horn volume is fixed in hardware |
| `hasDRL` | partial | DRL behaviour rides on the headlight mode `0x73` (`mode 2` = auto). No separate DRL channel. |
| `needsAuthForLock` | n/a | Lock not supported (see hasLock). |

Per task instructions: KingSong does not require auth for lock/unlock,
which is consistent with the public protocol exposing no lock at all.


## 9. Open questions and uncertainty

These items are NOT settled and should be confirmed with a labelled
capture from a known-good wheel before being relied on.

1. **Total distance vs trip distance**. The `0xA9` field at offsets 6..9
   is described as "total distance" in the public reference but its value
   resets across power cycles in some forum captures. It may actually be
   trip distance for newer firmwares, with cumulative mileage moved to
   `0xB9` or a separate poll.
2. **Word-swapped LE32**. The decoder for the 4-byte distance field
   reverses each adjacent byte pair before reading big-endian. This is
   either a deliberate quirk of the wheel (storing two 16-bit halves with
   the high half first) or a workaround in WheelLog that has stuck
   around. If a labelled capture shows that a plain LE u32 read gives
   correct meters, switch to plain LE u32.
3. **Speed signedness**. The `0xA9` speed field is read as signed Int16
   in the reference but no observed capture has shown a negative value.
   We do not know whether reverse motion on S22 reports as negative or
   as zero.
4. **Temperature scale**. WheelLog uses a single divisor across all KS
   models, but Veteran and Begode adapters use the MPU-6500 raw formula
   `(raw / 340.0) + 36.53`. KingSong almost certainly uses one of these
   two; pick by experiment per model.
5. **Pedal mode numeric mapping**. `0..2` is consistent across captures
   but we have not confirmed that S22 firmware uses the same indices as
   18L. Newer KS firmwares may have added a fourth mode.
6. **Lock / password feature**. The KingSong official app supports a
   numeric lock password. We have no public documentation showing this
   travels over the public `0xFFE0/0xFFE1` channel; it may use a hidden
   service or be enforced server-side in the app. Mark as not
   implementable from public sources.
7. **Mode-valid sentinel `0xE0`**. Other adapters treat the same byte as
   a generic "ride state" with multiple values (0xE0 normal, others for
   tiltback / pedal cutoff). We currently only special-case `0xE0`.
8. **Light mode encoding**. The `mode + 0x12` offset (so writes are
   `0x12`/`0x13`/`0x14` for off/on/auto) is suspicious; it may actually
   be a multi-purpose register where the high nibble selects the
   sub-command and the low nibble carries the value.
9. **Trailer**. Documented as `0x14 0x5A 0x5A` for outbound writes, but
   inbound `0xB3`/`0xE1`/`0xE2`/`0xE5`/`0xE6` use the trailer slots for
   ASCII payload bytes. Do not validate trailer on inbound frames whose
   type is one of those four; do validate trailer on outbound writes.
10. **Charge limit on older firmwares**. Command `0x8A` with `data[2] =
    0x09` is documented for newer S/F series. Older KS-18L firmwares may
    silently ignore or interpret this as a different sub-command.


## 10. Attribution

Primary protocol research: the WheelLog Android project, originally by
Andrey Cooper / palachzzz and currently maintained at
https://github.com/Wheellog/Wheellog.Android (GPLv3). The KingSong frame
layout, command bytes, and BMS pagination documented here were
established by reading that codebase, in particular the
`KingsongAdapter` class and adjacent constants.

Additional context (model lists, voltage classes, Rockwheel routing) was
cross-checked against:

- WheelLog wiki at https://github.com/Wheellog/Wheellog.Android/wiki
- Long-running Electric Unicycle Forum thread "Gotway/Kingsong protocol
  reverse-engineering" (forum.electricunicycle.org topic 870)
- DarknessBot / EUC World public blog posts on KS frame format
- The KingSong S22 user manual for the user-visible setting names

Original protocol reverse engineering by Ilya Shkolnik and other
WheelLog contributors. This document re-describes the protocol in our
own words and idiom; no source code has been copied.
