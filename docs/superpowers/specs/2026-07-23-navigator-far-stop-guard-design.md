# Navigator: far-stop guard, toast, and better GPX naming

Date: 2026-07-23
Branch: next-version
Status: approved, ready for implementation plan

## Problem

In the Route Builder the rider's current position is always prepended as the
route origin. When the first stop is far away (the rider is *planning* a route
somewhere they are not, not riding there now) this produces two bad behaviours:

1. A useless line is drawn from the rider's position across the map to a distant
   first stop.
2. "Start navigation" is allowed even though the rider cannot possibly ride the
   first leg now.

Related cleanups the same work touches:

- The saved GPX filename is hardcoded `route.gpx`.
- A routed save embeds the rider's current position as the first `<trk>` point
  (the geometry runs origin -> stops), leaking a transient location into a file
  meant to be a reusable route.

## Goals

1. When the first (next non-passed) stop is farther than a configurable
   threshold, do **not** draw the current-position -> first-stop line, and do
   **not** allow navigation to start. Planning (adding/moving stops, stop-to-stop
   routing) still works.
2. Answer/resolve "do we save current position to GPX": we do not save it as a
   pin, but it leaks into the `<trk>` geometry of routed saves. Fix: exclude the
   origin leg from saved files.
3. Build a better GPX filename from the stop names instead of `route.gpx`.
4. Surface the "too far to start" case as a toast (snackbar) naming the distance,
   not a blocking error. The threshold is a new Advanced setting.

## Non-goals

- No change to how navigation runs once started (legs, reroute, arrival).
- No change to the near-case display: within the threshold, the origin leg is
  still drawn exactly as today.
- No new "planning mode" concept; the gate is purely a distance check.

## Design

### A. Too-far gate (goals 1 + 4)

**New Advanced knob.** Add `navMaxStartDistanceKm: Int = 50` to
`AdvancedSettings` (`AppSettings.kt`, nested so `copy()` stays under the 255-arg
limit) and one `ADVANCED_SPECS` entry in `AdvGroup.NAV_BEHAVIOUR`:

```
AdvancedSpec("navMaxStartDistanceKm", AdvGroup.NAV_BEHAVIOUR,
    R.string.adv_nav_max_start, R.string.adv_nav_max_start_desc,
    5..1000, 5, unit = "km",
    get = { it.navMaxStartDistanceKm }, set = { s, v -> s.copy(navMaxStartDistanceKm = v) })
```

The registry gives us clamping in `sanitized()`, JSON (de)serialization, the
per-row reset affordance, and drift-guard coverage for free. Default **50 km**.

**Pure decision helper** (unit-testable, no Android deps), e.g. in the nav
package:

```
fun originWithinStart(rider: GeoPoint, firstStop: GeoPoint, maxKm: Int): Boolean =
    GeoMath.distanceM(rider, firstStop) <= maxKm * 1000.0
```

**`RouteBuilderViewModel` wiring:**

- Cache `maxStartDistanceKm` from `settingsRepository.settings` (it already
  observes settings; add this field alongside the others).
- `scheduleRecompute`: after computing `origin` and the first non-passed stop,
  prepend the origin only when `originWithinStart(...)` is true. When false, solve
  the stops-only chain (`routedTargets` without the origin). The rider->first-stop
  line is therefore never drawn; stop-to-stop geometry still solves and renders.
- `addWaypoint`: when the added stop is the first one and it is beyond the
  threshold, pass an empty `previewEdge` (suppress the gold rider->stop preview).
  Stop-to-stop previews (neighbour is another stop) are unaffected.
- `startNavigation`: after the existing empty/no-location checks, if the first
  non-passed stop is beyond the threshold, emit the toast (below) and return
  without starting.

**Self-healing:** the existing `currentLocation` observer recomputes past
`ORIGIN_REROUTE_M`, so as the rider closes the distance the origin leg reappears
and Start re-enables with no extra code.

### B. Toast (goal 4)

`_messages` is `SharedFlow<Int>` (resource ids only) and cannot carry a formatted
distance. Add a parallel pre-formatted channel, mirroring the existing
`_fillSearchText` string-flow pattern:

```
private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 2)
val toasts: SharedFlow<String> = _toasts
```

`RouteBuilderScreen` collects `viewModel.toasts` into the same `snackbarHost`
(next to the existing `viewModel.messages` collector).

The VM builds the string with the rider's units:

```
_toasts.tryEmit(context.getString(R.string.nav_too_far_to_start,
    NavFormat.distance(context, distanceM, imperial)))
```

New string `nav_too_far_to_start` = `First stop is %1$s away, too far to start
navigation.` No em-dash; brief; translated to every supported locale.

### C. Filename (goal 3)

New `RouteBuilderViewModel.suggestedFileName(): String`:

- Two or more stops: `"<first stop name> to <last stop name>"`.
- One stop: `"<stop name>"`.
- Names blank (not yet reverse-geocoded): fall back to `routeName`
  (defaults to "My route", `nav_default_route_name`).
- Sanitize with the app's established path-safe convention (the
  `sanitizeBackupName` regex: strip to `[A-Za-z0-9_\- ]`, collapse whitespace),
  cap ~48 chars, then append `.gpx`.
- The " to " separator survives sanitization (spaces + letters are kept).

`RouteBuilderScreen`: `saveLauncher.launch(viewModel.suggestedFileName())`
(replacing the hardcoded `"route.gpx"` at the `onSave` call). `ensureGpxExtension`
still guarantees the `.gpx` suffix if the rider deletes it in the system dialog.

### D. GPX origin exclusion (goal 2)

`saveGpx` writes a **stops-only** route so `<trk>` never begins at the rider's
current position. `<rtept>` is already origin-free.

- Build the saveable route from the stops alone (no origin prepend), reusing the
  routing call for a routed travel mode and `RoutingService.straightLineRoute`
  as the offline/failure fallback, then `GpxIO.write`.
- Reload (`loadGpx`) already re-routes from the `<rtept>` list, so dropping the
  origin leg loses nothing on re-open, and external GPX viewers see the routed
  shape between stops (not a straight rtept line).

## Data flow

```
settings.navMaxStartDistanceKm ─┐
currentLocation (rider) ────────┼─> scheduleRecompute ─> origin prepended?  ─> map line
                                │                                   └─> tourDistance
waypoints (stops) ──────────────┘
                                └─> startNavigation ─> within? ─ yes ─> engine.start
                                                            └─ no ──> _toasts (snackbar)
saveGpx ─> stops-only route ─> GpxIO.write (rtept + origin-free trk)
suggestedFileName() ─> saveLauncher.launch(name)
```

## Error handling / edge cases

- **Single far stop:** stops-only chain is one point, nothing to draw; the pin
  still shows and more stops can be added. Start is blocked with the toast.
- **No location fix yet:** existing `nav_no_location` path is unchanged and takes
  precedence over the distance check in `startNavigation`.
- **Exactly at threshold:** inclusive (`<=`), so the boundary counts as "within".
- **Passed stops:** the gate always measures to the first *non-passed* stop, so a
  multi-stop route in progress is judged by the leg the rider is actually on.
- **Routing fails on save:** straight-line fallback among the stops, still
  origin-free.

## Testing

- Unit-test `originWithinStart` at under / equal / over the threshold.
- Unit-test `suggestedFileName()`: two stops, one stop, blank names (route-name
  fallback), and illegal characters (sanitized away).
- The `ADVANCED_SPECS` drift-guard test covers the new spec automatically.
- Manual: plan a far route (line hidden, Start toasts), ride/simulate closer
  (line reappears, Start works), save and confirm the filename and that the
  saved `<trk>` does not start at the rider's position.

## Files touched

- `data/model/AppSettings.kt` - new `AdvancedSettings.navMaxStartDistanceKm`.
- `data/model/AdvancedSpec.kt` - new `ADVANCED_SPECS` entry.
- `ui/navigator/RouteBuilderViewModel.kt` - gate in `scheduleRecompute` /
  `addWaypoint` / `startNavigation`, `_toasts` flow, `suggestedFileName()`,
  stops-only `saveGpx`.
- `ui/navigator/RouteBuilderScreen.kt` - collect `toasts`, use
  `suggestedFileName()` for the save launcher.
- `nav/` - pure `originWithinStart` helper (+ its test).
- `res/values/strings.xml` (+ all locales) - `adv_nav_max_start`,
  `adv_nav_max_start_desc`, `nav_too_far_to_start`.
