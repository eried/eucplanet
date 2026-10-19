# Navigator view on the map: design

Branch: `feature/navigator-view` (from next-experimental). This file is working
scaffolding: drop it before the branch merges into a long-lived branch.

## Why

A rider asked for "turn-by-turn navigation on the map, speed, and distance to
destination. Just the usual. Plus four EUC telemetry fields, like voltage,
battery, trip distance, and PWM, which you can customize."

Today, Start sends the rider back to the dashboard. The next turn lives in a
pop-up card over the dashboard. The map itself shows no turn, no arrival time
and no telemetry: the card is suppressed there. The camera frames the midpoint
between the rider and the next stop, not the rider.

## Success

- With navigation running, the map alone is enough to ride by: next turn and
  its distance, arrival time, distance left, and 4 wheel numbers the rider
  picked.
- The map follows the rider, heading-up by default, north-up one tap away.
- Nothing that works today stops working: dashboard nav arrow and turn card,
  voice cues, stops panel, End navigation, landscape sidebar, group-ride peers.

## Decisions (from the user)

| Topic | Decision |
|---|---|
| Where | The existing map screen gains a navigator mode while guidance runs |
| After Start | Stay on the map in navigator view (today: pops to the dashboard) |
| Camera | Follow the rider, heading-up, rider in the lower third; compass toggles north-up |
| Fields | The dashboard's full metric list; 4 slots; long-press a tile to change |
| ETA speed | Advanced setting, default 25 km/h, blended with the trip's moving average, capped at 80% of tiltback |
| Layout | Turn card top-left with ETA and distance under it, tiles at the bottom above a slim stops bar |
| Speed | Big speed bubble bottom-left, like Google Maps; wheel speed, GPS speed with no wheel |
| Tiles | Always 4 |
| Screen | Kept on only while navigating with the map in front, and only when the dashboard keep-on setting is off |
| Hunt / Homing | Same view; card shows direction and straight-line distance; ETA from closing speed |
| Arrival | Arrival banner, then the normal map with the route still there |

## Layout

Portrait:

```
+-----------------------------+
| [<- 300 m ]          (N)    |   turn card, compass
| [ Storgata]                 |
| 14:32 . 8.4 km              |   arrival clock time . distance left
|                             |
|          map                |
|            ^ rider          |   lower third
|                             |
| [72.4V][ 68%][12.3km][ 41%] |   4 tiles
| Next: Cafe . 2 left   [ ^ ] |   slim stops bar, tap to expand
+-----------------------------+
```

- Turn card (top-left): the `NavState` arrow, distance to the turn, primary
  text and street. Off route and arrived replace the turn line in the same
  card, so the rider never needs the pop-up on the map.
- Under it: arrival clock time (device 12/24 h format) and distance left in
  the user's distance unit.
- Compass stays top-right.
- Speed bubble: a large round speed readout bottom-left, above the tiles,
  in the user's speed unit. Wheel speed while a wheel is connected, GPS
  speed otherwise. Always shown in navigator view.
- 4 tiles in a row above the stops bar; each shows value and short label.
- Stops bar: next stop name, stops left, an expand chevron. Expanded, it is
  today's stops panel, which carries End navigation (with its confirm).
- Search is already hidden while guidance runs; nothing changes there.
- The Layers and My-location buttons stay, stacked above the tiles.

Landscape: the speed bubble stays bottom-left on the map.
Landscape (sidebar shown, width at least `navSidebarMinScreenDp`): the turn
card stays top-left over the map; the tiles and the stops bar move into the
existing sidebar, tiles at its top.

Outside guidance, the map is exactly as today.

## Camera

- While guidance runs, `tickAutoFollow` targets the rider, not the
  rider/stop midpoint, with the rider placed in the lower third (a vertical
  recenter offset, combined with the existing panel offset).
- Heading-up as today (same start delay and easing). New: a remembered
  `navNorthUp` flag. When on, the follow keeps bearing 0.
- The compass button during guidance toggles `navNorthUp`. Its icon shows
  which mode is active. Outside guidance it keeps today's reset-to-north.
- Manual pan, pinch or rotate still pauses following for 6 s, then the
  camera returns to the rider in the chosen mode.
- Zoom is untouched (no speed zoom, as today).

## Arrival time

New pure function, unit-tested, no Android dependency:

```
etaSpeedKmh(settingKmh, movingAvgKmh, movingSeconds, tiltbackKmh)
```

- Blend: weight of the ride average grows with moving time, from 0 at 0 min
  to 1 at 10 min: `w = clamp(movingSeconds / 600, 0, 1)`;
  `speed = settingKmh * (1 - w) + movingAvgKmh * w`.
  With no moving average yet, `speed = settingKmh`.
- Cap: when tiltback is known (`> 0`), `speed = min(speed, 0.8 * tiltback)`.
- Floor: never below 5 km/h, so a crawl does not show an arrival in days.
- ETA = now + distance left / speed. Distance left comes from the engine.

Engine change: `NavState` gains `remainingM: Double` (the `distToEnd` the
engine already computes, per leg in Next segment mode, whole tour in Full
path mode). The watch mirror ignores the new field.

The moving average and moving time come from the same source as the
`AVG_TRIP_SPEED` metric, so the map and the dashboard agree.

Setting: `AdvancedSettings.navCruiseKmh` (Int, km/h, default 25, range
10..60) with an `ADVANCED_SPECS` entry in the navigation group, shown in the
user's speed unit, per-row reset. Label "Navigation cruising speed";
description says it affects the arrival time, and the danger: too high shows
an arrival you will not make.

## The 4 tiles

- Metric list: the dashboard's full catalog (`MetricCatalog`,
  `MetricRegistry`, `MetricValueFormat`).
- The dashboard's live string rendering (`displayValueFor`, a local function
  inside `DashboardScreen`) moves to a shared, non-composable formatter that
  both the dashboard and the navigator call. The dashboard's output must not
  change: guard it with a unit test over every key in
  `KNOWN_DASHBOARD_METRICS`.
- Storage: `AppSettings.navigatorMetrics`, a CSV of 4 keys, default
  `VOLTAGE,BATTERY,TRIP,LOAD` (`LOAD` is the catalog's PWM), nested so
  `AppSettings` stays under the constructor-argument limit.
- Long-press a tile on the map opens the dashboard's metric picker for that
  slot. Settings > Navigator lists the same 4 slots with a reset to default.
- An unknown or removed key falls back to that slot's default.

## Treasure hunt and Homing

No road route, only a goal. Same navigator view; the card shows the
direction arrow and the straight-line distance, labelled as straight line.
ETA uses closing speed: how fast the straight-line distance shrank over the
last 2 minutes. The ETA is hidden until that is positive and above 5 km/h,
and hidden while the rider moves away. Unit-tested like `etaSpeedKmh`.

## Screen

While guidance runs and the navigator map is in front, the screen stays on,
only when the dashboard's keep-screen-on setting is off (when it is on, that
setting already decides). It is released when guidance ends or the map
leaves the front.

## Arrival

At the last stop: the arrival banner as today, then the overlay goes away
and the map is the normal planning map, the route still drawn.

## Start lands on the map

`startNavigation(mode) { onExit() }` no longer pops to the dashboard; the map
switches to navigator view in place. The dashboard nav arrow, its long-press
menu and the turn card still work as today. The turn card stays suppressed on
the map (it now has its own card); arrival banners still show.

## Rules this touches

- Colors from `MaterialTheme.appColors`; add a token only if none fits.
- Every new string in all 23 locales; rider and wheel terminology; no
  em-dashes.
- The new global is an Advanced setting with a spec range and reset.
- Settings read through `SettingsRepository` (`currentOrLoad()` in the
  per-fix path).

## Testing

- Unit: closing-speed ETA (shrinking, steady, moving away, too slow).
- Unit: `etaSpeedKmh` (no ride yet, blend at 0, 5 and 10 min, tiltback cap,
  floor, unknown tiltback), ETA formatting.
- Unit: shared metric formatter matches the dashboard for every known key;
  `navigatorMetrics` parsing and fallback.
- Unit: `ADVANCED_SPECS` drift guard covers `navCruiseKmh`.
- Emulator (private port), virtual wheel plus a simulated route: card,
  arrival time counting down, tiles live, long-press picker, compass
  toggle, manual pan then return, End navigation, landscape sidebar, the
  dashboard turn card still working, speed bubble, screen kept on only
  while navigating, homing card, arrival back to the normal map.
- BRANCH.md: Worked on and Please test lines.

## Out of scope

Speed-based zoom, lane guidance, the HUD, watch and Android Auto screens
(Android Auto already has its own map with 4 boxes), and changing how the
route is computed.
