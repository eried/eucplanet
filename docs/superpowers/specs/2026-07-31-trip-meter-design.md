# Trip meter - design

A car-odometer-style running distance meter, independent of the recording
feature, with a distance-split detail view (graphed) and HUD availability.

## Motivation

The existing `TRIP` metric shows the distance of the current **recorded** trip;
the GPS accumulator only runs while `_recording` is true. Riders who don't
record want a simple "trip meter" that counts distance while connected and that
they reset like a car odometer. Tester ask: start on connect, hold through wheel
power-downs / charge stops, clear on Stop All; plus a manual reset.

## Concept

- New metric **`TRIP_METER`** ("Trip meter"), selectable in the Dashboard layout
  and in the Overlay Studio / HUD number set. Separate from `TRIP`.
- Counts distance continuously **while a wheel is connected** (no recording
  needed). The total persists across app restarts, wheel power-downs, and rest
  stops.
- Tapping the tile opens a **special detail view** with distance-based splits
  (every 10 km / 10 mi), NOT the min/max/avg view.

## Distance source (free - no new computation)

Reuse the per-tick distance the app already computes for the recorder /
dashboard (GPS-primary, wheel-distance fallback - GPS is trusted because BLE
drops freeze the wheel counter). The trip meter adds that same per-tick delta to
a running total **whenever a wheel is connected**, instead of only while
recording. No polling, no background loop; when disconnected, nothing runs and
the value sits in storage. A rare append happens each 10 km/mi boundary.

## Persistence & lifecycle

- Persisted to DataStore (survives app restart, process death, wheel power-down).
- **Cleared** only by: the **Reset** button in the detail view, or **Stop All**
  (`ACTION_STOP_ALL_AND_KILL`). Both zero the total and clear the split log.
- Otherwise it just keeps accumulating.

## Splits

At each 10 km / 10 mi mark (following the rider's distance unit) append one
`TripMeterSplit` record - all cheap accumulators, no heavy math:

```
index            // 1, 2, 3 ...
markDistanceKm   // 10, 20, 30 ... (stored in km, displayed per unit)
cumulativeMs     // elapsed active time when this mark was reached
segmentMs        // time for this segment (cumulativeMs - previous)
segmentAvgKmh    // markStep / segmentMs
segmentMaxKmh    // max speed seen during the segment (running max)
batteryPctAtMark // battery% sampled at the mark
```

Running accumulators reset per segment on each boundary. The in-progress
(partial) segment is shown too.

**Retention:** the split log is kept in **full** until a Reset / Stop All - a
trip odometer keeps the whole trip, however long. It is NOT pruned by the
"Stats length" (`dashboard_stats_length`) rolling window; that setting only
bounds the regular fine-grained per-metric stats (sparklines / min/max/avg), and
does not apply to these splits.

## Dashboard tile

- Add `TRIP_METER` to `MetricCatalog` (`supportsStats = false`, sparkline NONE -
  it is a monotonic counter). Live value = `tripMeterState.distanceKm` formatted
  per the distance unit, via `displayValueFor` in `DashboardScreen`.
- Tapping routes to the new `TripMeterDetailScreen` rather than the generic
  `MetricDetailScreen`.

## Detail view - tabbed, graphed (reuses existing chart+stats pattern)

Follows `MetricDetailScreen`'s "tabs across the top, chart + values below"
layout and its graph components (`Canvas`, `GraphBounds`, `GraphScale`), driven
off the split log rather than a rolling metric buffer:

- **Speed** tab - bar/line graph of `segmentAvgKmh` per split (+ max), and the
  split table.
- **Battery** tab - graph of battery used per segment (from `batteryPctAtMark`),
  and per-split values.
- **Time** tab - cumulative progression (distance vs `cumulativeMs`) plus each
  segment's time.

Header shows the total distance, total active time, and overall avg speed. A
**Reset** button (confirm dialog) zeroes the meter. No min/max/avg - those make
no sense for a monotonic trip meter.

## HUD availability

- Add `TRIP_METER` to both `StudioMetric` copies (phone `ui/studio` and `hud`
  `overlay`), byte-identical key `"TRIP_METER"`, kind DISTANCE, extract
  `{ it.tripMeterKm }`.
- Add `tripMeterKm: Float` to `HudState` (`HudWire.kt`) and bump `PROTOCOL_MINOR`
  (with a changelog note), populate it in `HudServer.snapshot()` from the repo,
  and map it in the HUD `StudioElementData.from()` into HUD `WheelData`. Also add
  `tripMeterKm` to phone `WheelData` (populated at the studio merge point) so the
  editor preview shows a live value.
- The HUD shows only the **running distance** big number. The splits / detail
  view stay phone-only (the cheap-to-send part is the single float).

## Architecture

- New `TripMeterRepository` (`@Singleton`): owns the accumulator, the split log,
  DataStore persistence, and `reset()`. Observes wheel connection + the per-tick
  distance/speed/battery (reads what `TripRepository` / `WheelRepository` already
  expose; use `dagger.Lazy` if a DI cycle would form). Exposes
  `StateFlow<TripMeterState>` (distanceKm, activeMs, splits, startedAtMs).
- Stop All wiring: `WheelService`'s stop-all path calls `tripMeterRepository.reset()`.
- Dashboard/ViewModel read the `StateFlow`; `TripMeterDetailScreen` renders tabs.
- Kept isolated from the recording code - no changes to the recorder's GPS
  accumulator or CSV path.

## Out of scope (YAGNI)

- Multiple meters (Trip A / Trip B). One meter for now.
- Configurable split interval - fixed at 10 in the rider's unit; an Advanced spec
  can follow later if asked.
- Persisting the full split log to a DB - DataStore JSON is enough for one meter.

## Testing

- Unit test the split logic (crossing boundaries, segment time/avg/max, reset).
- Manual: emulator + a **virtual wheel** simulator to watch the meter count up
  and splits appear. Do NOT push - run in the emulator for review first.
