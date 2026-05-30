# Garmin Varia BLE Protocol

Protocol reference for Garmin Varia rear-view radar / radar tail-light
devices (RTL515, RTL516, RVR315, RCT715, eRTL615, RearVue 820, RVR53320).
Implementation target: Kotlin adapter in `app/src/main/java/com/eried/eucplanet/ble/radar/VariaAdapter.kt`.

This document describes the wire format only. No code is reproduced from
upstream projects. See "Attribution" at the end of the file for sources.

Status: research-grade. Garmin's official protocol spec (`developer.garmin.com/radar-data-ble`)
is gated behind the **Garmin Radar Data BLE Program** NDA. Everything here
is the publicly-documented subset that open-source clients (pycycling,
harbour-tacho, hub-radar, this project) have converged on by reverse
engineering. Items marked "Unconfirmed" should be validated against a
labelled BLE capture before being relied on in production code.


## 1. GATT profile

| Role | UUID |
| --- | --- |
| Radar service (primary) | `6a4e3200-667b-11e3-949a-0800200c9a66` |
| Notify: threat measurement | `6a4e3203-667b-11e3-949a-0800200c9a66` |
| NDA: control / threat-level / light | `6a4e3201-…`, `6a4e3202-…`, others in the `6a4e32xx` family |
| Battery service (standard) | `0000180f-0000-1000-8000-00805f9b34fb` |
| Battery level char (standard) | `00002a19-0000-1000-8000-00805f9b34fb` |
| CCCD descriptor | `00002902-0000-1000-8000-00805f9b34fb` |

Notes:
- Public clients subscribe to **`…3203` only**. Every other characteristic
  in the `6a4e32xx` family (`…3201`, `…3202`, …) is part of the NDA
  surface; attempting to discover the GATT tree shows them but writing to
  them yields no documented behaviour.
- The threat-level byte that the Garmin head units render (none / medium
  / high) is not in `…3203`. Public clients derive it locally from
  distance + closing rate (see section 5).
- Light control is also NDA. There is **no documented BLE light-control
  path**; every public client that toggles Varia lights does so over
  **ANT+** instead, against the ANT+ Bicycle Light Profile (separate
  protocol, separate radio). Out of scope for this document.
- The standard 0x180F battery service is exposed on all firmware
  variants observed (RTL515 / RTL516 / RVR315). Subscribe to
  notifications on `0x2A19` to receive battery-level changes without
  polling. RCT715 (with integrated camera) and the newest RearVue 820
  are unconfirmed; assume the same layout until proven otherwise.


## 2. BLE name pattern

Advertised name prefixes observed in the wild:

| Prefix | Example | Notes |
| --- | --- | --- |
| `RTL` | `RTL515`, `RTL516` | Radar Tail-Light, the most common variant |
| `RVR` | `RVR315`, `RVR53320` | RearView Radar without integrated light |
| `RCT` | `RCT715` | RearView Camera + Tail-light (Varia eRTL camera) |
| `eRTL` | `eRTL615` | Newer RTL-class hardware |
| `Varia` | `Varia RearView 820` | RearVue 820 advertises with the brand prefix |

Detection strategy:
1. Match on the advertised name prefix (case-insensitive) to short-circuit
   service discovery for the obvious devices.
2. Optionally probe for service `6a4e3200-667b-11e3-949a-0800200c9a66`
   to catch any future model that uses a new advertised name but the
   same protocol family.


## 3. Frame structure (notify on `…3203`)

```
offset  0   1  2  3   4  5  6   7  8  9  ...
        HH  ID DI SP  ID DI SP  ID DI SP  ...
```

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | u8 header | Low nibble = fragment flag (see 3.1). High nibble = wrapping sequence counter (unused by this adapter). |
| 1..N | (u8, u8, u8) per vehicle | Repeated triplet: `(id, distance_m, approach_speed_kmh)` |

All multi-byte values: there are none. Every field is a single unsigned
byte. Kotlin's `Byte` is signed, so the adapter masks with `0xFF` to
interpret each octet as `u8`.

Vehicle fields:
- **id**: vendor track id (1..255). Stable across consecutive frames
  while a given vehicle remains in the radar's field. A missing id on a
  later frame means the radar has dropped the track (out of range or
  occluded). There is no explicit "dropped" event.
- **distance_m**: range from the rider's rear, in metres. Saturates at
  ~140 m (the advertised reach of the RTL515; longer-range variants
  may extend this).
- **approach_speed_kmh**: closing speed in km/h, positive when the
  vehicle is gaining on the rider. **Unconfirmed** under hard braking
  (does it go negative or saturate to 0?); the publicly-observed
  captures only show positive values.

Padding: when the radar tracks fewer vehicles than the maximum, the
trailing triple is sometimes (but not always) filled with `(0, 0, 0)`.
The adapter strips these so the UI doesn't render ghost cars at 0 m.

Checksum: **none**. The single header byte is the only framing. A
payload whose remaining length is not divisible by 3 is treated as
corrupt and discarded.


### 3.1 Fragmentation

The Varia firmware caps each BLE notification at **20 payload bytes**
even when a larger MTU has been negotiated. One notification therefore
carries at most **6 vehicles** (1 header + 6 × 3 = 19 bytes). When the
radar has 7+ vehicles in view it splits the logical frame across two
notifications:

| Header (low nibble) | Meaning |
| --- | --- |
| `0x0` | "More fragments coming". The receiver should buffer the payload and wait for the matching `_2`. |
| `0x2` | Final fragment, or standalone frame. The receiver concatenates any buffered `_0` payload, then parses triplets. |
| other | Reserved. Treat as standalone for forward-compat. |

Notes:
- The high nibble is a wrapping sequence counter. This adapter does not
  verify the counter ,  the `_0`/`_2` flag is sufficient and the seq
  counter only matters if you want to detect dropped frames.
- A `_0` arriving without its matching `_2` (BLE drop, rider in a dead
  spot) is recovered on the next logical frame: the next `_0` overwrites
  the buffer, the next `_2` is parsed in isolation.
- Treating each notification standalone causes the visible bug a tester
  reported as "6/1/6/1 vehicle-count jumping" on busy roads. This is
  what the fragmentation handling fixes.


## 4. Battery service

Standard Bluetooth SIG **Battery Service** (`0x180F`) with **Battery Level
Characteristic** (`0x2A19`).

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | u8 | Battery percentage, 0..100 |

Subscribe to notifications via the standard CCCD. The radar emits a
battery notification on connect, then on percentage change (typically
every 10 % drop). This adapter republishes the latest battery percentage
on every threat frame so downstream UI doesn't have to remember it.


## 5. Threat-level derivation

The "threat level" that Garmin head units display (NONE / APPROACHING /
FAST_APPROACH) is **not present in the `…3203` channel**. Garmin's
marketing copy suggests it lives in `…3201` or `…3202`, which are NDA.
This adapter derives it locally from distance + approach speed +
closing rate.

The classification in `RadarRepository.classify()`:

| Condition | Level |
| --- | --- |
| `approach_speed >= 60 km/h` | `FAST_APPROACH` |
| `distance <= 50 m` AND `approach_speed >= 3 km/h` | `FAST_APPROACH` |
| closing rate (computed from delta-distance / delta-time) `>= 10 m/s` | `APPROACHING` |
| `approach_speed >= 3 km/h` (anything else closing) | `APPROACHING` |
| else | `NONE` |

Notes:
- The 3 km/h floor filters parked-car detections the rider is
  approaching (or stationary on first sighting).
- The closing-rate fallback uses **m/s normalised by elapsed wall-clock
  between samples**, not a per-frame delta. The Varia notify rate is
  nominally ~1 Hz but isn't a documented guarantee; BLE drops can
  stretch a frame to 2 s and a frame-count threshold becomes incorrect
  in that case.
- This errs on the side of "louder" ,  a false negative on a closing
  car is dangerous, a false positive on a slow truck is just a yellow
  dot.


## 6. Notify cadence

Empirically observed: ~1 Hz when at least one vehicle is in view, slower
when the lane has been clear for a while. **Not formally documented**
in any public source. The ANT+ Bike Radar Device Profile (which Garmin
authored) transmits at a higher rate than the BLE mirror does.

Adapter consequence: do not assume the inter-frame gap. The
closing-rate fallback in section 5 time-normalises explicitly. Any
future code that wants a moving-average closing rate should keep the
same `(now - prev.lastSeenMs)` divisor.


## 7. Connection lifecycle

1. Pair from the Integration settings screen: scan advertises, user picks
   a device, address + name + vendor enum are persisted to
   `AppSettings`.
2. On connect, subscribe to both `…3203` (threat) and `0x2A19` (battery)
   notifications. Negotiate a larger MTU if possible; the firmware will
   still cap notifications at 20 bytes payload (section 3.1), but a
   larger MTU is cheap and protects against future firmware revisions.
3. Auto-reconnect on disconnect with 1.5 / 5 / 10 / 30 s backoff. The
   reconnect loop honours an "explicit disconnect" flag so a rider's
   deliberate Disconnect sticks until they Reconnect from the settings
   screen.
4. Drop the `pendingPayload` buffer on disconnect so a half-completed
   fragmented frame doesn't taint the next session.


## 8. Open questions / unconfirmed

- **Approach-speed sign under hard braking**: does `approach_speed_kmh`
  go negative (vehicle slowing relative to rider), or saturate to 0?
  All observed captures are positive.
- **RCT715 / RearVue 820 quirks**: the camera-equipped variants may
  expose an additional video-control characteristic in the `6a4e32xx`
  family. Not relevant to this adapter, but worth noting if a future
  branch tries to integrate camera previews.
- **Maximum vehicle count**: 8 has been observed in dense traffic; the
  fragmentation cap is 6 per notification, so the firmware will always
  send the higher count as two notifications. Whether the radar tracks
  more than 8 simultaneously is unconfirmed.
- **Frame rate under low-traffic conditions**: the radar appears to
  slow its notify rate when the lane has been clear for >10 s. Exact
  cadence unconfirmed; the alarm engine treats freshness via the
  `timestamp` field on each `RadarFrame` rather than assuming a rate.


## Attribution

- **pycycling/rear_view_radar.py** ([github.com/zacharyedwardbull/pycycling](https://github.com/zacharyedwardbull/pycycling)) ,  canonical triplet parser, GATT UUIDs.
- **Wunderfitz/harbour-tacho** ,  C++ port with fragment-aware reassembly using the same low-nibble flag the adapter in this project uses.
- **Garmin Connect IQ Developer Forum**, "Bluetooth profile for Garmin Varia RTL515" ([forums.garmin.com/developer/connect-iq/f/discussion/240452](https://forums.garmin.com/developer/connect-iq/f/discussion/240452)) ,  community write-up of the wire format and the 20-byte fragmentation cap.
- **Garmin owner's manual, RTL515/RTL516** ([www8.garmin.com/manuals/.../Varia_RTL515_516_OM_EN-US.pdf](https://www8.garmin.com/manuals/webhelp/GUID-C41F445D-457F-447D-88C8-FE286BF157E9/EN-US/Varia_RTL515_516_OM_EN-US.pdf)) ,  hardware reach + advertised name list.
- **ANT+ Bike Radar Device Profile (D00001664)** ,  referenced only for naming conventions; the spec itself is ANT+ member-restricted.
