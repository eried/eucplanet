# Begode / Gotway BLE Protocol

Parser + command-builder reference for Begode (formerly Gotway) electric
unicycles. Implementable from this doc alone, in any language, without copying
GPL code.

This is the messiest of the three big EUC protocols: no checksum, no sequence
number, no length field, no formal versioning. Frame layout mutates by
firmware. Treat every field as best-effort and keep heuristics for invalid
frames.

Research source: WheelLog (the WheelLog community), `GotwayAdapter.java`
and `GotwayVirtualAdapter.java`. WheelLog is GPLv3; this document restates the
protocol in our own words and tables, no code copied.

---

## 1. GATT profile

Begode reuses the HM-10/CC2541 serial-bridge profile, identical to KingSong and
Veteran (Leaper Kim). Brand cannot be inferred from GATT alone; disambiguation
is by first-frame magic bytes (section 3).

| Role        | UUID                                   | Properties        |
|-------------|----------------------------------------|-------------------|
| Service     | `0000ffe0-0000-1000-8000-00805f9b34fb` | primary           |
| Notify      | `0000ffe1-0000-1000-8000-00805f9b34fb` | NOTIFY            |
| Write       | `0000ffe1-0000-1000-8000-00805f9b34fb` | WRITE_NO_RESPONSE |
| CCCD        | `00002902-0000-1000-8000-00805f9b34fb` | descriptor        |

Notes:

- Same characteristic for read and write. Write uses `WRITE_NO_RESPONSE`.
- Enable notifications by writing `0x0001` to the CCCD.
- MTU is default 23; 24-byte frames often arrive split across two notifications, so the parser must reassemble.
- No flow control. The bridge drops bytes under load; tolerate truncated frames.

---

## 2. BLE advertised name

Older wheels advertise the bridge's stock name; newer wheels advertise a
model-specific string. Common observed prefixes:

| Pattern       | Notes                                                |
|---------------|------------------------------------------------------|
| `Gotway_*`    | Older models, generic bridge name                    |
| `Begode_*`    | Post-rebrand (~2021+), most current models           |
| `RS_*`        | RS / RS-HT use a dedicated prefix                    |
| `Master_*`    | Master and Master Pro                                |
| `Hero_*`      | Hero                                                 |
| `MSP_*`       | MSP                                                  |
| `EX_*`, `EX2_*`, `EX.N_*` | EX series                                |
| `T4_*`, `T3_*`            | T3, T4                                   |
| `Mten_*`      | Mten4, Mten5                                         |
| `MCM_*`       | MCM5 (older models also advertise as `Gotway_*`)     |
| `MSX_*`       | MSX                                                  |
| `RW`          | RockWheel rebrand, treated as Begode by WheelLog     |
| `ROCKW*`      | RockWheel variant                                    |

Recommendation: do NOT match on name to pick a brand. Connect, enable notify,
dispatch on magic bytes (section 3). Names are only a scan-UI hint.

---

## 3. Frame structure

### 3.1 Wire format (the "old" Begode frame, still used everywhere)

The wheel emits a stream of 24-byte frames. There is no length field. Frames are
delimited by a fixed header and a fixed footer:

| Offset | Bytes | Field           | Value          |
|--------|-------|-----------------|----------------|
| 0      | 1     | header[0]       | `0x55`         |
| 1      | 1     | header[1]       | `0xAA`         |
| 2..17  | 16    | payload         | type-dependent |
| 18     | 1     | frame type tag  | see 3.3        |
| 19     | 1     | sub-index / fixed `0x18` on the most common frames |
| 20..23 | 4     | terminator      | `0x5A 0x5A 0x5A 0x5A` |

There is no checksum and no length byte. Validity must be inferred from:

- header equals `55 AA`
- four trailing `5A` bytes
- buffer size is exactly 24

### 3.2 Byte order

Begode is **big-endian** for all 16-bit and 32-bit fields. This is unusual for
EUC protocols (KingSong is little-endian). All `u16` / `i16` / `u32` fields in
this document are BE unless explicitly noted.

### 3.3 Frame type tag at offset 18

The wheel multiplexes several frame types over one stream. Tag is at byte 18:

| Tag    | Frame name      | Contents                                      |
|--------|-----------------|-----------------------------------------------|
| `0x00` | Live A          | voltage, speed, distance(16), phase current, temp, PWM |
| `0x01` | BMS summary     | PWM limit, BMS pack voltage/current/temps     |
| `0x02` | BMS cells (1-8)   | 8 cells of BMS #1                           |
| `0x03` | BMS cells (9-16)  | 8 cells of BMS #2 (or upper cells)          |
| `0x04` | Live B          | total mileage, settings, alarms, light/LED   |
| `0x07` | Extra telemetry | true battery current, motor temp, true PWM    |
| `0xFF` | "Alexovik" PID  | only on SmirnoV custom firmware (BF prefix)   |

In practice, frames `0x00` and `0x04` alternate at ~10 Hz on stock firmware, and
the others are interleaved when a Smart-BMS is present.

### 3.4 ASCII firmware-banner frames

Before/around the binary frames the wheel sends short ASCII strings (no `55 AA`
header) advertising firmware and IMU. These are NOT part of the framed protocol;
the parser must recognise them as bare text:

| Prefix | Meaning                                                |
|--------|--------------------------------------------------------|
| `NAME` | Followed by model string, e.g. `NAME RS19`             |
| `GW`   | Stock Begode firmware version, e.g. `GW v1.07`         |
| `JN`   | ExtremeBull (Begode OEM clone) firmware                |
| `CF`   | Freestyl3r custom firmware (HW-PWM enabled)            |
| `BF`   | SmirnoV / "Alexovik" custom firmware                   |
| `MPU`  | IMU revision, e.g. `MPU6050` or `MPU6500`              |

These strings are emitted unsolicited but can also be requested with the `V` and
`N` single-character commands (see section 6). The presence of `MPU6500`
changes the temperature scale (see section 4.2).

### 3.5 "New" Begode format vs Veteran disambiguation

There is no truly new framed Begode protocol. The "virtual" Gotway adapter in
WheelLog is a runtime dispatcher: GATT `0xFFE0 / 0xFFE1` matches both Begode
and Leaper Kim "Veteran"; the first 3 bytes decide:

| First 3 bytes | Brand     | Adapter            |
|---------------|-----------|--------------------|
| `55 AA ..`    | Begode    | this protocol      |
| `DC 5A 5C`    | Veteran   | Leaper Kim Veteran |

`55 AA` is the canonical Begode magic. The "old vs new" dichotomy in forum
threads refers to the addition of frames `0x01..0x03`, `0x07`, `0xFF`; same
24-byte envelope, no breaking wire-format change.

### 3.6 Garbage / re-sync handling

The bridge occasionally inserts spurious `5A` bytes inside a packet. Required
re-sync rules (any clean-room parser should implement):

- At buffer size 5, if it reads `55 AA 5A 55 AA`, drop the first 3 bytes; keep trailing `55 AA`.
- At buffer size 6, if it reads `55 AA 5A 5A 55 AA`, drop the first 4 bytes; keep trailing `55 AA`.
- If any byte at offsets 20..23 is not `0x5A`, discard buffer and resync to next `55 AA`.

---

## 4. Realtime telemetry

All multi-byte fields are big-endian. Offsets are within the 24-byte frame.

### 4.1 Frame type `0x00` (Live A) -- primary telemetry

| Off | Size | Type     | Field            | Units / Scale                         |
|-----|------|----------|------------------|---------------------------------------|
| 0   | 2    | header   | `0x55 0xAA`      | -                                     |
| 2   | 2    | u16 BE   | raw voltage      | 0.01 V at 67.2 V nominal; rescale per pack (see 4.4) |
| 4   | 2    | i16 BE   | raw speed        | speed_kmh = raw * 3.6 / 100; signed for reverse |
| 6   | 2    | u16 BE   | distance high    | combined with bytes 8-9 to form u32 trip distance in meters |
| 8   | 2    | u16 BE   | distance low     | (in WheelLog only the low u16 is read; older wheels never set the high word) |
| 10  | 2    | i16 BE   | phase current    | 0.01 A; signed (motor current; brake = positive or negative depending on `gotwayNegative` setting) |
| 12  | 2    | i16 BE   | raw IMU temp     | see 4.2                                |
| 14  | 2    | i16 BE   | hardware PWM     | only meaningful on Freestyl3r FW; pct = raw * 10, multiply by 100 for centi-percent |
| 16  | 1    | u8       | trick / state    | only used by SmirnoV FW                |
| 17  | 1    | u8       | reserved / flags | not parsed                             |
| 18  | 1    | u8       | frame tag = `0x00` | -                                    |
| 19  | 1    | u8       | sub-index        | usually `0x18`                         |
| 20  | 4    | bytes    | terminator       | `0x5A 0x5A 0x5A 0x5A`                  |

Trip distance: WheelLog reads only `buff[8..9]` as a u16 in meters (wraps at
65535 m). Some firmwares place the full trip in `buff[6..9]` as u32 BE; check
both forms during real captures.

Speed sign: unreliable. Some firmwares emit absolute speed only; others emit
signed. WheelLog exposes `gotwayNegative` (0 = abs, +1, -1). Our parser
should emit raw signed and let the UI normalise.

### 4.2 Temperature decode (offset 12)

The wheel returns the raw 16-bit MPU IMU temperature register. Conversion
depends on the IMU revision (announced in the `MPU` ASCII banner):

| IMU      | Formula (Celsius)                       |
|----------|------------------------------------------|
| MPU6050  | `tempC = (raw / 340.0) + 36.53`          |
| MPU6500  | `tempC = (raw / 333.87) + 21.00`         |

If the IMU is unknown, default to MPU6050. Multiply by 100 if the application
stores temperature as centi-Celsius.

### 4.3 Battery percent (derived from voltage)

Battery percent is derived in the app, not transmitted. WheelLog uses one of
two curves keyed off the *unscaled* (84-V-equivalent) voltage in centi-volts:

Standard curve:

| Raw voltage (cV) | Battery % |
|------------------|-----------|
| `<= 5290`        | 0         |
| `5291 .. 6579`   | `(raw - 5290) / 13` |
| `>= 6580`        | 100       |

"Better percents" curve (preferred):

| Raw voltage (cV) | Battery % |
|------------------|-----------|
| `<= 5120`        | 0         |
| `5121 .. 5440`   | `(raw - 5120) / 36` |
| `5441 .. 6680`   | `(raw - 5320) / 13.6` |
| `> 6680`         | 100       |

For non-84V packs, divide the rescaled voltage by the per-pack scaler back to
the 84V-equivalent value before applying the curve, OR rescale the curve.

### 4.4 Voltage / cell-count scalers

Begode does not advertise battery chemistry. The user (or auto-detect) picks a
scaler:

| App index | Pack nominal | Cells (S) | Scaler | Notes                  |
|-----------|--------------|-----------|--------|------------------------|
| 0         | 67  V        | 16S       | 1.00   | MCM5, older            |
| 1         | 84  V        | 20S       | 1.25   | Default; Mten4, Tesla  |
| 2         | 100 V        | 24S       | 1.50   | Nikola, MSX, MSP       |
| 3         | 116 V        | 28S       | 1.7381 | (= 116/67) older RS    |
| 4         | 134 V        | 32S       | 2.00   | RS, EX, EX.N, T4 32S   |
| 5         | 168 V        | 40S       | 2.50   | Master, Hero, T4 40S   |
| 6         | 151 V        | 36S       | 2.25   | Master Pro             |

"126 V" packs (30S) appear in some forum docs but WheelLog folds them into the
116V/134V tiers. Treat as 30S = 126 nominal = scaler 1.875 if needed.

`true_voltage_V = (raw_cV / 100.0) * scaler`

Auto-voltage: if a `0x01` BMS frame is received, the actual pack voltage in
that frame is authoritative and the scaler is bypassed.

### 4.5 Frame type `0x04` (Live B) -- mileage and settings

| Off  | Size | Type   | Field                             | Units            |
|------|------|--------|-----------------------------------|------------------|
| 2    | 4    | u32 BE | total mileage                     | meters (lifetime)|
| 6    | 2    | u16 BE | settings bitfield                 | see below        |
| 8    | 2    | u16 BE | power-off timer                   | minutes          |
| 10   | 2    | u16 BE | tiltback / max-speed              | km/h; `>= 100` means disabled |
| 13   | 1    | u8     | LED mode                          | 0..9             |
| 14   | 1    | u8     | alert flags                       | bitfield, see below |
| 15   | 1    | u8     | light mode                        | low 2 bits: 0=off, 1=on, 2=strobe |
| 18   | 1    | u8     | frame tag = `0x04`                | -                |
| 19   | 1    | u8     | `0x18`                            | -                |
| 20   | 4    |        | terminator `5A 5A 5A 5A`          | -                |

Settings bitfield at offset 6 (u16 BE):

| Bits     | Field          | Values                                    |
|----------|----------------|-------------------------------------------|
| 15..14   | reserved       |                                           |
| 14..13   | pedals mode    | 0/1/2 (UI inverts: hard=2, medium=1, soft=0) |
| 12..11   | reserved       |                                           |
| 11..10   | speed alarms   | 0=both / 1=only-1 / 2=off / 3=PWM-tiltback (CF FW) |
| 9..8     | reserved       |                                           |
| 8..7     | roll angle     | 0=low / 1=med / 2=high                    |
| 6..1     | reserved       |                                           |
| 0        | miles flag     | 1=miles, 0=km                             |

Alert byte at offset 14:

| Bit | Meaning            |
|-----|--------------------|
| 0   | High power         |
| 1   | Speed alarm 2      |
| 2   | Speed alarm 1      |
| 3   | Low voltage        |
| 4   | Over voltage       |
| 5   | Over temperature   |
| 6   | Hall sensor error  |
| 7   | Transport mode     |

### 4.6 Frame type `0x07` (extra telemetry) -- "true" current, motor temp, true PWM

Present on newer firmwares (post ~2022) and required to get correct battery
current on multi-BMS packs.

| Off | Size | Type   | Field             | Units                |
|-----|------|--------|-------------------|----------------------|
| 2   | 2    | i16 BE | battery current   | 0.01 A (sign convention inverted vs phase current) |
| 4   | 2    |        | reserved          |                      |
| 6   | 2    | i16 BE | motor temperature | Celsius (already converted by FW; multiply 100 for cC) |
| 8   | 2    | i16 BE | true PWM          | 0.01 (multiply by 100 for centi-pct; sign per `gotwayNegative`) |

When this frame arrives, the parser should:

- mark `truePWM = true` and use this value instead of frame `0x00` byte 14
- mark `trueCurrent = true` and use this value instead of any derived current

### 4.7 Frame types `0x01`, `0x02`, `0x03` (Smart BMS)

`0x01` -- BMS summary, indexed by `buff[19]` (`0..1` selects BMS #1, `>=2`
selects BMS #2):

| Off | Size | Type   | Field                        | Units              |
|-----|------|--------|------------------------------|--------------------|
| 2   | 2    | u16 BE | PWM limit                    | 0.01 pct           |
| 6   | 2    | u16 BE | pack voltage                 | 0.1 V (multiply by 10 to get cV) |
| 8   | 2    | i16 BE | BMS current                  | 0.1 A              |
| 10  | 2    | i16 BE | temp sensor 1 (or 3 if odd index) | Celsius       |
| 12  | 2    | i16 BE | temp sensor 2 (or 4 if odd)  | Celsius            |
| 14  | 2    | i16 BE | half-pack voltage            | 0.1 V              |
| 19  | 1    | u8     | BMS sub-index                | bit 0 selects half |

`0x02` and `0x03` -- per-cell voltages (8 cells per frame).

| Off    | Size | Type   | Field                  |
|--------|------|--------|------------------------|
| 2..17  | 16   | 8 x u16 BE | cell voltage in millivolts |
| 19     | 1    | u8     | page index `pNum`; cells covered are `[pNum*8 .. pNum*8 + 7]` |

Frame `0x02` reports BMS #1; frame `0x03` reports BMS #2 (Master / Hero have
two packs and two BMSes).

### 4.8 Frame type `0xFF` (Alexovik / SmirnoV PID)

Only present on SmirnoV custom firmware (banner starts `BF`). Carries advanced
PID and motor-control tuning -- not telemetry. Skip unless you target that FW.

| Off | Field                            |
|-----|----------------------------------|
| 2   | Extreme mode (bit 0)             |
| 3   | Braking current (0..100 pct)     |
| 4   | Rotation control (bit 0)         |
| 5   | Rotation angle (raw + 260 deg)   |
| 6   | Advanced settings on/off         |
| 7   | Proportional factor (P)          |
| 8   | Integral factor (I)              |
| 9   | Differential factor (D)          |
| 10  | Dynamic compensation             |
| 11  | Dynamic compensation filter      |
| 12  | Acceleration compensation        |
| 14  | P factor for Q current           |
| 15  | I factor for Q current           |
| 16  | P factor for D current           |
| 17  | I factor for D current           |

---

## 5. Settings response (query)

There is no dedicated query/response protocol. Settings arrive embedded in the
streaming `0x04` (Live B) frames at all times, plus `0xFF` frames on Alexovik
FW. To "query" a setting, wait for the next `0x04`.

The only explicit query commands are the ASCII-banner requests:

| Command | Reply prefix(es)         | Purpose          |
|---------|--------------------------|------------------|
| `V`     | `GW`, `JN`, `CF`, `BF`   | firmware version |
| `N`     | `NAME ...`               | model name       |

WheelLog issues `V` and `N` repeatedly with 40 ms minimum spacing until both
replies arrive or 50 attempts elapse. After 50 failures the model defaults to
`Begode` and firmware to `-` (unknown).

---

## 6. Control commands

All control commands are short (1..3 byte) ASCII strings written WITHOUT response
to the FFE1 characteristic. There is no acknowledgement; observe the `0x04`
frame to confirm a setting changed. Single-character commands are case-sensitive.

### 6.1 Single-byte ASCII commands

| Byte  | ASCII | Action                                           |
|-------|-------|--------------------------------------------------|
| 0x62  | `b`   | Beep (horn)                                       |
| 0x63  | `c`   | Calibration step 1 (followed by `y` after 300 ms) |
| 0x79  | `y`   | Calibration step 2 / confirm                      |
| 0x45  | `E`   | Light off                                         |
| 0x51  | `Q`   | Light on (full)                                   |
| 0x54  | `T`   | Light strobe (3rd state)                          |
| 0x6D  | `m`   | Switch wheel display to miles                     |
| 0x67  | `g`   | Switch wheel display to kilometers                |
| 0x68  | `h`   | Pedals mode: hard                                 |
| 0x66  | `f`   | Pedals mode: medium                               |
| 0x73  | `s`   | Pedals mode: soft                                 |
| 0x69  | `i`   | Pedals mode: mode 4 (SmirnoV FW only)             |
| 0x6F  | `o`   | Speed alarms: all-on                              |
| 0x75  | `u`   | Speed alarms: only stage 1                        |
| 0x69  | `i`   | Speed alarms: off (note: collides with pedals; context-sensitive in FW) |
| 0x49  | `I`   | PWM-tiltback alarm (CF FW)                        |
| 0x3E  | `>`   | Roll angle: low                                   |
| 0x3D  | `=`   | Roll angle: medium                                |
| 0x3C  | `<`   | Roll angle: high                                  |
| 0x56  | `V`   | Request firmware banner                           |
| 0x4E  | `N`   | Request model name banner                         |
| 0x57  | `W`   | Open multi-byte sub-menu (see 6.2)                |
| 0x22  | `"`   | Disable max-speed limit                           |

Pedals-mode and alarm-mode share the byte `i` (`0x69`); on stock FW the wheel
disambiguates by the most recent `W`-prefix command. Always send pedals-mode
and alarm-mode at least 100 ms apart.

### 6.2 Two-byte sequences (W-prefix sub-menu)

For numeric settings (max speed, beeper volume, LED mode), the wheel uses a
two-step sequence. Send `W` followed by a sub-menu selector ~100 ms later, then
the digit byte ~200 ms after that.

| Sequence                  | Action                              |
|---------------------------|-------------------------------------|
| `W` then `Y` then `H L`   | Set max speed: H = `(speed/10)+0x30`, L = `(speed%10)+0x30`. Followed by `b` to confirm. |
| `W` then `B` then `0..9`  | Set beeper volume (1..9 -> 0x31..0x39) |
| `W` then `M` then `0..9`  | Set LED mode (0..9 -> 0x30..0x39)   |

After the digit, send `b` (beep) again to confirm-write.

### 6.3 Two-byte raw commands (Alexovik / SmirnoV FW)

These wheels accept binary 3-byte commands, not ASCII. First two bytes are the
opcode (printable but treat as raw), third byte is the value:

| Opcode    | Field                                | Value range   |
|-----------|--------------------------------------|---------------|
| `0x45 0x4D` | Extreme mode                         | 0 / 1         |
| `0x42 0x41` | Braking current                      | 0..100        |
| `0x52 0x43` | Rotation control                     | 0 / 1         |
| `0x72 0x73` | Rotation angle (degrees - 260)       | 0..100 (= 260..360) |
| `0x61 0x73` | Advanced settings on/off             | 0 / 1         |
| `0x68 0x70` | P factor                             | 0..255        |
| `0x68 0x69` | I factor                             | 0..255        |
| `0x68 0x64` | D factor                             | 0..255        |
| `0x68 0x63` | Dynamic compensation                 | 0..255        |
| `0x68 0x66` | Dynamic compensation filter          | 0..255        |
| `0x61 0x63` | Acceleration compensation            | 0..255        |
| `0x63 0x70` | P factor (Q current)                 | 0..255        |
| `0x63 0x69` | I factor (Q current)                 | 0..255        |
| `0x64 0x70` | P factor (D current)                 | 0..255        |
| `0x64 0x69` | I factor (D current)                 | 0..255        |
| `0x74 0x74` | Trick mode                           | 0..N          |

### 6.4 Connect-time beep

The app sends a single `b` after notifications are enabled, both as a UX cue
("the wheel beeped, we're connected") and to flush the bridge's input buffer.

### 6.5 Lights -- 3-state

Begode is the only major brand with a 3-state light: off / on / strobe. UI
should rotate `E` -> `Q` -> `T` -> `E`. Strobe is unsafe at night and is
repurposed by Freestyl3r FW as the PWM-tiltback warning indicator; do not send
`T` if alarm-mode is `I` (PWM-tiltback).

---

## 7. Per-model and firmware quirks

### 7.1 Voltage tier per model

| Model            | Cells | Pack nominal | App index | Notes                  |
|------------------|-------|--------------|-----------|------------------------|
| Mten4            | 20S   | 84 V         | 1         |                        |
| Mten5            | 24S   | 100 V        | 2         |                        |
| MCM5 v1          | 16S   | 67 V         | 0         |                        |
| MCM5 v2          | 20S   | 84 V         | 1         | Set "is MCM" ratio fix |
| MSX (84V)        | 20S   | 84 V         | 1         |                        |
| MSX (100V)       | 24S   | 100 V        | 2         |                        |
| MSP              | 24S   | 100 V        | 2         |                        |
| Nikola 84/100    | 20S/24S | 84/100 V   | 1 / 2     |                        |
| RS               | 24S   | 100 V (early) / 134 V (late) | 2 / 4 | Two voltage variants exist |
| RS-HT            | 32S   | 134 V        | 4         |                        |
| EX, EX.N         | 32S   | 134 V        | 4         |                        |
| EX2              | 36S   | 151 V        | 6         |                        |
| T3               | 32S   | 134 V        | 4         |                        |
| T4               | 32S/40S | 134 V / 168 V | 4 / 5   | Two pack options       |
| Hero             | 32S   | 134 V        | 4         |                        |
| Master           | 36S   | 151 V        | 6         |                        |
| Master Pro       | 36S   | 151 V        | 6         | Dual BMS               |

Always allow user override; some owners refit packs.

### 7.2 MCM5 distance/speed ratio

Firmware on MCM5 reports speed and distance pre-multiplied by ~1.143 (8/7).
Compensate by multiplying by `RATIO_GW = 0.875` when the user enables the
"My wheel is a Gotway MCM" toggle. No automatic detection.

### 7.3 SmirnoV (BF) custom firmware

- Replaces frame `0x01` with pedals-mode info and frame `0xFF` with PID config.
- Battery current shipped in `0x00` byte 8 (not in `0x07`).
- IMU is MPU6500, not MPU6050: use the alternate temperature formula.
- Hardware PWM is real (set `hwPwm = true`).

### 7.4 Freestyl3r (CF) custom firmware

- Hardware PWM is real (parse byte 14 of `0x00` directly, do not derive).
- 4th alarm mode "PWM tiltback" added (command `I`).
- Strobe light is repurposed for tiltback warning; do not send `T` while CF
  PWM-tiltback is active.

### 7.5 ExtremeBull (JN) firmware

- Stock-equivalent frame layout.
- HW-PWM not enabled by default; treat as Begode stock for telemetry.

### 7.6 Old vs new frame summary

Forum users sometimes call frames including BMS data ("0x01..0x03") and `0x07`
the "new" Begode protocol. This is a pure addition: same envelope (`55 AA ...
5A 5A 5A 5A`), new tag values at byte 18. Older firmwares simply never emit
those tags. There is no breaking re-keying of byte offsets.

| "Old" wheels (frames `0x00`, `0x04` only) | "New" wheels (also `0x01`, `0x02`, `0x03`, `0x07`) |
|------|------|
| MCM5 v1, Mten4, MSX 84V, Nikola 84V, older RS | RS-HT, EX, EX2, EX.N, T3, T4, Hero, MSP, Master, Master Pro |

### 7.7 Phase current vs battery current

- Frame `0x00` byte 10: **phase** current (raw inverter current, larger).
- Frame `0x07` byte 2: **battery** current (sign-inverted from phase).
- BMS frames `0x01` byte 8: BMS-reported pack current (most accurate).

If only phase current is available, derive battery current as
`I_batt ~= I_phase * PWM`.

---

## 8. WheelCapabilities summary

Suggested `WheelCapabilities` defaults for our adapter:

| Capability               | Begode default                          |
|--------------------------|-----------------------------------------|
| `hasHorn`                | true (`b`)                              |
| `hasLight`               | true                                    |
| `lightStates`            | 3 (off / on / strobe)                   |
| `hasSoftwareLock`        | **false** (Begode only has dismount-lock; no API) |
| `hasMaxSpeedSetting`     | true                                    |
| `hasTiltback`            | true (alias of max-speed on stock FW)   |
| `hasPedalMode`           | true (3 levels, 4 on SmirnoV)           |
| `hasRollAngleMode`       | true (3 levels)                         |
| `hasMilesToggle`         | true                                    |
| `hasLedMode`             | true (0..9)                             |
| `hasBeeperVolume`        | true (1..9)                             |
| `hasCalibration`         | true                                    |
| `hasAlarmMode`           | true (3 modes; 4 on CF FW)              |
| `hasBattery`             | true (computed from voltage)            |
| `hasSmartBmsCells`       | true on dual-BMS models, otherwise false|
| `hasPhaseCurrent`        | true                                    |
| `hasMotorTemp`           | only on `0x07` frame firmwares          |
| `hasBatteryCurrent`      | only on `0x07` or BMS frames            |
| `reportsSignedSpeed`     | firmware-dependent; expose toggle       |

---

## 9. Open questions / unverified

- **Reverse speed sign**: confirm by capture per model. Some wheels emit
  `i16` BE with proper sign, others always positive.
- **Distance high word**: WheelLog reads only the low u16 from frame `0x00`
  byte 8. Unclear whether bytes 6-7 are always part of distance (u32) or are
  status bits on some firmwares -- worth a labelled capture.
- **Settings bitfield**: bits 11..10 = speed alarms is documented but the
  exact mapping to the `o` / `u` / `i` / `I` commands needs verification
  against an EX2 or Master firmware capture.
- **Auto-detect "old vs new"**: heuristic = wait 2 s after connect; if no
  frame with tag `0x07` arrives, treat as old protocol. Marked uncertain
  because `0x07` frames are sent every ~1 s on new firmwares but only every
  few seconds on some MSX builds.
- **Tiltback vs max-speed**: stock Begode treats `wheelMaxSpeed` as
  tiltback. The "true" max speed is hard-set in firmware. Confirm whether
  the `Y H L` command actually limits speed or just shifts the tiltback
  threshold per model.
- **Total distance encoding**: confirmed u32 BE in meters per WheelLog. Some
  forum posts claim a wheel-specific divisor of 36 on certain Mten units.
- **126V (30S) packs**: omitted from WheelLog's scaler list. May exist in
  some EX/Master refits; treat 1.875 as a manual override rather than
  auto-detect.

---

## 10. Attribution

Primary research source: WheelLog Android by the WheelLog community --
`GotwayAdapter.java` and `GotwayVirtualAdapter.java`, GPLv3.
Repository: https://github.com/Wheellog/wheellog.android

Additional cross-references that informed the mapping: EUC.Forum threads on
Begode RS / EX, EUC World protocol notes, DarknessBot Gotway frame docs, and
NbPower BMS-frame notes. No code from any of those projects is reproduced
here; every field, formula and command is restated in our idiom.
