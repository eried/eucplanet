# ENGO 2 / 3 HUD glasses integration - design

Status: approved design, doc-driven scaffold (no device on hand yet; finish + tune on a real
unit later, same pattern as the IPS i5 / Dragy branches). Branch: `feature/engo-hud` off
`next-experimental`.

## Goal

Drive ENGO 2 and ENGO 3 smart glasses as a heads-up output for EUC Planet: a compact,
glanceable telemetry HUD, with a navigation takeover (turn arrow + distance) when Navigator
guidance is active. Opt-in, isolated, low-CPU, no impact on riders who never enable it.

## Why these glasses are different from the MotoEye HUD

The existing "MotoEye HUD" (`HudServer`) streams telemetry **JSON over a WebSocket** to a smart
remote app that renders a rich layout itself. ENGO is the opposite: an **ActiveLook** BLE
"notification display, not a video screen." The phone sends **low-level draw commands** (text,
shapes, gauges, preloaded bitmaps); the glasses just display them. So ENGO cannot mirror the
MotoEye rich layout - it is a purpose-built compact HUD, rendered on the phone and pushed as
draw commands. We do NOT stream bitmaps per frame (CPU + BLE bandwidth both forbid it); bitmaps
are preloaded once and referenced by id.

## Protocol facts (ActiveLook, pinned from the public spec)

- Both ENGO 2 and ENGO 3 use the **same open ActiveLook API**. One adapter serves both.
- Display: **304 x 256 px**. ENGO 2 = 16 grey levels (MDP05); ENGO 3 = 81 RG colors (MDP08).
- GATT service `0783B03E-8535-B5A0-7140-A304D2495CB7`:
  - RX (write commands) `...CBA` (Write / Write-no-response)
  - TX (notify responses) `...CB8`
  - Control (flow control + errors) `...CB9`
  - Gesture `...CBB`, Touch `...CBC` (unused in v1)
- Frame: `0xFF | cmdId | format | length(BE) | [queryId] | data | 0xAA`. `format` bit 5 = length
  size (0 = 1 byte, 1 = 2 bytes); bits 4-1 = queryId byte count (we use 0). `length` counts the
  whole frame incl. header + footer. All scalars Big Endian. Max 512 data bytes/command.
- Commands used in v1: `clear` 0x01, `luma` 0x10, `grayscale` 0x30, `color` 0x3D (RG glasses),
  `rect` 0x33, `rectf` 0x34, `line` 0x32, `circf` 0x36, `arc` 0x3C, `txt` 0x37,
  `txtColor` 0x3E, `holdFlush` 0x39 (batch a frame), `fontSelect` 0x52, `battery` 0x05,
  `vers` 0x06.
- Flow control: subscribe to Control; on `0x02` (RX buffer 75% full) pause sending, resume on
  `0x01`. Use write-with-response for reliability.

## Architecture (four small, isolated units, `service.hud.engo`)

1. **`ActiveLookProtocol`** - pure Kotlin object. Builds command byte arrays (frame + each
   opcode we use). No Android, no state -> fully unit-tested against the spec, no hardware
   needed. This is the correctness core.
2. **`EngoAdapter`** - BLE client mirroring the external-GPS adapter pattern
   (name-prefix scan, connect, MTU negotiate, write to RX, subscribe Control for flow control,
   subscribe TX for battery/version replies). Owns the connection lifecycle and coexists with
   the wheel BLE via the existing connection infrastructure.
3. **`EngoHudRenderer`** - subscribes to the existing telemetry (`WheelRepository`) and
   `NavigationEngine`. Each tick (2-4 Hz) it chooses telemetry-page vs nav-takeover, lays out
   the rider's selected metrics into slots, and emits a batched frame (`holdFlush` hold ->
   draws -> flush). Partial redraw: only re-sends a widget whose formatted value changed.
   Metric set comes from the existing dashboard **metric catalog**.
4. **Settings + `EngoDemoSource`** - enable / auto-connect / metric picks, surfaced in the HUD
   settings section next to MotoEye. `EngoDemoSource` feeds synthetic telemetry so the render
   logic runs (and previews) without glasses.

## Data flow

`WheelRepository` + `NavigationEngine` -> `EngoHudRenderer` (choose page, format selected
metrics, diff vs last frame) -> `ActiveLookProtocol` (encode) -> `EngoAdapter` (BLE writes,
respecting flow control) -> glasses. Input from the glasses (gesture / touch) is out of scope
for v1 (output only).

## Layout (304 x 256, resolution-tolerant slots)

Telemetry page (rider picks the metrics; default shown):

```
+------------------------------------------+
|  42                    PWM [|||||    ] 61|   speed = primary (big font);
|  km/h                                    |   PWM bar (RYG on ENGO 3, grey on 2)
|                                          |
|  BATT 78 %                 TEMP 38 C     |   two rider-selectable secondary slots
+------------------------------------------+
```

Nav takeover (Navigator guidance active):

```
+------------------------------------------+
|            /\                 120 m      |   turn arrow (line/arc cmds or a small
|           /  \                            |   preloaded arrow set) + distance
|          /____\                           |
|   Main Street                            |   next maneuver street
+------------------------------------------+
```

Bars use `rectf` (outline `rect` + filled portion); no gauge preload needed in v1. The nav
arrow is drawn with line/`polyline` commands (a preloaded arrow set is a later optimization).

## ENGO 2 vs 3

One adapter. Colour is chosen per connected model: ENGO 3 uses `color` 0x3D / `txtColor` 0x3E
with RG values so PWM / alarm widgets go green -> yellow -> red; ENGO 2 falls back to
`grayscale` 0x30 / `txt` 0x37 (bar fill conveys the level). Model / capability read from the
device info on connect.

## Refresh & bandwidth

2-4 Hz, batched per frame with `holdFlush`, partial redraw (diff formatted values), numbers +
shapes only - never per-frame bitmaps. CPU is negligible (format strings + small BLE writes);
BLE stays within budget and flow control is honoured.

## Testing

- `ActiveLookProtocol` **unit tests**: assert exact frame bytes for each command vs this spec
  (clear, txt, txtColor, rectf, arc, holdFlush, frame length/format edge cases). No hardware.
- `EngoHudRenderer` tests driven by `EngoDemoSource`: telemetry-vs-nav page choice, metric
  formatting, and partial-redraw diffing produce the expected command sequence.
- Follows the project's data-driven / drift-guard testing convention.

## Finish-on-device (deferred to when a unit is available)

The adapter ships in connect + verify mode. To confirm on real glasses: exact font ids and
sizes, pixel positions per model, RG colour values, MTU / flow-control timing, and the
name-prefix used by ENGO in its BLE advertisement. These are tuning constants, isolated in the
renderer + adapter, not structural.

## Deferred (v2+)

Safety-alert flashes, glasses gesture/touch input, a per-metric layout editor, multiple
rider-cycled pages, preloaded ActiveLook layouts/pages for lower bandwidth.
