# Trip Details: trim to a section of the ride

Status: approved design, ready for an implementation plan.
Branch: `next-experimental`.

## Problem

Trip Details always shows the whole ride. A long ride makes it hard to study one
stretch of it, a climb, a fast section, the minutes around an alarm, because the
summary tiles average across everything and the graphs squeeze the interesting
part into a few pixels.

Overlay Studio already solves the same problem for replay with a trim dialog.
Trip Details should get the same affordance, so the rider learns one interaction
and it works in both places.

## Scope

In scope:

* A funnel button in the Trip Details top bar, to the right of the edit pen.
* A trim dialog reusing Overlay Studio's, with Start / End / Duration fields and
  a Reset button.
* With a trim applied: summary tiles, all charts and the header date range
  recompute for the selection; the map draws the selection at full strength and
  the rest of the track faded; the funnel changes to signal the view is not the
  full trip.

Out of scope, deliberately:

* **Sharing, CSV export, GPX export and eucstats upload keep using the full
  trip, always.** A trim is a lens for reading a ride, not an edit to it.
  "Export just this section" is a separate feature with real consequences, a
  partial ride reaching the public leaderboard, and is not part of this work.
* Persisting the trim. It is session state and clears when the screen is left.
* Graph pinch zoom. Related rider request, tracked separately.
* The Studio map's gaps where GPS coordinates drop. Separate issue.

## Decisions

| Question | Decision |
|---|---|
| What recomputes when trimmed? | Everything: tiles, charts, header range, map. |
| Does the trim persist? | No. Session only, plain `remember` state. |
| Does it affect share or export? | No. Those always use the full trip. |
| Where does the trim state live? | The Trip Details composable. No ViewModel or DB change. |

## Architecture

### Single filter point

Every derived value on the screen already hangs off one list, so the trim is
applied once, at the source, and everything downstream follows unchanged:

```kotlin
// Was: var dataPoints by remember { mutableStateOf<List<TripDataPoint>>(emptyList()) }
var allPoints by remember { mutableStateOf<List<TripDataPoint>>(emptyList()) }

/** Elapsed-ms window into the ride. null means the full trip. */
var trimRange by remember { mutableStateOf<LongRange?>(null) }

// TripDataPoint.date is a String, and TripCsv.parseDate goes through
// SimpleDateFormat, which is slow. A long ride is tens of thousands of rows,
// so elapsed offsets are parsed once per trip and reused on every trim change.
val elapsedMs = remember(allPoints) {
    val t0 = allPoints.firstNotNullOfOrNull { TripCsv.parseDate(it.date) } ?: 0L
    LongArray(allPoints.size) { i -> (TripCsv.parseDate(allPoints[i].date) ?: t0) - t0 }
}

val dataPoints = remember(allPoints, elapsedMs, trimRange) {
    val r = trimRange ?: return@remember allPoints
    allPoints.filterIndexed { i, _ -> elapsedMs[i] in r }
}
```

`metrics`, the six chart series, `gpsPoints`, `extraEvents`, `wheelSwitches`,
`scrubPoint` and `headerDateTime` keep reading `dataPoints` verbatim.

This is the whole reason for filtering at the source rather than threading a
range through the derived values. `wheelSwitches` derives each marker's position
by counting the GPS-bearing rows before it:

```kotlin
val gpsIdx = dataPoints.subList(0, e.index)
    .count { it.latitude != 0.0 && it.longitude != 0.0 }
```

Any approach that trimmed the map and the charts separately would have to
re-derive that count by hand, and getting it wrong misplaces the wheel-change
markers with no visible error. Filtering once keeps it correct by construction.

`trimRange` is stored in elapsed milliseconds from the first sample, not as
indices, so it stays meaningful and matches what the rider typed in the dialog.

Rows whose timestamp does not parse fall back to offset 0, matching how
`TripCsv.metricsFrom` already skips unparseable dates rather than failing.

### The heal hazard

`RecordingViewModel.healTripMetrics` writes `startTime`, `endTime` and
`distanceKm` back onto the trip row whenever the stored values look broken. That
is the self-heal for a recording killed before it finalised. It is currently fed
from the same `metrics` value the tiles use.

If `metrics` starts coming from trimmed points, then opening a trip with a broken
row while a trim is active would persist **the trimmed window as the trip's real
identity**. The trip list reads those stored fields, not the CSV, so the ride's
true start, end and distance would be lost with no way back.

The two computations are therefore kept separate by purpose:

```kotlin
val fullMetrics = remember(allPoints)  { viewModel.tripMetrics(allPoints) }   // heal only
val metrics     = remember(dataPoints) { viewModel.tripMetrics(dataPoints) }  // header + tiles

LaunchedEffect(fullMetrics, liveState) {
    if (liveState == false) viewModel.healTripMetrics(trip, fullMetrics)
}
```

`healTripMetrics` must never receive trimmed metrics. This is the one path in
the feature that can destroy data, and it gets an explicit regression test.

## User interface

### Top bar

Trip Details has two separate top bars, portrait and landscape, both currently
Edit + Share. Both get the funnel between them, giving Edit, Funnel, Share. The
funnel follows the existing pattern of the edit pen and only renders when
`dataPoints.isNotEmpty()`.

State is signalled by both colour and shape, so it reads at a glance and does
not depend on colour alone:

| State | Icon | Tint |
|---|---|---|
| Full trip | `Icons.Outlined.FilterAlt` | default top bar content colour |
| Trimmed | `Icons.Filled.FilterAlt` | `MaterialTheme.appColors.primary` |

`material-icons-extended` is already a dependency, so both variants exist.
`primary` is already an `AppThemeColors` token, so no new token is needed and
the theme editor and colour identifier pick it up unchanged.

### Dialog

Overlay Studio's `TrimTimeDialog` is reused, not reimplemented. It already
provides exactly the requested behaviour: Start, End and Duration as
number-keyboard MM:SS fields that stay linked (editing Start or End re-derives
Duration; editing Duration moves the opposite end), Reset on the left which
clears to the full trip and commits immediately, Cancel and Apply on the right,
and Apply enabled only when both ends parse, sit inside `[0, duration]` and
`start < end`.

Refactor required, and it is small: `TrimTimeDialog` and its `TrimTimeField`
helper are `private` in `StudioReplayDialog.kt` and move to
`ui/common/TrimTimeDialog.kt`. `formatReplayClock` and `parseReplayClock` are
already public top-level functions in `StudioReplay.kt` and are simply imported.
Overlay Studio's behaviour must not change.

One condition is added to Apply enablement beyond the existing checks: the
selection must contain at least 2 data points, otherwise the charts and the map
have nothing to draw. Trips are sampled at roughly 1 Hz, so this only rejects
selections of a second or two, or ranges falling inside a recording gap.

Per repo convention, the dialog sets `dismissOnClickOutside = false`.

### Map

Requirement: the full track stays visible, faded outside the selection. This is
done by layering rather than by segmenting, which avoids having to intersect the
trim range with the existing wheel-change cuts:

1. Draw the full track first, through the existing polyline loop that splits at
   genuine wheel changes, at `opacity: 0.25`.
2. Draw the in-range portion on top, through the same loop, at full opacity with
   the normal blue and purple colours.
3. Move the green start and red end dots to the selection's endpoints.
4. `fitBounds` targets the selection, so the faded remainder runs off past the
   edges as context.

Untrimmed, step 1 is skipped and the rendering is identical to today.

## Testing

Unit tests, on the filter and on metrics:

* An untrimmed trip returns the identical point list.
* A trimmed range recomputes distance and duration for the selection.
* A range holding fewer than 2 points is rejected by Apply.
* A range whose bounds fall inside a recording gap still selects the points that
  do lie within it.

Regression test, on the data-loss path:

* With a trim active, `healTripMetrics` receives full-trip metrics, never the
  trimmed ones.

Manual checks:

* Portrait and landscape top bars both show the funnel in the right slot and
  both icon states render correctly in the dark and light built-in themes.
* Scrubbing a chart while trimmed moves the map marker to the matching sample.
* Wheel-change markers land in the right places on a trip recorded across two
  wheels, trimmed and untrimmed.
* Reset returns every tile, chart, the header and the map to the full ride.

## Localization

Near zero cost. The Studio's trim strings are already generic and already
translated across all 19 locales, and are reused directly:
`studio_replay_trim_title` ("Trim trip"), `studio_replay_trim_start`,
`studio_replay_trim_end`, `studio_replay_trim_duration`,
`studio_replay_trim_reset`, plus `action_cancel` and `action_apply`.

One new string is required, the funnel's `contentDescription`, and it needs
translating to all supported locales.
