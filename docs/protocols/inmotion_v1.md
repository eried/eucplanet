# InMotion V1 BLE protocol

Reference for parser and command builder implementation. Covers the older
InMotion family that pre-dates the V14 / V13 / V12 / V11 / P6 wire format
(known here as "InMotion V2", in `inmotion_v2.md`).

In-scope models: V8, V8F, V8S, V10, V10F, V10S, V10SF, V10T, V10FT,
V5 / V5F / V5PLUS / V5D, L6, Lively, Glide 3 (Solowheel rebrand of V8),
plus the older R-series (R1, R10, R2, R0) and V3 generation that share
the framing. Production wheels still seen on this protocol are V8 / V8F
and the V10 line; everything else is legacy.

Original prose. Source code from WheelLog (GPLv3) was read for research;
no code copied. See Attribution at the end. Status: research-grade -
CAN-over-BLE framing and the `0x0F550113` fast info ID are solid, but
slow-info offsets and per-model shifts are firmware-dependent. Flagged.


## 1. GATT profile

InMotion V1 advertises **two** proprietary services. Notifications come on
one service, writes go to the other.

| Role | UUID (128-bit) | Short |
| --- | --- | --- |
| Notify service | `0000ffe0-0000-1000-8000-00805f9b34fb` | 0xFFE0 |
| Notify characteristic (wheel -> phone) | `0000ffe4-0000-1000-8000-00805f9b34fb` | 0xFFE4 |
| Write service | `0000ffe5-0000-1000-8000-00805f9b34fb` | 0xFFE5 |
| Write characteristic (phone -> wheel) | `0000ffe9-0000-1000-8000-00805f9b34fb` | 0xFFE9 |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` | standard |

Notes:
- This is **not** the same shape as V2. V2 (V14 etc.) uses Nordic UART
  `6e400001/02/03`, single service. V1 uses the older HM-10-style
  `0xFFEx` set split across two services, with read and write on
  different characteristics.
- KingSong / Begode / Veteran adapters also use UUIDs in the `0xFFE0`
  family. Disambiguate by BLE name and by the magic header on the
  first frames received (see section 3).
- Wheels also expose standard `0x180A` (Device Info) and `0x180F`
  (Battery), plus several unused legacy services (`0xFFA0`, `0xFFB0`,
  `0xFFC0`, `0xFFD0`, `0xFF90`, `0xFE00`, `0xFC60`). A parser does
  not need to discover those.

Procedure on connect: discover services (confirm `0xFFE0` + `0xFFE5`),
subscribe to `0xFFE4` (CCCD `0x01 0x00`), send PIN if required (section
7), send `GetSlowInfo` to learn the model code, then run a `GetFastInfo`
keep-alive loop.


## 2. BLE name pattern

Advertised names observed:

| Pattern | Examples | Notes |
| --- | --- | --- |
| `V<digits>` or `V<digits><suffix>` | `V8`, `V8F`, `V10`, `V10F`, `V10F-AB12` | Most common on V8 / V10 generation |
| `Inmotion-<...>` | `Inmotion-V8`, `Inmotion-V10F` | Some firmwares prepend the brand |
| `IM<digits>` | `IM01234`, `IM987654` | Older R-series and some rebrands |
| `L6-<...>` | `L6-XXXX` | L6 model |
| `Solowheel <...>` or `Glide<...>` | `Glide 3`, `Solowheel Glide-3` | Solowheel-branded V8 hardware |

Detection strategy:
1. Probe for service `0xFFE0` containing notify characteristic `0xFFE4`
   AND service `0xFFE5` containing write characteristic `0xFFE9`.
2. If only `0xFFE0` + `0xFFE1` are present, the device is KingSong /
   Begode / Veteran, not InMotion V1.
3. The advertised name alone is not authoritative. After connect, send
   `GetSlowInfo` and read back the model code from the response (section 5).


## 3. Frame structure

V1 wraps an 8-byte CAN-bus frame in a BLE envelope with byte stuffing.

```
offset  0  1   2 ... N-3   N-3   N-2 N-1
        AA AA  <escaped CAN frame>  CK  55 55
```

| Field | Size | Meaning |
| --- | --- | --- |
| Header | 2 bytes | `0xAA 0xAA` literal start-of-frame |
| Body | variable | Escaped CAN frame, see below |
| Checksum | 1 byte | `sum(unescaped CAN bytes) mod 256` |
| Trailer | 2 bytes | `0x55 0x55` literal end-of-frame |

### 3.1 Byte stuffing

The bytes `0xAA`, `0x55` and `0xA5` cannot appear unescaped inside the
body or checksum. Each occurrence is prefixed with `0xA5`.

| Raw byte | On the wire |
| --- | --- |
| `0xAA` | `0xA5 0xAA` |
| `0x55` | `0xA5 0x55` |
| `0xA5` | `0xA5 0xA5` |
| any other | unchanged |

The checksum byte itself is escaped on the same rules. The header and
trailer pairs are always literal `0xAA 0xAA` and `0x55 0x55` and are
written without escaping.

### 3.2 CAN frame layout (unescaped)

Every V1 BLE packet carries a fixed 16-byte CAN prefix, optionally
followed by an extended payload:

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | u32 LE | CAN ID | 29-bit extended CAN ID, see section 3.3 |
| 4 | 8 bytes | data | CAN payload; semantics depend on ID |
| 12 | u8 | len | `8` for normal frames, `0xFE` for extended frames |
| 13 | u8 | ch | Channel; `5` for all phone-to-wheel commands |
| 14 | u8 | format | `0` = standard, `1` = extended (29-bit ID) |
| 15 | u8 | type | `0` = data frame, `1` = remote (request) frame |

When `len == 0xFE`, the frame is an **extended** packet: the 8 data
bytes at offset 4-11 are reinterpreted as a header, and additional
payload follows the 16-byte prefix.

### 3.3 Extended payload

For extended frames (`len == 0xFE`): the standard 16-byte CAN prefix is
followed by `L` payload bytes, where `L` is `u32 LE` written at offset
4-7 of the prefix (i.e. inside the `data` slot). Total wire length is
`16 + L` unescaped bytes plus framing.

Fast-info replies (`0x0F550113`) ship ~76 bytes (offsets up to 76
stable; some firmwares ship 80+). Slow-info replies (`0x0F550114`)
ship 132-144 bytes; offsets above 124 are optional and firmware-
dependent.

### 3.4 CAN IDs used by V1

All IDs are 32-bit values written little-endian at offset 0 of the CAN
frame. The high byte distinguishes telemetry (`0x0F`) from alerts
(`0x0F78...`). Only the IDs below are exchanged in normal operation.

| ID | Direction | Meaning |
| --- | --- | --- |
| `0x0F550113` | both | Fast info: phone requests, wheel replies (extended) |
| `0x0F550114` | both | Slow info / settings dump: phone requests, wheel replies (extended) |
| `0x0F550115` | phone -> wheel, ack back | Ride-mode group: max speed, classic/comfort, pedal sensitivity, tilt |
| `0x0F550116` | phone -> wheel, ack back | Remote control group: LED, beep, power off |
| `0x0F550119` | phone -> wheel, ack back | Wheel calibration |
| `0x0F55010D` | phone -> wheel, ack back | Headlight on/off |
| `0x0F55012E` | phone -> wheel, ack back | Handle / lift sensor button |
| `0x0F550609` | phone -> wheel, ack back | Play sound by index |
| `0x0F55060A` | phone -> wheel, ack back | Speaker volume |
| `0x0F550307` | phone -> wheel, ack back | PIN / password |
| `0x0F780101` | wheel -> phone | Asynchronous alert (tiltback, low battery, etc.) |

Acks for command IDs come back with the same ID; `data[0] == 0x01` means
success, `0x00` means failure.


## 4. Realtime telemetry (fast info)

Sent by the wheel after the phone issues a `GetFastInfo` request
(CAN ID `0x0F550113`, len `8`, ch `5`, data `FF FF FF FF FF FF FF FF`).
The reply is an extended frame; offsets below are into the **extended
payload** (i.e. starting at offset 16 of the inner CAN frame), not into
the BLE packet.

All multi-byte integers are little-endian.

| Offset | Size | Field | Units / scale |
| --- | --- | --- | --- |
| 0 | i32 LE | Pitch / lean angle | raw / 65536, in degrees |
| 4 | i32 LE | (Reserved / motion vector) | Unconfirmed |
| 8 | i32 LE | (Reserved / motion vector) | Unconfirmed |
| 12 | i32 LE | Speed sample A | see section 4.1 |
| 16 | i32 LE | Speed sample B | see section 4.1 |
| 20 | i32 LE | Current (signed) | raw / 1000, in amps |
| 24 | u32 LE | Voltage | raw / 100, in volts |
| 28 | (4 bytes) | Reserved | Unconfirmed |
| 32 | u8 | MOS / motor temperature | raw, in degrees C |
| 33 | u8 | Reserved | |
| 34 | u8 | IMU / board temperature | raw, in degrees C |
| 35 | u8 | Reserved | |
| 36 | (8 bytes) | Reserved | Unconfirmed |
| 44 | u32 LE | Total mileage (most models) | raw, in metres - see section 4.2 |
| 48 | u32 LE | Trip distance | raw, in metres |
| 52 | (8 bytes) | Reserved | Unconfirmed |
| 60 | u32 LE | Work mode / state | see section 4.3 |
| 64 | (8 bytes) | Reserved | Unconfirmed |
| 72 | i32 LE | Roll | raw / 90, in degrees (legacy models only) |

### 4.1 Speed

V1 reports two motor-speed samples and the host averages them:

```
speed_units = (sampleA + sampleB) / (2 * factor)
speed_kmh   = abs(speed_units) * 3.6
```

`factor` depends on the model code returned in the slow-info reply:

| Models | factor |
| --- | --- |
| R1S, R1Sample, R0 | `1000.0` |
| Everything else (V5, V8, V10, L6, R1N, R10, R2, V3, etc.) | `3812.0` |

### 4.2 Total mileage

The encoding of byte 44 has drifted across firmware revisions. Pick by
model code:

| Models | Field at offset 44 | Conversion |
| --- | --- | --- |
| V5 family, V8, V8F, V8S, Glide3, V10 family, R1 family ("1x"), R2 family ("5x" type) | u32 LE | metres |
| R0 | u64 LE | metres |
| L6 | u64 LE | `value * 100` metres (centimetres on the wire) |
| Others (legacy R / V3) | u64 LE | `round(value / 5.711016379455429e7)` km - then convert to metres |

The conversion constant for the legacy branch is the historical WheelLog
value; its derivation is undocumented. Treat older R-series mileage as
"approximate; varies by firmware".

### 4.3 Work mode

Offset 60 is a 32-bit field with two interpretation rules:

**Modern wheels** (V8F, V8S, V10, V10F, V10T, V10FT, V10S, V10SF):

The high nibble of the low byte (`(value >> 4) & 0xF`) is the macro state:

| High nibble | Meaning |
| --- | --- |
| 1 | Shutdown |
| 2 | Drive |
| 3 | Charging |
| other | Unknown |

If `(value & 0x0F) == 1`, append "Engine off".

**Legacy wheels** (V5, V8, R-series, V3, L6, etc.):

The low 4 bits map directly:

| Low nibble | Meaning |
| --- | --- |
| 0 | Idle |
| 1 | Drive |
| 2 | Zero |
| 3 | LargeAngle |
| 4 | Check |
| 5 | Lock |
| 6 | Error |
| 7 | Carry |
| 8 | RemoteControl |
| 9 | Shutdown |
| 10 | pomStop |
| 12 | Unlock |

### 4.4 L6 quirks

L6 has a separate ride-mode and lock-state interpretation that does not
overlap with the V8 / V10 work-mode field. From the legacy work-mode
byte:

- `(byte & 0x0F) != 0`: BLDC mode; otherwise FOC mode.
- `(byte & 0xF0) != 0`: locked; otherwise unlocked.

### 4.5 Battery percent

V1 wheels do **not** report battery percentage on the wire. The phone
derives it from voltage. Per-model curves (input volts in V, output 0.0 to 1.0):

V8 / V8F / V8S / Glide3 (84 V pack):
`>=82.5 V` -> 1.0; `>68.0 V` -> `(volts - 68.0) / 14.5`; else 0.0.

V10 family (84 V pack, "better" curve):
`>83.5 V` -> 1.0; `>68.0 V` -> `(volts - 66.5) / 17`; `>64.0 V` ->
`(volts - 64.0) / 45`; else 0.0.

Default (V5, R-series, others): piecewise linear with breakpoints at
82.0, 77.8, 74.8, 71.8, 70.3, 68.0 - see WheelLog for exact slopes
(values vary by 1-2 percent).


## 5. Settings frame (slow info)

Sent by the wheel in response to `GetSlowInfo` (CAN ID `0x0F550114`, len
`8`, ch `5`, type `1` (remote frame), data all `0xFF`). The reply is an
extended frame; offsets are into the extended payload.

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | 8 bytes | Serial number | Big-endian byte order, hex-formatted |
| 24 | u16 LE | Firmware version field 2 | minor part |
| 26 | u8 | Firmware version field 1 | mid part |
| 27 | u8 | Firmware version field 0 | major part - displayed `f0.f1.f2` |
| 56 | u32 LE | Pedal angle / horizon | `round(value / 6553.6)` -> tenths of a degree |
| 60 | u16 LE | Max speed setting | raw / 1000, in km/h |
| 80 | u8 | Headlight | `1` = on, `0` = off |
| 104 | u8 | Model code low digit | see section 5.1 |
| 107 | u8 | Model code high digit | see section 5.1 |
| 124 | u8 | Pedal sensitivity | `(raw - 28) & 0xFF`; `0x80`=100% (max), `0x20`=min |
| 125 | u16 LE | Speaker volume | raw / 100 |
| 129 | u8 | Handle button | `1` = disabled, `!= 1` = enabled (inverted) |
| 130 | u8 | LED (under-glow) | `1` = on |
| 132 | u8 | Ride mode | `1` = classic, `0` = comfort |

Offsets 125, 129, 130, 132 are only present on firmwares that ship a
slow-info payload longer than 124 / 126 / 129 / 132 bytes respectively.
Older V8 firmware tops out at ~120 bytes and omits all of these.

### 5.1 Model code (carType)

The wheel reports its model as ASCII digits at offsets 104 and 107.
Concatenate as `[byte107 if > 0][byte104]` and look up:

In-scope models (the rest is legacy R / V3 / sample hardware not seen
in the wild on current firmware):

| Code | Model |
| --- | --- |
| `30` | R0 |
| `50` | V5 |
| `51` | V5PLUS |
| `52` | V5F |
| `53` | V5D |
| `60` | L6 |
| `61` | Lively |
| `80` | V8 |
| `85` | Glide 3 (Solowheel) |
| `86` | V8F |
| `87` | V8S |
| `100` | V10S |
| `101` | V10SF |
| `140` | V10 |
| `141` | V10F |
| `142` | V10T |
| `143` | V10FT |

Legacy R-series codes `0` through `7` (R1N/R1S/R1CF/R1AP/R1EX/R1Sample/
R1T/R10), V3 codes `10`-`13` (V3/V3C/V3PRO/V3S), and R2 codes `20`-`24`
also exist. If the wheel reports an unknown code, fall back to V8
telemetry layout.


## 6. Control commands

All commands are CAN frames with `len = 8`, `ch = 5`, `format = 0`,
`type = 0` (data frame) unless noted. The 8-byte data payload follows.

| Command | CAN ID | data[0..7] |
| --- | --- | --- |
| Request fast info (poll) | `0x0F550113` | `FF FF FF FF FF FF FF FF` |
| Request slow info (settings dump) | `0x0F550114` | `FF FF FF FF FF FF FF FF`, type=1 (remote) |
| Request battery cell levels | `0x0F550114` | `00 00 00 0F 00 00 00 00`, type=1 |
| Request firmware version | `0x0F550114` | `20 00 00 00 00 00 00 00`, type=1 |
| Headlight on | `0x0F55010D` | `01 00 00 00 00 00 00 00` |
| Headlight off | `0x0F55010D` | `00 00 00 00 00 00 00 00` |
| Decorative LED on | `0x0F550116` | `B2 00 00 00 0F 00 00 00` |
| Decorative LED off | `0x0F550116` | `B2 00 00 00 10 00 00 00` |
| Beep / horn (V8F / V8S / V10x) | `0x0F550116` | `B2 00 00 00 11 00 00 00` |
| Beep / horn (V8 / V5F: play sound 4) | `0x0F550609` | `04 00 00 00 00 00 00 00` |
| Play sound `n` | `0x0F550609` | `nn 00 00 00 00 00 00 00` |
| Power off | `0x0F550116` | `B2 00 00 00 05 00 00 00` |
| Wheel calibration | `0x0F550119` | `32 54 76 98 00 00 00 00` |
| Set max speed | `0x0F550115` | `01 00 00 00 hi lo 00 00` where `lo,hi = (kmh * 1000) LE` |
| Set ride mode (1=classic, 0=comfort) | `0x0F550115` | `0A 00 00 00 cc 00 00 00` |
| Set pedal sensitivity | `0x0F550115` | `06 00 00 00 hi lo 00 00` where `lo,hi = ((s + 28) << 5) LE` |
| Set pedal tilt (horizon) | `0x0F550115` | `00 00 00 00 b3 b2 b1 b0` where `b0..b3 = (deg10 * 65536 / 10) LE` |
| Set speaker volume | `0x0F55060A` | `lo hi 00 00 00 00 00 00` where `lo,hi = (vol * 100) LE` |
| Handle / lift button enable | `0x0F55012E` | `00 00 00 00 00 00 00 00` |
| Handle / lift button disable | `0x0F55012E` | `01 00 00 00 00 00 00 00` |
| Send PIN / password | `0x0F550307` | 6 ASCII digits + `00 00`, type=0 |

### 6.1 Worked example: "headlight on"

CAN frame (16 bytes, unescaped):

```
0D 01 55 0F  01 00 00 00 00 00 00 00  08 05 00 00
[--ID LE--]  [-----data[0..7]-----]   len ch fmt typ
```

Checksum: `0x0D + 0x01 + 0x55 + 0x0F + 0x01 + 0x08 + 0x05 = 0x80`.

No bytes in this frame need escaping. Wire packet (21 bytes):

```
AA AA  0D 01 55 0F  01 00 00 00 00 00 00 00  08 05 00 00  80  55 55
```

### 6.2 Worked example: "set max speed = 30 km/h"

`30 * 1000 = 30000 = 0x7530` -> `lo=0x30, hi=0x75`. The adapter writes
`value[1], value[0]`, i.e. `hi lo` order in the data slot. The `0x55`
byte that lands at data offset 4 is escaped on the wire as `A5 55`.


## 7. Auth handshake

Some firmwares (notably V10F retail builds and later V8F builds) require
a 6-digit PIN before they will reply to control commands. The wheel
still streams telemetry without the PIN, so basic read-only display
works without authenticating; commands silently fail.

Procedure:
1. The phone must know the 6-digit PIN out of band (printed on the wheel,
   set in the original InMotion app, or stored from a previous session).
2. Send the password command up to six times until the wheel replies
   with CAN ID `0x0F550307`. The reply is a single ack frame; once
   received, treat the session as authenticated and stop sending.
3. If the wheel does not reply, commands will not take effect. There is
   no negative ack.

Wire format: PIN as 6 ASCII bytes (`'1','2','3','4','5','6'`) followed
by two zero bytes, sent on CAN ID `0x0F550307`.

Wheels without a PIN configured accept and ignore the password command,
so it is safe to always send it on first connect when the PIN is known.


## 8. Per-model quirks

| Family | Work mode | Horn | DRL | Volume | Ride mode | Mileage offset 44 | Battery curve |
| --- | --- | --- | --- | --- | --- | --- | --- |
| V8 | modern | playSound(4) | yes | maybe | no | `u32 LE` m | V8 |
| V8F, V8S | modern | dedicated | yes | yes | yes | `u32 LE` m | V8 |
| Glide 3 | modern | dedicated | yes | yes | no | `u32 LE` m | V8 |
| V10 family | modern (roll unreliable) | dedicated | yes | yes | yes | `u32 LE` m | V10 |
| V5 family | legacy | playSound only | no | no | no | `u32 LE` m | default |
| L6 | legacy + L6 lock/BLDC bits | playSound only | no | no | no | `u64 LE * 100` m | always 0 |
| R-series / V3 | legacy | playSound only | no | no | no | `u64 LE / 5.711e7` km | default |

Notes:
- V10 family roll field at offset 72 is unreliable; treat as `0`.
- Speed factor is `1000.0` for R1S / R1Sample / R0; `3812.0` for everything else.
- Using the V8 battery curve on a V10 over-reports battery by 5-10
  percent at the top end.
- L6 odometer should be treated as approximate; the centimetre-as-u64
  encoding is correct for the firmwares WheelLog supports but other
  L6 batches may differ.


## 9. Capability summary

`WheelCapabilities` defaults for a generic V1 wheel before model is known:

| Field | Default | Notes |
| --- | --- | --- |
| `hasHorn` | `true` | V8 / V5F / L6 / R-series / V3 use `playSound(4)` instead of the dedicated horn command |
| `hasLight` | `true` | All V1 wheels expose the headlight command |
| `hasLock` | `false` | No remote lock command. Lock state is observable in work mode but not commandable |
| `hasMaxSpeed` | `true` | RideMode CAN ID, sub-command 1 |
| `hasAlarmSpeed` | `false` | No settable alarm speed; alarms are firmware tilt-back triggers reported via the alert frame |
| `hasVolume` | model-dependent | Yes on V8F / V8S / V10 family / Glide3; no on older V5 / V8 / L6 / R-series / V3 |
| `hasDRL` | model-dependent | Yes on V8, V8F, V8S, Glide3 and full V10 family; no on V5, L6, R-series, V3 |
| `needsAuthForLock` | `false` | No lock command. Note: if PIN is set, all *setting* commands need auth; track separately as `needsAuthForCommands` if needed |

Per-model overrides are summarised in section 8.


## 10. Open questions

- Fast-info offsets 4-11 and 28-31 carry signed 32-bit values on some
  firmwares (additional motion / motor data). Semantics not documented;
  capture under controlled riding to label.
- Fast-info offset 76+: one WheelLog branch reads a fourth temperature
  on V10FT firmware; not present on older builds.
- The `5.711016379455429e7` mileage scale for legacy R / V3 wheels has
  no documented derivation. Roughly `2^32 / 75.0`, suggesting an
  internal fixed-point representation, but unconfirmed.
- `Mode.intToMode(mode)` (rookie / general / smoothly classification on
  bits 4-7 of work mode) is not observed to change on V8 / V10 firmware
  in practice; meaning on R / V3 unverified.
- Slow-info offsets 125-132 vary by firmware even within the same model;
  always range-check `ex_data.length` before reading.
- PIN handshake rate-limit / lockout behaviour on bad attempts is not
  documented.


## 11. Attribution

Primary research source: **WheelLog Android**,
`app/src/main/java/com/cooper/wheellog/utils/InMotionAdapter.java`
(GPLv3). The CAN ID layout, framing, escape rules, fast / slow info
offsets, battery curves and per-model quirks here were reverse-engineered
by the WheelLog community led by Ilya Shkolnik (palachzzz) and
contributors. This document is original prose and tables describing that
research; no code from WheelLog has been copied. Consult the upstream
source for the canonical reference.

Secondary references: WheelLog wiki and forum threads on
electricunicycle.org and esk8.news.

V14 / V13 / V12 / V11 / P6 use the separate V2 protocol family (Nordic
UART UUIDs, different framing); see `inmotion_v2.md` and
`project_v14_protocol.md` for those.
