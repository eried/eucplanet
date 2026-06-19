# InMotion V2 BLE protocol

Reference for parser and command builder implementation. Covers the
newer InMotion family that post-dates the V8 / V10 generation (known
here as "InMotion V1", in `inmotion_v1.md`).

In-scope models: V14 (50GB, 50S, Adventure), V13, V13 PRO, V12 HS,
V12 HT, V12 PRO, V12S, V11, V11y, V9, and the InMotion P6 e-scooter.
The P6 is on the same wire family but uses an extended-routing-only
variant of the command set; it gets its own branch in every section
below.

Original prose. The V14 / V12 / V11 layouts were cross-checked against
public open-source research; the P6 layout is original work from labelled
BLE captures against the user's hardware. No third-party GPL code copied.
See Attribution at the end. Status: research-grade, V14 telemetry and the
extended-routing CONTROL packet shape are solid; P6 telemetry past
voltage / current / speed / temperatures is partial and several fields
are flagged. V12 HS/HT/Pro deviations have not been validated against
a real wheel.


## 1. GATT profile

V2 advertises a **single** proprietary service following the Nordic
UART pattern. Notifications and writes are on different
characteristics inside the same service.

| Role | UUID (128-bit) | Short |
| --- | --- | --- |
| UART service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | NUS |
| Write characteristic (phone -> wheel) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | RX |
| Notify characteristic (wheel -> phone) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | TX |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` | standard |

Notes:
- This is **not** the same shape as V1. V1 uses split services
  `0xFFE0` (notify) + `0xFFE5` (write); V2 collapses both onto a single
  Nordic UART service with the conventional Adafruit/Nordic-style RX /
  TX split. KingSong, Begode and Veteran wheels also use `0xFFE0`-
  family UUIDs and are not on this protocol.
- All BLE notifications can split a single `AA AA …` frame across
  multiple GATT packets, especially on the longer settings / stats
  responses. A reassembly buffer is required (see section 3.4).
- Wheels also expose standard `0x180A` (Device Info) and `0x180F`
  (Battery). A parser does not need to discover those.

Procedure on connect: discover the Nordic UART service, subscribe to
TX (CCCD `0x01 0x00`), then run the family-specific init sequence
(section 5). The P6 path differs from V14; see section 7.


## 2. BLE name pattern

Advertised names observed:

| Pattern | Examples | Notes |
| --- | --- | --- |
| `V14` | `V14`, `V14-AB12` | V14 50GB / 50S; some firmwares prepend the brand |
| `Adventure-...` | `Adventure-1234` | V14 Adventure variant, same wire protocol as V14 |
| `V13` | `V13`, `V13-Pro-...` | V13 and V13 PRO |
| `V12` | `V12`, `V12HS-...`, `V12S-...` | V12 HS / HT / Pro / V12S, the exact variant is only known after carType resolves |
| `V11` | `V11`, `V11y-...` | V11 and V11y |
| `P6-XXXXXXXX` | `P6-AB12CD34` | InMotion P6 e-scooter; **drives a separate command path** |

Detection strategy:
1. Probe for service `6e400001-...` containing characteristics
   `6e400002` (write) AND `6e400003` (notify).
2. The advertised name resolves the wheel family, but not the exact
   model: `V12` could be HS / HT / Pro / S, `V13` could be PRO,
   `V14` could be 50GB / 50S / Adventure. Only the carType frame
   (cmd 0x02 sub 0x01, section 5) distinguishes them.
3. A name starting with `P6-` switches the connection to the
   extended-routing-only command set **before** the first init packet
   goes out. The P6 returns all zeros to the legacy carType query, so
   the family must be pinned from the name; see section 7.
4. The `Adventure-` prefix is treated identically to `V14`, same wire
   protocol, same parser. The carType byte then refines the display
   name to the exact V14 variant.


## 3. Frame structure

V2 wraps a flat command/data payload in a BLE envelope with byte
stuffing. There is no end trailer, the frame ends at the checksum
byte and the next `AA AA` starts the next frame.

```
offset  0  1   2 ... N-2   N-1
        AA AA  <escaped payload>  CK
```

| Field | Size | Meaning |
| --- | --- | --- |
| Header | 2 bytes | `0xAA 0xAA` literal start-of-frame |
| Body | variable | Escaped payload, see below |
| Checksum | 1 byte | XOR of every **unescaped** payload byte |

V1 differs by ending each frame with a literal `0x55 0x55` trailer and
using a sum-mod-256 checksum; V2 has no trailer and XORs instead.

### 3.1 Byte stuffing

The bytes `0xAA` and `0xA5` cannot appear unescaped inside the payload
or checksum. Each occurrence is prefixed with `0xA5`.

| Raw byte | On the wire |
| --- | --- |
| `0xAA` | `0xA5 0xAA` |
| `0xA5` | `0xA5 0xA5` |
| any other | unchanged |

The checksum byte itself is escaped on the same rules. The two header
bytes `0xAA 0xAA` are always literal and are written without
escaping. V1 also stuffs `0x55` because it has a `0x55 0x55` trailer;
V2 has no trailer and leaves `0x55` literal.

### 3.2 Payload layout (unescaped)

The payload sits between the header and the checksum. There are two
shapes, depending on the `flags` byte at offset 0.

**Default / initial shape** (flags `0x11` or `0x14`):

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | u8 | flags | `0x11` initial, `0x14` default |
| 1 | u8 | length | covers `command` + `data`, i.e. `data.len + 1` |
| 2 | u8 | command | see section 5 |
| 3 | N | data | command-specific, see section 5 |

**Extended shape** (flags `0x16`):

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | u8 | flags | `0x16` (extended) |
| 1 | u8 | length | covers routing + command + data, i.e. `data.len + 3` |
| 2 | u8 | routing 0 | always `0x02` |
| 3 | u8 | routing 1 | always `0x21` |
| 4 | u8 | command | see section 5 |
| 5 | N | data | command-specific, see section 5 |

The `0x02 0x21` routing pair is what the official InMotion app uses
for security-sensitive commands and for the entire P6 protocol. V11
and older firmwares accept the DEFAULT form for lock; V12 / V13 / V14
silently drop lock unless it arrives via EXTENDED. Light, horn, max
speed and DRL go through EXTENDED on V12 and newer in our
implementation regardless of model.

### 3.3 Flags byte

| Value | Name | Used for |
| --- | --- | --- |
| `0x11` | INITIAL | First-boot info queries (carType / serial / firmware versions) |
| `0x14` | DEFAULT | Steady-state queries (realtime, settings, statistics) and V14-legacy light writes |
| `0x16` | EXTENDED | Routing-prefixed packets, all CONTROL writes on V12+ and every P6 packet, request/response both |

There is also an `0x13` flag used for the authentication handshake
sub-protocol; see section 5.4. It is not a routing variant of the
above, it is the auth protocol's own framing flag.

### 3.4 Reassembly

A single BLE notification can carry partial frames in either
direction. The receiver must buffer raw bytes and scan for `AA AA`
boundaries:

1. Append each incoming notification to a per-connection byte buffer.
2. Walk the buffer looking for `0xAA 0xAA` pairs; each pair starts a
   candidate frame.
3. For each candidate, take everything up to the next `0xAA 0xAA` (or
   end of buffer), unescape, verify XOR checksum, and parse.
4. If the final candidate is incomplete (checksum doesn't validate
   yet), keep it in the buffer for the next notification.

A checksum mismatch is treated as "not yet a complete frame" rather
than "corrupt frame", production wheels do not corrupt frames in
practice, but they do split them.

### 3.5 Checksum

XOR every unescaped payload byte (`flags`, `length`, routing bytes if
present, command, every data byte). The result is a single byte; on
the wire it is escaped under the same rules as a payload byte.

V1 uses sum-mod-256 over the unescaped 16-byte CAN frame; V2 uses XOR
over the variable-length payload. The two are not interchangeable.


## 4. Realtime telemetry

The realtime stream is the wheel's reply to a `RealTimeInfo` request
(command `0x04`, no data). Layout differs per family.

### 4.1 V14 realtime layout

All multi-byte integers are little-endian. Offsets are into the
`data` field of the parsed packet (i.e. after `flags`, `length`,
optional routing, command).

| Offset | Size | Field | Units / scale |
| --- | --- | --- | --- |
| 0 | u16 LE | Voltage | raw / 100, in volts |
| 2 | i16 LE | Current (signed) | raw / 100, in amps |
| 8 | i16 LE | Speed (signed) | raw / 100, in km/h |
| 12 | i16 LE | Torque (signed) | raw / 100, in N·m |
| 14 | i16 LE | PWM (signed) | raw / 100, in percent |
| 16 | i16 LE | Battery power (signed) | watts |
| 18 | i16 LE | Motor power (signed) | watts |
| 20 | i16 LE | Pitch angle (signed) | raw / 100, in degrees |
| 22 | i16 LE | Roll angle (signed) | raw / 100, in degrees |
| 28 | u16 LE | Trip distance | raw × 10, in metres |
| 34 | u16 LE | Battery 1 percent | raw / 100, in percent |
| 36 | u16 LE | Battery 2 percent | raw / 100, in percent |
| 40 | u16 LE | Dynamic speed limit | raw / 100, in km/h |
| 50 | u16 LE | Dynamic current limit | raw / 100, in amps |
| 58 | u8 | Temperature sensor 1 | see section 4.4 |
| 59 | u8 | Temperature sensor 2 | see section 4.4 |
| 60 | u8 | Temperature sensor 3 | see section 4.4 |
| 61 | u8 | Temperature sensor 4 | see section 4.4 |
| 62 | u8 | Temperature sensor 5 | see section 4.4 |
| 63 | u8 | Temperature sensor 6 | see section 4.4 |
| 74 | u8 | PC mode | low 3 bits = wheel state, see section 4.5 |
| 76 | u8 | Light state | bit 1 = headlight on |

Minimum packet length is 65 bytes; everything past offset 51 (current
limit, temps, pcMode, light) is read only when the packet is long
enough.

Battery percent shown to the user is the rounded average of `Battery
1 percent` and `Battery 2 percent`, clamped to 0–100. Truncation was
previously the source of a 1 % disagreement with the wheel's own
screen.

### 4.2 V12 HS / HT / PRO realtime layout

V12 packs values tighter, speed / torque / PWM sit at lower offsets,
the two-pack battery split is collapsed into a single field, and the
state byte moves earlier. All multi-byte values are little-endian.

| Offset | Size | Field | Units / scale |
| --- | --- | --- | --- |
| 0 | u16 LE | Voltage | raw / 100, in volts |
| 2 | i16 LE | Current (signed) | raw / 100, in amps |
| 4 | i16 LE | Speed (signed) | raw / 100, in km/h |
| 6 | i16 LE | Torque (signed) | raw / 100, in N·m |
| 8 | i16 LE | PWM (signed) | raw / 100, in percent |
| 10 | i16 LE | Battery power (signed) | raw × 100, in watts |
| 12 | i16 LE | Motor power (signed) | watts |
| 16 | i16 LE | Pitch angle (signed) | raw / 100, in degrees |
| 18 | i16 LE | Pitch aim angle (signed) | raw / 100, in degrees, unused |
| 20 | i16 LE | Roll angle (signed) | raw / 100, in degrees |
| 22 | u16 LE | Trip distance | raw / 100, in km |
| 24 | u16 LE | Battery level | raw / 100, in percent |
| 26 | u16 LE | Remaining mileage | unused |
| 28 | u16 LE | Reserved | always 18000 in observed captures |
| 30 | u16 LE | Dynamic speed limit | raw / 100, in km/h |
| 32 | u16 LE | Dynamic current limit | raw / 100, in amps |
| 40 | u8 | Temp: MOS | section 4.4 |
| 41 | u8 | Temp: motor | section 4.4 |
| 42 | u8 | Temp: battery | always 0, skipped |
| 43 | u8 | Temp: board | section 4.4 |
| 44 | u8 | Temp: CPU | section 4.4 |
| 45 | u8 | Temp: IMU | section 4.4 |
| 46 | u8 | Temp: lamp | always 0, skipped |
| 54 | u8 | State byte | bits 0–2 pcMode, 3–5 mcMode, 6 motor active, 7 charging |
| 55 | u8 | Light byte | bit 0 low beam, bit 1 high beam, bit 2 lifted |

Minimum packet length is 56 bytes. The single `Battery level` field
is mirrored to both `battery1` / `battery2` in the dashboard so the
UI shows one consistent number rather than two half-filled rings.

### 4.3 Temperatures (V14 and V12)

Each temperature byte is decoded via the shared
`ByteUtils.parseTemperature(byte)` helper. The on-wire encoding is a
single byte with a model-wide offset; the helper applies the offset
and yields degrees Celsius. The exact offset constant is defined in
`ByteUtils.parseTemperature` (`176` for V12 family); refer to the
helper rather than reproducing the formula here.

For V12 the always-zero MOS / lamp slots at offsets 42 and 46 are
skipped before computing the maximum so the dashboard's hottest-
sensor number is not dragged toward `−176 °C`.

### 4.4 Work mode (PC mode)

V14 and V12 expose a single byte (`pcMode = stateByte & 0x07`) with a
shared interpretation. The bits map by family:

| Family | Source byte | Bits used | Encoding |
| --- | --- | --- | --- |
| V14 | data\[74\] | low 3 | low-3-bits state |
| V12 | data\[54\] | low 3 (state), 3–5 (mc), 6 (motor), 7 (charging) | full state byte |
| P6 | data\[80\] | full byte | `0x49 = engaged, 0x00 = parked, 0xFD/0xFE = pre-auth` |

The detailed enum mapping for the low-3-bit state field (`0 = lock,
1 = drive, 2 = shutdown, 3 = idle` for V12) is firmware-dependent.
Public V14 references list "0 / 1 / 2 / 3" without universal labels;
treat anything outside `1` (drive) as non-engaged for display purposes
and surface the raw value to diagnostics.


## 5. Commands (default protocol family)

All commands are written from phone to wheel; replies come back on
the same command byte. Unless noted, the DEFAULT framing
(flags `0x14`) is used; CONTROL writes use EXTENDED (flags `0x16`,
routing `0x02 0x21`).

### 5.1 Command catalog

| Byte | Name | Direction | Used for |
| --- | --- | --- | --- |
| `0x01` | MAIN_VERSION | both | Bootloader / hardware revision (rarely polled) |
| `0x02` | MAIN_INFO | both | Multiplex: carType (sub `0x01`), serial (sub `0x02`), versions (sub `0x06`), and auth replies (`0x80`-family) |
| `0x03` | DIAGNOSTIC | both | Reserved, issued by the official app's service mode, not used here |
| `0x04` | REAL_TIME_INFO | both | Realtime telemetry, polled every 200 ms by default |
| `0x05` | BATTERY_INFO | both | Per-cell battery telemetry (not implemented in this app) |
| `0x10` | SOMETHING1 | both | Mandatory but ignored response, sent during init to mirror the official app |
| `0x11` | TOTAL_STATS | both | Lifetime mileage, ride time, power-on time |
| `0x20` | SETTINGS | both | Settings page A, tiltback, alarm, pedal angle, modes, etc. |
| `0x60` | CONTROL | phone -> wheel | Sub-command-dispatched control writes (see 5.3) |

The packet layout for each query is `flags + length + cmd + data`;
the data byte is the sub-cmd selector. Examples below match the
exact byte sequences the app writes.

### 5.2 MAIN_INFO sub-commands

| Sub | Description | Packet (DEFAULT, flags `0x11`) | Reply data layout |
| --- | --- | --- | --- |
| `0x01` | carType | `AA AA 11 02 02 01 ck` | `[0x01, mainSeries, series, type, …]`, `modelId = series*10 + type` |
| `0x02` | serial number | `AA AA 11 02 02 02 ck` | `[0x02, 16 ASCII bytes]` |
| `0x06` | firmware versions | `AA AA 11 02 02 06 ck` | `[0x06, padding, driverBoard, …, mainBoard, …, BLE, …]` |

The carType payload on V14 is **only 6 bytes long**; an earlier
`data.size < 8` guard wrongly rejected it. The parser now accepts any
carType ≥ 3 bytes and reads `mainSeries / series / type` at
data\[0..2\].

Firmware-version layout (after stripping the leading sub-cmd echo at
data\[0\]):

| Offset | Field |
| --- | --- |
| 1 | DriverBoard patch (u16 LE), minor at +2, major at +3 |
| 10 | MainBoard patch (u16 LE), minor at +2, major at +3 |
| 19 | BLE patch (u16 LE), minor at +2, major at +3 |

Each is displayed as `major.minor.patch`.

### 5.3 CONTROL sub-commands

Every CONTROL write goes through EXTENDED framing
(`AA AA 16 LEN 02 21 60 SUB [data…] CK`). Sub-cmd byte values:

| Sub | Name | Data layout | Notes |
| --- | --- | --- | --- |
| `0x21` | SET_MAX_SPEED | `tilt_lo tilt_hi alarm_lo alarm_hi` (each u16 LE × 100) on V14 family; `tilt_lo tilt_hi` only on V11 / V12 HS/HT/PRO | The wheel discriminates by payload length, not by sub-cmd |
| `0x22` | SET_PEDAL_TILT | u32 LE / 65536 in degrees (legacy from V1 mapping; not exercised in this app) | Reserved |
| `0x26` | SET_VOLUME | single byte 0–100 | Clamped to 0–100 |
| `0x2B` | SET_LIGHT_BRIGHTNESS | reserved | Not exercised |
| `0x2D` | SET_DRL | single byte `0` / `1` | V14 family; V12 has no DRL |
| `0x31` | SET_LOCK | single byte `0` (unlock) / `1` (lock) | Requires EXTENDED framing on V12+; auth handshake (5.4) required first on V12+ |
| `0x50` | SET_LIGHT | V14: single byte `0` / `1` ; V12 HS/HT/Pro: `[low, high]` two-beam pair | Single-byte form is silently ignored on V12 HS/HT/Pro |
| `0x51` | PLAY_SOUND | V14: `[soundId, volume]` (canonical `[0x02, 0x64]`); V11 / V12 HS/HT/Pro / V12S / V9: `[soundId, 0x01]` (canonical `[0x18, 0x01]`) | "playBeep" vs "playSound", wrong opcode is silently dropped |

For SET_MAX_SPEED: tiltback and alarm are each `(km/h × 100)` as
u16 LE. Example: 30 km/h tiltback + 35 km/h alarm on V14 is

```
AA AA 16 07 02 21 60 21  B8 0B  AC 0D  CK
                         3000   3500
```

V11 / V12 HS/HT/PRO models only accept the short form
(`[0x21, lo, hi]`, 3 bytes after routing); attempting the long form
on those wheels causes the wheel to ignore the packet. The adapter
picks based on `detectedModel.maxSpeedHasAlarms`.

For V12 HS/HT/PRO the alarm threshold goes through a separate
sub-cmd `0x3e`:

```
AA AA 16 09 02 21 60 3e  a1_lo a1_hi  a2_lo a2_hi  CK
```

with both tiers carrying the same single user-facing value.

### 5.4 Authentication handshake

V14 family wheels respond to most read and control packets without
authentication. The exceptions are `SET_LOCK` (on V12 and newer) and
the entire P6 control endpoint, which silently drop until a one-shot
handshake has run.

The handshake uses its own flags byte `0x13` and routing pair `0x02
0x00`:

1. Phone -> wheel: `requestAuthKey()` =
   `AA AA 13 04 02 00 00 02 17`. The wheel replies with a 16-byte
   "encrypted password" blob inside a `0x02 0x80 0x02 [16 bytes]`
   response.
2. Phone -> wheel: `verifyAuth(blob)` echoes the same blob back as
   `AA AA 13 14 02 00 00 82 [16 bytes] CK`. The wheel replies with
   `0x02 0x80 0x82 0x01` to signal success.

Once the wheel ACKs, control writes are accepted for the lifetime of
the connection. There is no documented rate limit or lockout on bad
attempts; in practice the handshake is a fixed echo (the wheel
accepts the same blob it just sent) and never fails on production
firmware.

### 5.5 SETTINGS (V14 layout)

Reply data starts with a sub-cmd echo at offset 0; the parser strips
it, then reads (offsets into the stripped buffer):

| Offset | Size | Field | Units |
| --- | --- | --- | --- |
| 0 | u16 LE | Max speed (tiltback) | raw / 100, km/h |
| 2 | u16 LE | Alarm speed | raw / 100, km/h |
| 8 | i16 LE | Pedal adjustment | raw / 10, degrees |
| 10 | u8 | Mode flags | bit 0 offroad, bit 4 fancier |
| 11 | u8 | Comfort sensitivity | percent |
| 12 | u8 | Classic sensitivity | percent |
| 20 | u16 LE | Standby delay | raw, seconds (divide by 60 for minutes) |
| 30 | u8 | Flags A | bit 0 NOT mute, bit 2 DRL |
| 31 | u8 | Flags B | bit 2 locked, bit 4 transport mode |

For V12 HS/HT/Pro the SETTINGS layout differs:

| Offset | Size | Field | Units |
| --- | --- | --- | --- |
| 9 | u16 LE | Max speed (tiltback) | raw / 100, km/h |
| 11 | i16 LE | Alarm speed 1 | raw / 100, km/h |
| 13 | i16 LE | Alarm speed 2 | raw / 100, km/h, exposed as the single alarm threshold |
| 15 | i16 LE | Pedal adjustment | raw / 10, degrees |
| 17 | u16 LE | Standby delay | raw, seconds |
| 19 | u8 | Mode flags | bit 0 classic ride mode, bit 4 fancier |
| 22 | u8 | Speaker volume | percent |
| 39 | u8 | Flags | bit 0 NOT mute, bit 6 transport mode |

V12 settings have no reliable lock-state byte in the public layout;
the parser leaves `lockState = 0` (unlocked) and the repository
tracks the user's intent locally.


## 6. Worked example: "lock"

Phone wants to lock a V14. Sub-cmd `0x31` with payload `[0x01]`,
EXTENDED framing:

```
payload bytes (unescaped):
  16   05   02 21   60   31 01
  flg  len  routing cmd  sub data

XOR: 0x16 ^ 0x05 ^ 0x02 ^ 0x21 ^ 0x60 ^ 0x31 ^ 0x01 = 0x60

Wire (no escape needed, no 0xAA or 0xA5 in the payload):
  AA AA 16 05 02 21 60 31 01 60
```

The 10-byte sequence above matches the captures from the official
InMotion app for a V14 lock.

Unlock differs only at the data byte: `00` instead of `01`, and the
checksum becomes `0x61`.


## 7. P6 deviations

The InMotion P6 e-scooter is on the V2 wire family but only honors
the **EXTENDED routing form**. Every query and every response uses
`02 21 [sub]` / `21 02 [sub|0x80] …`; the legacy `02 [cmd]` queries
return all zeros. This means a parser must know it's talking to a P6
before sending the first init packet, the carType query (`02 01`)
silently fails and would otherwise time out the connection.

The app detects P6 by the BLE name prefix `P6-` (section 2). Once
detected:

- `notifyConnectingTo()` pins `detectedModel = P6` and sets the
  `useP6Protocol` flag.
- `initSequence()` issues only `getP6Info()` (sub `0x06`) and
  `getP6Settings()` (sub `0x20`). The legacy carType / serial /
  firmware / statistics queries are skipped.
- `pollRealtime()` issues `getP6RealTimeData()` (sub `0x07`).
- `pollSettings()` issues `getP6Settings()` (sub `0x20`).
- The P6 requires the auth handshake (section 5.4) on every connect
  before any control write is accepted. V14 family wheels only need
  the handshake for `SET_LOCK`.

### 7.1 P6 sub-command map

| Sub | Direction | Description |
| --- | --- | --- |
| `0x04` | both | Detailed-data query / response (motor + driver-board temperatures) |
| `0x06` | both | Info bundle: serial number + firmware version |
| `0x07` | both | Realtime telemetry |
| `0x10` | both | Ride history, TLV-style layout, not yet reverse-engineered |
| `0x11` | both | Total stats, TLV-style layout, not yet reverse-engineered |
| `0x20` | both | Settings page A, 51-byte body, see 7.3 |
| `0x60` | phone -> wheel | CONTROL (same sub-cmd bytes as V14 where they apply, plus P6-specific ones) |

### 7.2 P6 realtime layout (sub `0x07`)

The response arrives as `21 02 87 01 00 [body…]`; the parser strips
the `02 87 01 00` prefix and reads the body. Offsets are into the
body, all multi-byte values little-endian.

| Offset | Size | Field | Units / scale | Confidence |
| --- | --- | --- | --- | --- |
| 0 | u16 LE | Voltage | raw / 100, V | high, matches InMotion-app readout |
| 2 | i16 LE | Current (signed) | raw / 100, A | high, matches at idle |
| 8 | i16 LE | Speed (signed) | raw / 100, km/h | high, labelled riding capture, reverse riding decodes correctly only as signed |
| 12 | i16 LE | Torque (signed) | raw / 100, N·m | medium, labelled capture: +5.05 at idle, -6.97 in reverse, +12.33 transitioning |
| 14 | i16 LE | PWM (signed) | raw / 100, percent | medium, labelled capture: 1.75 % idle, 1.70-1.78 % rolling |
| 20 | u16 LE | Battery 1 percent | raw / 100, percent | high, matches per-pack on-screen value |
| 22 | u16 LE | Battery 2 percent | raw / 100, percent | high, matches per-pack on-screen value |
| 28 | u8 | MOS temperature | direct °C | high, three labelled samples, exact match |
| 31 | u8 | Motor temperature | `(byte − 145) / 1.5` °C | high, within 0.3 °C across 9 samples in two logs |
| 58 | u32 LE | Lifetime odometer | raw / 100, km | high, three labelled riding samples, within rounding |
| 78 | u8 | IMU temperature | `62 − byte` °C | low, only 3 data points, inverted-scale fit is unusual but matches |
| 80 | u8 | PC mode | `0x49` = engaged, `0x00` = parked, `0xFD`/`0xFE` = pre-auth | high, tracks labelled park/sport toggle window |

Fields not in the table above are not yet decoded. The parser leaves
them at default so the dashboard reads blank rather than guessing.

Battery percent shown to the user is the rounded average of the two
per-pack fields. When the per-pack reads are zero (e.g. partial early
frames), the parser falls back to a linear voltage estimate on the
56-cell pack curve (3.0–4.2 V per cell → 165–235 V end-to-end).

The motor formula saturates at 73 °C (byte 255); PWM-event spikes
that briefly exceed this just clip. The MOS plausibility window
is `5..120 °C`; the IMU plausibility window is `5..62 °C` (the
inverted formula's valid input range).

Headlight state is not reliably reported in the realtime stream on
production P6 firmware; preview builds tried byte 84 bit 1 but the
value was sticky on user hardware. The repository tracks the user's
intent locally with optimistic toggling.

### 7.3 P6 settings layout (sub `0x20` with arg `0x20`)

Reply is a 51-byte body; the parser strips the leading `0x20` sub-cmd
echo and reads (offsets into the stripped buffer):

| Offset | Size | Field | Units |
| --- | --- | --- | --- |
| 8 | u16 LE | Tiltback set speed | raw / 100, km/h |
| 10 | u16 LE | Speed limit alarm | raw / 100, km/h |
| 14 | u16 LE | PWM tilt-back limit | raw / 100, percent |
| 16 | u16 LE | PWM level 1 alarm | raw / 100, percent |
| 18 | u16 LE | PWM level 2 alarm | raw / 100, percent |

An earlier build read tiltback at d\[12..13\]; that only worked when
the user's tiltback equalled their alarm (the value at d\[8..9\]
happened to mirror d\[10..11\] in the test set). The labelled capture
in `docs/P6_CAPTURE_LABELS.md` pinned it to d\[8..9\].

### 7.4 P6 control sub-commands

| Sub | Name | Data layout | Notes |
| --- | --- | --- | --- |
| `0x21` | SET_MAX_SPEED | `v_lo v_hi` (u16 LE × 100, km/h) | 2-byte payload only, no alarm. `60 21` alone is sufficient and persists across reboots |
| `0x24` | SET_SPEED_CLAMP_25 | single byte 0 / 1 | "Speed Clamp at 25 km/h" safety toggle |
| `0x25` | SET_PEDAL_HARDNESS | `[live, committed]` (each 0–100) | App sends both bytes; we use the same value in both |
| `0x2F` | SET_AUTO_HEADLIGHT | single byte 0 / 1 | When ON, the wheel decides; when OFF, `0x50` controls manually |
| `0x3E` | SET_ALARM_SPEED | `v_lo v_hi 00 00` (u16 LE × 100, km/h) | Verified at 13679 = 136.79 km/h = 85 mph on the labelled capture |
| `0x4C` | SET_PWM_THRESHOLDS | three u16 LE values (tilt, alarm1, alarm2; each × 100) | One packet sets all three |
| `0x50` | SET_LIGHT | `[on, on]` (two bytes, mirrored) | V14's 1-byte form is silently ignored |
| `0x51` | PLAY_SOUND | `[0x18, 0x01]` | V14's `[0x02, 0x64]` is silently ignored |

There is **no** flash-commit packet for max-speed. An earlier build
sent `60 3e [tilt 00 00]` after each `60 21` write, believing it was
a commit; re-analysis of the labelled capture showed `60 3e` is the
alarm-speed setter, not a commit, and the InMotion app only fires it
to clamp `alarm ≤ max` after a downward drag. Sending the redundant
write was overwriting alarm with tiltback transiently.

### 7.5 P6 detailed-data layout (sub `0x04`)

Response is `21 02 84 [86-byte body]`. The parser strips the `02 84`
routing and reads (offsets into the body):

| Offset | Field | Decoding | Notes |
| --- | --- | --- | --- |
| 58 | MOS temperature | `°F = byte − 126` → °C | Verified 72 °F = byte 0xC6 in labelled capture |
| 59 / 61 / 63 / 65 / 62 / 66 | Other thermistors | same `°F = byte − 126` → °C, filtered to 0..120 °C plausible range | First two values that pass the filter are reported as motor / driver-board |

Offsets 60 and 64 were padding in the labelled capture. The
detailed-data poll is currently disabled by the adapter (`pollStats`
returns null) because the realtime stream's body\[28\] / body\[31\] /
body\[78\] expose the same temperatures more reliably; the detailed
endpoint is documented here for completeness but its variable layout
was the source of an earlier "blinking 0 / value" temperature bug.


## 8. V12 deviations (vs V14)

| Topic | V14 | V12 HS / HT / PRO |
| --- | --- | --- |
| Realtime field layout | section 4.1 | section 4.2, tighter packing, single battery field |
| Settings field layout | section 5.5, tiltback at offset 0 | section 5.5, tiltback at offset 9 |
| SET_MAX_SPEED payload | 4 bytes (tilt + alarm) | 2 bytes (tilt only) |
| Alarm threshold write | included in SET_MAX_SPEED | separate sub-cmd `0x3e`, two tiers in one packet |
| SET_LIGHT payload | 1 byte `[on]` | 2 bytes `[low, high]`, V14 single-byte form is ignored |
| Horn opcode | `playBeep` `[0x51, 0x02, 0x64]` | `playSound` `[0x51, 0x18, 0x01]` |
| Temperature offset constant in `parseTemperature` | shared | shared (`176`), but always-zero MOS / lamp slots at offsets 42 / 46 must be skipped |
| Lock state in SETTINGS | byte 31 bit 2 | not reliably exposed; track locally |

V11 sits between V14 and V12 HS/HT/PRO in command dispatch: it uses
the V12 short SET_MAX_SPEED form (`maxSpeedHasAlarms = false`) but
the V14 single-byte SET_LIGHT form (`usesV12LightForm = false`).
V13 / V13 PRO / V11y / V12S / V9 use the V14 long SET_MAX_SPEED form;
V12S and V9 still need the V12 `playSound` horn opcode.


## 9. Capability summary

`WheelCapabilities.INMOTION_V2` defaults are model-specific; the
adapter resolves per-model flags through the `InMotionV2Model` enum
table after carType lands (or, for the P6, immediately from the BLE
name).

| Field | V14 family | V13 / V11y | V12 HS / HT / Pro | V11 | P6 |
| --- | --- | --- | --- | --- | --- |
| `hasHorn` | yes (`playBeep`) | yes (`playBeep`) | yes (`playSound`) | yes (`playSound`) | yes (`playSound`) |
| `hasLight` | yes (1-byte) | yes (1-byte) | yes (2-byte beams) | yes (1-byte) | yes (2-byte mirrored) |
| `hasLock` | yes (EXTENDED) | yes (EXTENDED) | yes (EXTENDED) | yes (DEFAULT or EXTENDED) | yes (EXTENDED) |
| `hasMaxSpeed` | yes (tilt + alarm in one) | yes (tilt + alarm in one) | yes (tilt only; alarm via `0x3e`) | yes (tilt only) | yes (tilt only) |
| `hasAlarmSpeed` | included in max-speed | included in max-speed | separate `0x3e` packet | not exposed | separate `0x3e` packet |
| `hasVolume` | yes (`0x26`) | yes | yes | yes | unknown, `0x26` not yet tried |
| `hasDRL` | yes (`0x2D`) | yes | no | no | no (auto-headlight instead, `0x2F`) |
| `needsAuthForLock` | yes (V12 and newer) | yes | yes | no on V11 | yes for **every** control write |
| `requiresConnectAuth` | no | no | no | no | yes |

V11y is grouped with V13 because they share the `playBeep` horn and
the long-form SET_MAX_SPEED packet. V9 and V12S use the long-form
max-speed but the older `playSound` horn.


## 10. Open questions

- V14 realtime offsets 4–7, 24–27, 30–33, 42–49, 52–57, 64–73, and
  77+ carry data the parser does not yet read. Some are likely
  per-cell battery telemetry (battery 1/2 voltage, individual
  currents) and per-side motor stats; capture under labelled riding
  to pin down.
- V14 work-mode byte at offset 74 has no documented mapping past the
  low 3 bits. The full byte may carry mc-mode / motor-active /
  charging like V12's offset 54, but is not parsed.
- V12 work-mode bits 3–5 (`mcMode`) and bit 7 (`charging`) are read
  but not surfaced in the dashboard; no labelled capture distinguishes
  the mcMode values yet.
- V12 lock-state in SETTINGS, no reliable public reference; our
  parser keeps `lockState = 0`. Likely needs a labelled V12
  lock/unlock capture to confirm.
- P6 ride history (sub `0x10`) and total-stats (sub `0x11`) have a
  TLV-style layout that has not been reverse-engineered. Polling
  them currently produces unparsed bytes.
- P6 IMU temperature formula `62 − byte` is built on three data
  points only and is an unusual inverted-scale fit. Could be
  coincidence; needs more labelled samples across a wider
  temperature range to confirm.
- P6 detailed-data temperature offsets past byte 58 are guessed
  by walking offsets 59 / 61 / 63 / 65 / 62 / 66 and filtering to a
  plausible range. The exact motor / driver-board offsets are
  pinned in the labelled capture but the layout differs across the
  firmware variants we've seen.
- P6 headlight state in the realtime stream, preview4's "byte 84
  bit 1" rule worked in a single labelled capture but the value is
  sticky on production firmware, so the repository now tracks user
  intent locally. A real stream-side state byte has not been found.
- P6 volume control sub-cmd has not been verified. The V14 `0x26`
  builder is the safe guess but `setVolume` has not been exercised
  on P6 hardware in a labelled capture.
- The carType `modelId` table covers the models in
  `InMotionV2Model.kt`; other variants reported in the field
  (V14 50K, V14 export variants) fall through to the default V14
  parser, which is the safe fallback but may misread firmware-
  specific fields.
- Per-model auth requirements: V14 family wheels need the auth
  handshake only for `SET_LOCK`; V12+ wheels also need it for any
  CONTROL write touching a security-sensitive setting; P6 needs it
  for the entire control endpoint. The exact list of "security-
  sensitive" sub-commands on V12 / V13 is not fully documented.


## 11. Attribution

Protocol reference (upstream, GPLv3):
<https://github.com/Wheellog/wheellog.android/blob/master/app/src/main/java/com/cooper/wheellog/utils/InmotionAdapterV2.java>

The V14 / V13 / V12 / V11 packet shape, carType registry, command
catalog, and realtime / settings offsets were cross-referenced
against the upstream reference and Electric Unicycle Forum threads.

The P6 layout is original work: every P6 offset, sub-command and
formula in section 7 was derived from labelled BLE captures recorded
during user-led testing (`docs/P6_CAPTURE_LABELS.md`). The P6 is not
covered by any public open-source reference we could find.

This document is original prose and tables; no third-party GPL source
is reproduced.

License note: this app is MIT-licensed. Quoting exact byte offsets,
sub-cmd values and magic-byte sequences is fact, not copyrightable
expression, those are reproduced freely. No GPLv3 source code is
included.

V8 / V8F / V8S / V10 family / V5 / L6 / R-series / V3 use the
separate V1 protocol family (split `0xFFE0` / `0xFFE5` services,
CAN-over-BLE framing); see `inmotion_v1.md` and
`project_v14_protocol.md` for those.
