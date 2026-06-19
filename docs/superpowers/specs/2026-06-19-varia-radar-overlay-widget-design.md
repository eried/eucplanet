# Varia rear-view radar overlay widget — design

Date: 2026-06-19
Branch: `feature/varia-radar-overlay-widget`

## Goal

Surface the already-existing Garmin Varia rear-view radar data in two new
places: the **Overlay Studio** (so any rider can record video with radar data)
and, via the existing custom-overlay pipe, on the **HUD companion's "Custom"
screen**. This is one new Overlay Studio control (`OverlayElementType.RADAR`),
not a new screen — exactly like `DATA_DIAL`, `MAP`, and `G_FORCE` already are.

## What already exists (do not rebuild)

- `RadarRepository.currentFrame: StateFlow<RadarFrame?>` (phone, `:app`).
- `RadarFrame(vendor, threats: List<RadarThreat>, batteryPercent: Int?, timestamp)`.
- `RadarThreat(id, distanceM, approachSpeedKmh, threatLevel, firstSeenMs)`.
- `ThreatLevel { NONE, APPROACHING, FAST_APPROACH }` — locally classified.
- Dashboard already draws radar as a vertical "lane" bar (`DashboardRadarMini`).
- Overlay Studio element model + render dispatch (phone + HUD), shared
  `:hud-protocol` `OverlayLayout`/`OverlayElement`/`OverlayElementType`.
- HUD wire protocol `HudState` (`:hud-protocol/HudWire.kt`), `customOverlayJson`
  carries the studio layout to the HUD; `HudDemoSource` provides synthetic
  telemetry for emulator testing behind `debug.eucplanet.demo=true`.

## Hardware reality

The Varia is rear-facing and reports **distance + closing speed + a stable
per-car track id**, but **no bearing** (it cannot tell left from right). The
"Mirror" mode is therefore a *style*: both blind-spot markers illuminate
together to the current max threat level. This is called out in the UI copy.

## View modes (`OverlayElement.radarMode`, following `dialStyle`/`mapStyle`)

- **LANE** — vertical proximity bar; cars as colour-coded blips by distance
  (0…`radarRangeM`), optional distance labels. Mirrors `DashboardRadarMini`.
- **MIRROR** — two blind-spot chevrons (left + right) that light together to the
  max threat level, with the closest distance / fastest closing readout.
- **MINIMAL** — compact card: threat dot + closest distance + fastest closing
  speed; "lane clear" idle state when no targets.

## Data flow

```
RadarRepository.currentFrame ──phone──▶ HudServer.snapshot()
   RadarFrame{threats:[RadarThreat...], battery}        │ maps to wire
        │                                               ▼
 phone Overlay Studio live preview      HudState.radarTargets: List<RadarTargetWire>
   RadarWidgetElement (3 modes)         + radarConnected + radarBatteryPercent
                                               │ SSE 5 Hz; customOverlayJson carries layout
                                               ▼
                                  HUD Custom screen → OverlayElementRenderer
                                    → RadarWidgetElement (same 3 modes)
```

## Changes by module

### `:hud-protocol` (the contract)
- `HudWire.kt`: add `radarConnected: Boolean = false`,
  `radarBatteryPercent: Int = -1`, `radarTargets: List<RadarTargetWire> =
  emptyList()` (capped at 8). New `@Serializable RadarTargetWire(id, distanceM,
  approachSpeedKmh, level: Int)` where `level = ThreatLevel.ordinal`. Bump
  `PROTOCOL_MINOR` 7 → 8 (additive; old HUDs ignore the new fields).
- `OverlayLayout.kt`: add `RADAR` to `OverlayElementType`; add
  `radarMode: String = "LANE"`, `radarRangeM: Float = 140f`,
  `radarShowDistanceLabels: Boolean = true` to `OverlayElement`. Reuse existing
  `foreground` / `background` / `opacity` / `shadow`.

### `:app` (producer + phone studio editor & preview)
- `service/hud/HudServer.kt`: inject `RadarRepository`; populate the new wire
  fields in `snapshot()`.
- `service/hud/HudDemoSource.kt`: synthesize 0–3 closing cars so the widget is
  testable on emulators without a physical Varia.
- `ui/studio` render: `RADAR ->` branch + `RadarWidgetElement` (3 modes), threat
  colours from theme tokens `statusGood/Warn/Danger` (per `CLAUDE.md`).
- `ui/studio` editor: add to the Data group in the add-element picker, icon +
  label, and a radar config section (mode chips, range slider, label toggle,
  colours). `newElement()` radar default. Wire the phone studio's live data to
  expose the current `RadarFrame`.
- `res/values/strings.xml`: new strings (English; other locales fall back to the
  default — translation parity is a follow-up).

### `:hud` (consumer + renderer)
- `overlay/StudioElementData.kt`: carry `radarTargets` / `radarConnected` /
  `radarBatteryPercent` from `HudState` (HUD already depends on `:hud-protocol`,
  so it reuses `RadarTargetWire` directly).
- `overlay/OverlayElements.kt`: `RADAR ->` branch + `RadarWidgetElement` (same 3
  modes; fixed green/amber/red over the dark HUD surface — the documented
  theming exception).

## Edge cases

- Radar unpaired / no targets → idle "lane clear" / muted placeholder, never an
  error.
- Old HUD + new phone → unknown wire fields ignored (`ignoreUnknownKeys`); RADAR
  element silently dropped by the HUD's existing unknown-type fallback.
- Old preset JSON → radar fields take defaults.
- Targets capped (8) to bound frame size.

## Testing

- `:hud-protocol` unit tests: radar fields round-trip; the frozen v1.0 baseline
  still decodes (additive-only); a RADAR `OverlayElement` survives preset JSON
  round-trip; `PROTOCOL_MINOR` bumped.
- Emulator end-to-end (AVDs `new-version` = phone, `motoeye_e6_proxy` = HUD):
  build/install both, enable HUD server + `debug.eucplanet.demo=true`, add the
  RADAR widget in Overlay Studio, cycle modes, confirm matching live render on
  the HUD Custom screen. Capture screenshots of each mode, the editor config,
  and the HUD.
```
