# Ninebot / Segway BLE Protocol

Protocol reference for Ninebot and Segway-Ninebot electric unicycles.
Implementation target: Kotlin parser and command builder in EUC Planet.

This document describes the wire format only. No code is reproduced from
upstream projects. See "Attribution" at the end of the file for sources.

Two protocol families are covered here:

1. **Ninebot Z** (Part A) -- modern, encrypted. Z6, Z10, Segway-rebranded
   Z-series, plus some E+ / Mini Plus firmwares that ship the new stack.
2. **Ninebot legacy** (Part B) -- older, unencrypted. One E, One E+,
   One S2, Mini, Mini Pro.

They share neither the GATT profile nor the framing. The adapter must
detect which family the wheel speaks and dispatch accordingly.

Status: research-grade. The Ninebot Z encryption section is complete
enough to implement; live-data offsets between Z6 and Z10 may differ
in detail. Items marked "Unconfirmed" should be validated against a
labelled BLE capture before being relied on in production code.


-------------------------------------------------------------------------
# PART A. Ninebot Z (Z6 / Z10 / new-stack E+)
-------------------------------------------------------------------------


## 1. GATT profile (Ninebot Z)

Ninebot Z uses a Nordic-UART-style profile.

| Role | UUID |
| --- | --- |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| Notify (wheel -> phone) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| Write (phone -> wheel) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` |

Notes:
- Distinct write and notify characteristics, unlike legacy Ninebot.
- Subscribe to notifications on `...0003...` via the standard CCCD
  before sending any commands.
- Default ATT MTU (payload 20 bytes) is sufficient. Settings fit in one
  notification; live-data replies span two.


## 2. BLE name pattern (Ninebot Z)

Advertised name formats observed in the wild:

| Pattern | Example | Notes |
| --- | --- | --- |
| `Ninebot Z<...>` | `Ninebot Z10`, `Ninebot Z6` | Most common |
| `ZN<serial>` | `ZN1234567` | Some Z6 firmwares strip the brand prefix |
| `Segway Z<...>` | `Segway Z10` | Region-specific re-branding |
| `MiniPLUS<serial>` | `MiniPLUS123456` | Mini Plus uses Z-protocol |

Detection strategy:
1. Probe for the Nordic-UART service `6e400001-...`; its presence is
   the strongest single signal of a Z-protocol wheel.
2. Confirm by attempting the BLE-version handshake (section 5). Reply
   from src `0x14`, param `0x68` confirms Z protocol.
3. Name alone is not reliable: some Mini/S2 firmwares speak Z despite
   advertising legacy-style names.


## 3. Frame structure (Ninebot Z)

```
offset  0  1   2     3    4    5    6        N+5   N+6  N+7  N+8
        5A A5 <len> <src><dst><cmd><param> .. <crc_lo><crc_hi>
                                  |<--- N data bytes --->|
```

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | u8 magic[0] | `0x5A` |
| 1 | u8 magic[1] | `0xA5` |
| 2 | u8 length | Number of data bytes after the param byte (`N`). Excludes header, magic, CRC |
| 3 | u8 src | Source address (see section 4) |
| 4 | u8 dst | Destination address |
| 5 | u8 cmd | Command opcode (Read / Write / Get / GetKey, see section 4) |
| 6 | u8 param | Parameter ID (see section 7) |
| 7..6+N | N bytes | Command-specific data payload |
| 7+N | u8 crc_lo | CRC byte 0, little-endian |
| 8+N | u8 crc_hi | CRC byte 1 |

Total on-wire frame size: `N + 9` bytes.

Byte order:
- All multi-byte fields use **little-endian** unless specified otherwise.
- 16-bit CRC stored as **u16 LE**.
- 32-bit telemetry fields stored as **u32 LE**.

Checksum:

```
sum = 0
for byte in [length, src, dst, cmd, param, ...data]:   # N+5 bytes
    sum = (sum + byte) & 0xFFFF
crc = sum XOR 0xFFFF                                   # i.e. ones complement
```

Equivalent statement: CRC is the bitwise NOT of the 16-bit running sum
of every byte in the frame except the two magic bytes and the CRC bytes
themselves. Stored little-endian.

Important: the CRC is computed over the **plaintext** payload. After
encryption (section 6), the CRC bytes themselves are also XOR'd along
with the rest. The receiver must therefore decrypt first, then verify.


## 4. Address space and command opcodes (Ninebot Z)

### Addresses

| Name | Value | Direction |
| --- | --- | --- |
| BMS1 | `0x11` | Wheel battery 1 |
| BMS2 | `0x12` | Wheel battery 2 |
| Controller | `0x14` | Main MCU |
| KeyGenerator | `0x16` | Encryption-key oracle, replies once during handshake |
| App | `0x3E` | This phone |

The phone uses `0x3E` as its source address on every outbound frame.
`dst` selects which on-wheel subsystem the frame is addressed to.

### Command opcodes

| Name | Value | Use |
| --- | --- | --- |
| Read | `0x01` | Phone reads a parameter |
| Write | `0x03` | Phone writes a parameter |
| Get | `0x04` | Phone subscribes to a parameter (live data) |
| GetKey | `0x5B` | Phone requests session encryption key from the KeyGenerator |

Replies from the wheel reuse the same opcode that triggered them, with
`src` and `dst` swapped.


## 5. Connection state machine (Ninebot Z)

After the GATT connection and CCCD subscription are up, the adapter
walks a fixed sequence of read requests. Each step waits for the
matching reply before advancing.

| State | Action | Frame |
| --- | --- | --- |
| 0 | getBleVersion | Read `0x68` from Controller `0x14`, data `{0x02}` |
| 1 | getKey | GetKey from KeyGenerator `0x16`, param `0x00`, data empty |
| 2 | getSerialNumber | Read `0x10` from Controller |
| 3 | getFirmwareVersion | Read `0x1A` from Controller |
| 4 | getParams1 | Read `0x70`/`0x72`/`0x74`/`0x7C`..`0x7F` (lock, alarms) |
| 5 | getParams2 | Read `0xC6`..`0xCE`, `0xD2`, `0xD3` (LED, pedal, drive flags) |
| 6 | getParams3 | Read `0xF5` (speaker volume) |
| 7..9 | BMS1 dump | Conditional on bmsMode flag |
| 10..12 | BMS2 dump | Conditional on bmsMode flag |
| 13 | live-data loop | Repeated Get of `0xB0` |

Once state 13 is reached, the adapter loops on Get-`0xB0` (live data)
at the desired refresh rate. Settings reads/writes can be interleaved.


## 6. Encryption (Ninebot Z) [CRITICAL]

Ninebot Z uses a **stream cipher built from a 16-byte session key**
exchanged once at connect. Every frame after the key exchange is
encrypted byte-for-byte with this fixed key. There is **no IV, no
nonce, no key rotation, and no authentication tag**. It is XOR with a
repeated key.

### 6.1. Key exchange

After the BLE-version handshake (state 0), the phone sends:

```
plaintext frame:
  5A A5 00 3E 16 5B 00 <crc_lo> <crc_hi>

len = 0x00       (no data bytes)
src = 0x3E       (App)
dst = 0x16       (KeyGenerator)
cmd = 0x5B       (GetKey)
param = 0x00     (GetKey)
crc = ones_complement(sum(0x00, 0x3E, 0x16, 0x5B, 0x00))
    = ~(0x00 + 0x3E + 0x16 + 0x5B + 0x00) & 0xFFFF
    = ~0x00AF
    = 0xFF50      -> on the wire 0x50 0xFF
```

This first GetKey frame is sent in **plaintext** (no encryption applied).

The wheel responds from the KeyGenerator address `0x16` with a frame
whose 16 data bytes are the session key. The data field is exactly
**16 bytes** of pseudo-random material; this is the `gamma` keystream.

The reply itself is also **plaintext** (the wheel cannot encrypt a
response to a key request before the phone has the key; the phone needs
plaintext to read it).

### 6.2. Cipher

After the key exchange, both the phone and the wheel apply the same
XOR transform to **every** frame, in both directions. The cipher
operates on the buffer that starts immediately after the two magic
bytes:

```
encrypted = frame[2..end]            # length, addresses, cmd, param, data, CRC
encrypted[0] is left UNTOUCHED       # the length byte stays in plaintext
for j in 1 .. encrypted.length - 1:
    encrypted[j] ^= gamma[(j - 1) % 16]
```

Equivalent statement, expressed against the on-wire frame indices:

| On-wire index | Field | Encrypted? | Keystream byte |
| --- | --- | --- | --- |
| 0 | magic `0x5A` | No | -- |
| 1 | magic `0xA5` | No | -- |
| 2 | length | No | -- |
| 3 | src | Yes | `gamma[0]` |
| 4 | dst | Yes | `gamma[1]` |
| 5 | cmd | Yes | `gamma[2]` |
| 6 | param | Yes | `gamma[3]` |
| 7 | data[0] | Yes | `gamma[4]` |
| ... | ... | Yes | `gamma[(i-3) mod 16]` for on-wire index `i` |
| 7+N | crc_lo | Yes | `gamma[(N+4) mod 16]` |
| 8+N | crc_hi | Yes | `gamma[(N+5) mod 16]` |

So **two header bytes (magic) and the length byte are plaintext** on
the wire. Everything else is XOR'd, **including the CRC**. Decryption
on the receive side is the same operation (XOR is involutive); decrypt
first, then verify the CRC.

### 6.3. Properties

- Key length: **16 bytes** (128 bits).
- Cipher: **XOR with repeating fixed key**, period 16. Not AES, not RC4.
- IV / nonce: **none**. Keystream restarts at offset 0 for every frame.
- Key rotation: **none observed**. Key persists for the BLE session and
  is replaced only on disconnect / reconnect.
- Authentication: **none**. The CRC is a sum-checksum, not a MAC.

Confidentiality-only obfuscation. We need to interoperate with it; we
do not need to defend it.


## 7. Parameter IDs (Ninebot Z)

| Param | Hex | Direction | Notes |
| --- | --- | --- | --- |
| GetKey | `0x00` | RW | Used only with cmd `0x5B` |
| SerialNumber | `0x10` | R | ASCII serial, length varies |
| Firmware | `0x1A` | R | Version word |
| BleVersion | `0x68` | R | First handshake step |
| LockMode | `0x70` | RW | `0x01` = lock engaged, `0x00` = unlocked |
| LimitedMode | `0x72` | RW | Speed-limit feature on/off |
| LimitModeSpeed | `0x74` | RW | u16 LE, value = km/h * 100 |
| Alarms | `0x7C` | RW | Bitfield, which alarm slots are armed |
| Alarm1Speed | `0x7D` | RW | u16 LE, km/h * 100 |
| Alarm2Speed | `0x7E` | RW | u16 LE, km/h * 100 |
| Alarm3Speed | `0x7F` | RW | u16 LE, km/h * 100 |
| LiveData | `0xB0` | R / Get | Telemetry, see section 8 |
| LedMode | `0xC6` | RW | u8, mode 0..7 |
| LedColor1 | `0xC8` | RW | RGB triple |
| LedColor2 | `0xCA` | RW | RGB triple |
| LedColor3 | `0xCC` | RW | RGB triple |
| LedColor4 | `0xCE` | RW | RGB triple |
| PedalSensitivity | `0xD2` | RW | u16 LE |
| DriveFlags | `0xD3` | RW | Bitfield, see below |
| SpeakerVolume | `0xF5` | RW | u8, written value is `volume << 3` |

DriveFlags (`0xD3`) bit layout (Unconfirmed):

| Bit | Meaning |
| --- | --- |
| 0 | DRL (daytime running light) |
| 1 | Tail light |
| 2 | Auxiliary light, role unclear |
| 3 | Strain gauge / pedal pressure sensor |
| 4 | Brake assist |


## 8. Realtime telemetry (Ninebot Z, param `0xB0`)

Sent by the wheel as a single response to `Get 0xB0`. The data payload
is at least 28 bytes; some firmwares append additional fields.

All offsets below are relative to the **start of the data payload**
(i.e. on-wire offset 7 = data[0]).

| Offset | Field | Type | Scale | Units |
| --- | --- | --- | --- | --- |
| 0..1 | reserved / status | u16 LE | -- | -- |
| 2..3 | reserved / state | u16 LE | -- | -- |
| 4..5 | reserved | u16 LE | -- | -- |
| 6..7 | reserved | u16 LE | -- | -- |
| 8..9 | battery_pct | u16 LE | direct | % (0..100) |
| 10..11 | speed | u16 LE | div 100 | km/h |
| 12..13 | reserved | u16 LE | -- | -- |
| 14..17 | total_distance | u32 LE | direct | meters |
| 18..19 | trip_distance | u16 LE | x10 | meters (multiply by 10) |
| 20..21 | reserved | u16 LE | -- | -- |
| 22..23 | temperature | i16 LE | div 10 | degrees C |
| 24..25 | voltage | u16 LE | div 100 | V (Unconfirmed: some firmwares use direct decivolts) |
| 26..27 | current | i16 LE | div 100 | A (positive = drive, negative = regen) |

Notes:
- `battery_pct` is reported by the wheel; this is the wheel's own SOC
  estimate, not a calculation from voltage.
- `speed` is unsigned. Reverse motion is not separately encoded.
- `temperature` is the controller MOSFET temperature. BMS pack
  temperatures come from BMS1/BMS2 frames separately.
- For Z6 vs Z10 differences see section 9.

How live data is requested:

- **Polled**: the phone repeats `Get 0xB0` at its desired cadence
  (typically 5..10 Hz). The wheel does not push unsolicited live data.
- This is unlike KingSong (which pushes) and InMotion V14 (which mixes).


## 9. Settings dump and write commands (Ninebot Z)

To write a setting, send a `cmd=0x03` (Write) frame with the matching
`param` and a payload of the appropriate size.

| Setting | Frame template | Encoding |
| --- | --- | --- |
| Lock | `cmd=0x03 param=0x70 data={value, 0x00}` | `value=1` lock, `value=0` unlock |
| Speed-limit on/off | `cmd=0x03 param=0x72 data={value, 0x00}` | `value=1` enable |
| Speed-limit value | `cmd=0x03 param=0x74 data={lo, hi}` | u16 LE, km/h * 100 |
| Alarms armed | `cmd=0x03 param=0x7C data={lo, hi}` | bitfield: bit 0..2 enable alarm 1..3 |
| Alarm 1 speed | `cmd=0x03 param=0x7D data={lo, hi}` | u16 LE, km/h * 100 |
| Alarm 2 speed | `cmd=0x03 param=0x7E data={lo, hi}` | u16 LE, km/h * 100 |
| Alarm 3 speed | `cmd=0x03 param=0x7F data={lo, hi}` | u16 LE, km/h * 100 |
| LED mode | `cmd=0x03 param=0xC6 data={mode}` | u8, 0..7 |
| LED color slot 1..4 | `cmd=0x03 param=0xC8/CA/CC/CE data={r,g,b}` | 3 bytes RGB |
| Pedal sensitivity | `cmd=0x03 param=0xD2 data={lo, hi}` | u16 LE |
| Drive flags | `cmd=0x03 param=0xD3 data={flags, 0x00}` | bitfield, see section 7 |
| Speaker volume | `cmd=0x03 param=0xF5 data={vol << 3, 0x00}` | u8, value left-shifted 3 |

To read the same setting back, replace `cmd=0x03` with `cmd=0x01`
(Read) and send no data.


## 10. Lock / unlock (Ninebot Z)

Ninebot Z exposes a software lock through param `0x70`:

```
to lock:    Write 0x70 with data {0x01, 0x00}
to unlock:  Write 0x70 with data {0x00, 0x00}
to query:   Read  0x70 (no data)
```

Notes:
- The official app gates this behind a numeric PIN enforced **in the
  app**, not the wheel. The wheel accepts a raw lock/unlock write from
  any party that completed the encrypted handshake; no on-wheel PIN.
- Practical security therefore comes from the encryption: a bystander
  who has not done the GetKey handshake cannot send a valid frame
  because the wheel will fail the CRC after XOR.
- Our app may surface a UX-level PIN to mirror the official app, but
  the wheel does not enforce it.


-------------------------------------------------------------------------
# PART B. Ninebot legacy (One E / E+ / S2 / Mini)
-------------------------------------------------------------------------


## 11. GATT profile (Ninebot legacy)

| Role | UUID |
| --- | --- |
| Service | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| Notify | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| Write | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` |

Notes:
- Single characteristic `0xFFE1` for both write and notify, like
  KingSong. Subscribe via CCCD then write to the same handle.
- Default ATT MTU is sufficient.


## 12. BLE name pattern (Ninebot legacy)

| Pattern | Example | Notes |
| --- | --- | --- |
| `Ninebot One E+` | `Ninebot One E+12345` | Most common One E+ form |
| `Ninebot One E` | `Ninebot One E1234` | Original One E |
| `Ninebot S2` | `Ninebot S2 ABCDE` | One S2 |
| `Ninebot Mini ...` | `Ninebot Mini Pro` | Mini / Mini Pro share legacy stack |

Detection strategy:
1. Probe for service `0xFFE0` containing characteristic `0xFFE1`.
2. If the device name starts with `Ninebot One`, `Ninebot S2`, or
   `Ninebot Mini`, treat as legacy.
3. After connect, send a Read of param `0x68` (BleVersion). On legacy
   the reply is a string identifier ("S2", "Mini", "" / empty for
   "default" One E+ etc.) which selects the address subset (section 13)
   and live-data offset map (section 15).


## 13. Frame structure (Ninebot legacy)

```
offset  0  1   2     3    4    5     6        N+3   N+4  N+5
        55 AA <len> <src><dst><param> .. <crc_lo><crc_hi>
                                |<--- N data bytes --->|
```

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | u8 magic[0] | `0x55` |
| 1 | u8 magic[1] | `0xAA` |
| 2 | u8 length | Number of data bytes after the param byte (`N`) |
| 3 | u8 src | Source address |
| 4 | u8 dst | Destination address |
| 5 | u8 param | Parameter ID (no separate cmd byte) |
| 6..5+N | N bytes | Command-specific data |
| 6+N | u8 crc_lo | u16 LE checksum |
| 7+N | u8 crc_hi | |

Total on-wire frame size: `N + 6` bytes (the unpacker uses `len + 6`
as the expected total once it has read `length`, accounting for two
magic + length + addresses + param + 2 CRC).

Differences vs Ninebot Z:
- Magic is **`0x55 0xAA`** (Z is `0x5A 0xA5`).
- There is **no separate `cmd` byte**. Read vs Write is encoded
  implicitly by which `param` is targeted and by the request/response
  direction.
- **No encryption**. Legacy frames are plaintext on the wire.
- One fewer byte of overhead (no cmd byte).

Checksum: same algorithm as Z. Sum every byte from `length` through
the last data byte, then ones-complement (XOR with `0xFFFF`), store
little-endian.


## 14. Address space and parameters (Ninebot legacy)

Addresses depend on the protocol-version string returned by the
BleVersion read (section 12).

| Name | Default | S2 | Mini |
| --- | --- | --- | --- |
| Controller | `0x01` | `0x01` | `0x01` |
| KeyGenerator | `0x16` | `0x16` | `0x16` |
| App | `0x09` | `0x11` | `0x0A` |

Parameter IDs in use:

| Param | Hex | Notes |
| --- | --- | --- |
| SerialNumber | `0x10` | ASCII |
| Firmware | `0x1A` | Version word |
| ActivationDate | `0x69` | Read-only |
| BleVersion | `0x68` | Returns variant string ("", "S2", "Mini") |
| LiveData | `0xB0` | Telemetry, see section 15 |

Legacy exposes **far fewer parameters** than Z. There is no documented
LED, no separate alarm slots, no speaker control through this stack.


## 15. Realtime telemetry (Ninebot legacy, param `0xB0`)

Live-data payload is around 32 bytes. Offsets into the data payload:

| Offset | Field | Type | Scale | Units | Notes |
| --- | --- | --- | --- | --- | --- |
| 8..9 | battery_pct | u16 LE | direct | % | |
| 10..11 | speed (Mini) | i16 LE | div 10 | km/h | Mini only |
| 14..17 | total_distance | u32 LE | direct | meters | |
| 22..23 | temperature | i16 LE | div 10 | degrees C | |
| 24..25 | voltage | u16 LE | div 100 | V | Unconfirmed -- some captures suggest direct decivolts |
| 26..27 | current | i16 LE | div 100 | A | |
| 28..29 | speed (S2) | i16 LE | direct | km/h * 100 | S2 only |

Per-variant quirks:
- **One E / One E+**: speed at offset 10..11 like Mini, no `/10` divisor.
  (Unconfirmed for E+ on newest firmware, which may use the S2 layout.)
- **S2**: speed at offset 28..29, scale `/100`.
- **Mini**: speed at offset 10..11, scale `/10`.

Polling cadence: a 5-step transmit loop every 25 ms (serial, firmware,
live, live, live) gives roughly 24 Hz live-data poll. No unsolicited
push from the wheel.


## 16. Settings (Ninebot legacy)

The legacy stack does not expose lock, alarms, light, volume, or LED
through any documented parameter ID. Settings writes are not in scope
for legacy support. If a user needs to change limits on a One E / S2,
they must use the Ninebot mobile app over Bluetooth Classic / BLE
side-channels not covered here.

Open question: there is forum chatter about a maintenance / "diag"
parameter that exposes calibration; we have not confirmed it is
reachable from the BLE stack and have no opcode for it. See section 19.


## 17. Lock / unlock (Ninebot legacy)

**Not supported**. Legacy Ninebot does not expose a software lock over
this BLE stack. Hardware ignition / key-card flow on Mini Pro is
out-of-band.


-------------------------------------------------------------------------
# PART C. Cross-cutting
-------------------------------------------------------------------------


## 18. Per-model quirks summary

| Model | Family | Notes |
| --- | --- | --- |
| Ninebot One E | Legacy | Default address subset (App `0x09`). Speed at offset 10..11. No settings writes. |
| Ninebot One E+ | Legacy (mostly) | Same as One E. Some late firmwares may speak Z; detect by service UUID. |
| Ninebot One S2 | Legacy | App address `0x11`. Speed at offset 28..29. |
| Ninebot Mini / Mini Pro | Legacy | App address `0x0A`. Speed at offset 10..11, scale div 10. |
| Mini Plus | Z (Unconfirmed) | Some captures show Nordic-UART service; route to Z if `6e400001-...` is present. |
| Ninebot Z6 | Z | Standard Z layout. |
| Ninebot Z10 | Z | Standard Z layout. Higher voltage (84 V class), so confirm voltage scale on a labelled capture. |
| Segway-Ninebot Z (rebranded) | Z | Same protocol, only the marketing name changes. |


## 19. Capability summary

For our `WheelCapabilities` struct, the two families map to:

### Ninebot Z (Z6, Z10, new-stack E+)

| Field | Value | Notes |
| --- | --- | --- |
| `hasHorn` | false | No documented horn opcode in the public protocol |
| `hasLight` | true | DRL via DriveFlags `0xD3` bit 0; LED mode via `0xC6` |
| `hasLock` | true | Param `0x70`, see section 10 |
| `hasMaxSpeed` | true | LimitModeSpeed `0x74` (km/h * 100) |
| `hasAlarmSpeed` | true | Three independent slots `0x7D`/`0x7E`/`0x7F` |
| `hasVolume` | true | SpeakerVolume `0xF5`, value `<< 3` |
| `hasDRL` | true | DriveFlags `0xD3` bit 0 |
| `needsAuthForLock` | false | Wheel does not enforce a PIN; encryption is the gate. App-side PIN is UX, not security |

Bonus capabilities not in the standard struct: LED color customisation
(four slots), tail light, brake assist toggle, pedal sensitivity tune.

### Ninebot legacy (One E, One E+ legacy, S2, Mini)

| Field | Value | Notes |
| --- | --- | --- |
| `hasHorn` | false | Not exposed |
| `hasLight` | false | Not exposed via this BLE stack |
| `hasLock` | false | Not exposed |
| `hasMaxSpeed` | false | No documented opcode |
| `hasAlarmSpeed` | false | No documented opcode |
| `hasVolume` | false | Not exposed |
| `hasDRL` | false | Not exposed |
| `needsAuthForLock` | n/a | Lock not supported |

Legacy support in EUC Planet should be treated as **read-only
telemetry**: speed, voltage, current, battery, temperature, total
distance. No settings writes.


## 20. Open questions and uncertainty

Confirm against a labelled capture from a known-good wheel before
trusting any of these:

1. **Z voltage scale.** Existing references are inconsistent (`/100` in
   some places, raw in others). Z10 is nominally 84 V; capture and fix.
2. **Z battery field source.** Whether offset 8..9 is the wheel's SOC
   or BMS pack-1 SOC. BMS dumps may contradict the LiveData value.
3. **DriveFlags bit map.** Public references mark the `0xD3` third bit
   with a "?". Toggle each bit on a Z10 to confirm meaning.
4. **Z BleVersion reply format.** Treated as opaque "ready" signal; the
   reply layout is not well documented but is not needed.
5. **Z key entropy.** Whether the 16-byte key is per-session random or
   a function of the wheel serial. Two connect cycles would settle it.
6. **Legacy speed offset on E+.** Late-firmware E+ captures sometimes
   show too-low values; the field may have moved to offset 28..29.
7. **Legacy current sign.** Z uses positive = drive; legacy presumed
   the same but Unconfirmed.
8. **Mini Plus family.** Reported on forums to advertise Nordic-UART,
   suggesting Z protocol, but no first-party capture confirms.
9. **Lock-while-moving.** Wheel reaction to `0x70 -> 0x01` while moving
   is undocumented. Official app refuses; we should too.
10. **CRC endianness.** All public references say u16 LE; a minority
    of forum posts say BE. Code is unambiguously LE, we go with LE.
11. **MTU for BMS frames.** BMS dumps on Z (states 7..12) may exceed
    20-byte ATT payload and require an MTU bump like KingSong's BMS.


## 21. Attribution

Protocol references (upstream, GPLv3):
- Legacy (One E / E+ / S2 / Mini):
  <https://github.com/Wheellog/wheellog.android/blob/master/app/src/main/java/com/cooper/wheellog/utils/NinebotAdapter.java>
- Z encrypted variant:
  <https://github.com/Wheellog/wheellog.android/blob/master/app/src/main/java/com/cooper/wheellog/utils/NinebotZAdapter.java>

Cross-checked against forum write-ups on the Electric Unicycle Forum
(esp. Z10 BLE-stack discussions). All tables and offset maps are
re-described in this project's idiom (`u8`, `u16 LE`, `i16 LE`,
`0xA5`); no third-party GPL source is reproduced.

Ninebot Z encryption: GetKey opcode `0x5B`, the 16-byte gamma key,
and the XOR loop `data[j] ^= gamma[(j-1) % 16]`.
