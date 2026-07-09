# IPS protocol (i5 / Zero / Lhotz / XIMA)

Status: **capture phase, incomplete.** This documents the wire format only, in
our own words and tables. No third-party source code is reproduced. Byte
offsets, UUIDs and opcodes are facts, not copyrightable expression.

## Background

IPS was one of the earliest electric-unicycle brands (Shenzhen / Singapore),
now defunct (~2017) with no app updates and no official support. Its companion
app was **iAmIPS** (iOS + Android). Unlike KingSong / Begode / InMotion /
Ninebot / Veteran, **IPS was never supported by WheelLog**, so there is no
open-source decoder to reference. Everything here is derived from a community
BLE sniff and from our own captures.

The user's testers ride an **IPS i5**. The i5 is a smaller, later model than
the XIMA/Lhotz that was originally sniffed, so the profile and framing below
are a strong starting point but **must be confirmed against a real i5
capture** before any value is trusted.

## GATT profile (from the 2015 XIMA/Lhotz sniff)

| Role    | UUID | Notes |
|---------|------|-------|
| Service | `0xFF00` | |
| Notify  | `0xFF01` | subscribe for notifications; not readable |
| Write   | `0xFF02` | commands written here; not readable |

Codified as `BleProfile.IPS` in `WheelAdapter.kt`.

## Framing (from the same sniff)

- The app writes a request to `FF02` shaped `90 00 <opcode> [payload]`.
- The wheel answers on `FF01` with a frame that **begins with the same
  `90 00 <opcode>`**, followed by that value's payload.
- Several answers may be **concatenated in a single notification**, so the
  parser splits on the `90 00` marker (see `IpsAdapter.splitFrames`).

### Known / candidate opcodes

| Request | Meaning | Confidence |
|---------|---------|------------|
| `90 00 01` | Speed poll (app fires ~2 Hz; speedometer cadence) | confirmed on XIMA, i5 TBD |
| `90 00 10` | Carries one of battery / mileage / firmware | response seen, layout unknown |
| `90 00 11` | Carries another of the above | response seen, layout unknown |

Values the app displayed: firmware version, battery %, speed (km/h), speed
limit, total mileage. There is **no lock function**. At 0 km/h the speed
answer looked like `90 00 01 ... 00 00 01 0b` in the sniff; the scaling was
never pinned publicly.

## What is needed to finish this

A labelled i5 capture (see `../BLE_CAPTURE_GUIDE.md`). Specifically:

1. The i5's **exact advertised BLE name** (an nRF Connect scan screenshot is
   enough) so the scan filter and family router match it. Current guesses in
   `isIpsWheelName`: `ips*`, `i5-*`, `i5`, `*lhotz*`, `*xima*`.
2. Confirm the i5 exposes **service FF00 / notify FF01 / write FF02** (or
   capture the real UUIDs if it differs).
3. A labelled ride so each `90 00 xx` answer can be tied to a known
   speed / battery % / voltage / mileage value and the byte offsets derived.

Until then, `IpsAdapter` connects, subscribes, polls the read opcodes, and
logs every framed answer to diagnostics (prefix `ips frame op=...`) but emits
`DecodeResult.Unknown` rather than inventing dashboard values. Control
commands (horn / light / lock / speed limit) are intentionally unwired; no
guessed write opcode is ever sent to a real wheel.

## Attribution

Protocol facts (FF00/FF01/FF02, the `90 00 xx` framing, and the `90 00 01`
speed poll) are restated in our own words from the community reverse-
engineering thread "Reverse engineering XIMA bluetooth protocol" on the
Electric Unicycle Forum (topic 1133, 2015), used as a **reference only**. No
source code from that thread, from the iAmIPS app, or from any GPL project is
reproduced here. This app is MIT-licensed.
