# Trip Details Trim Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a funnel button to the Trip Details top bar that trims the screen to a section of the ride, recomputing the tiles, charts and header for the selection while the map keeps the rest of the track visible but faded.

**Architecture:** The trim is applied once, as a filter over the single `dataPoints` list that every derived value on the screen already reads. Elapsed-millisecond offsets are precomputed per trip so the filter is an index test rather than a date parse. Filtering logic lives in a pure `TripTrim` object so it is unit-testable without Compose. The one path that writes to the database, `healTripMetrics`, is deliberately fed from a separate full-trip metrics value.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, `material-icons-extended`, Leaflet in a WebView, JUnit 4.

## Global Constraints

Copied from `CLAUDE.md` and `CONVENTIONS.md`. Every task's requirements implicitly include this section.

- **No em-dashes anywhere.** UI strings, code comments, commit and PR text. Use commas, " - ", or separate sentences.
- **Colors come from the theme.** Read via `MaterialTheme.appColors.*`. Never hardcode `Color(...)` and never use `MaterialTheme.colorScheme.*` in feature UI.
- **Localize all user-facing text.** Every string lives in `strings.xml` and is translated to all supported locales. Use rider terminology: the device is a wheel, the user is a rider.
- **Editor and studio dialogs set `dismissOnClickOutside = false`** so a stray tap cannot drop in-progress edits.
- **Verify builds** by grepping for `BUILD SUCCESSFUL` / `BUILD FAILED`. Never pipe gradle to `tail` and mask the exit code.
- **Branch:** `next-experimental`. New features are built here.
- Locales in `app/src/main/res`: `values`, `values-b+es+419`, `values-da`, `values-de`, `values-es`, `values-fr`, `values-it`, `values-ja`, `values-ko`, `values-nl`, `values-no`, `values-pl`, `values-pt-rBR`, `values-ru`, `values-sv`, `values-tr`, `values-uk`, `values-zh`, `values-zh-rTW`.

**Build and test commands** (run from the repo root, `d:\Downloads\eucplanet-improvements`):

```bash
./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.ui.recording.TripTrimTest"
./gradlew :app:assembleDebug
```

---

## File Structure

| File | Responsibility |
|---|---|
| Create: `app/src/main/java/com/eried/eucplanet/ui/recording/TripTrim.kt` | Pure trim logic: elapsed offsets, filtering, validity. No Compose, no Android. |
| Create: `app/src/test/java/com/eried/eucplanet/ui/recording/TripTrimTest.kt` | Unit tests for the above. |
| Create: `app/src/main/java/com/eried/eucplanet/ui/common/TrimTimeDialog.kt` | The shared trim dialog, moved out of Overlay Studio. |
| Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/StudioReplayDialog.kt` | Drop the two private composables, import them from their new home. |
| Modify: `app/src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt` | Trim state, both top bars, the metrics split, the map's faded layer. |
| Modify: `app/src/main/res/values*/strings.xml` | One new string, the funnel content description. |

---

### Task 1: Extract the trim dialog so Trip Details can reuse it

Pure refactor. Overlay Studio's behaviour must not change. There is no new test here because the deliverable is "the same dialog, reachable from a second place"; its behaviour is already exercised through Studio, and the compile plus the manual Studio check is the gate.

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/ui/common/TrimTimeDialog.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/StudioReplayDialog.kt:453-600`

**Interfaces:**
- Consumes: `formatReplayClock(ms: Long): String` and `parseReplayClock(text: String): Long?`, both already public top-level functions in `app/src/main/java/com/eried/eucplanet/ui/studio/StudioReplay.kt:118` and `:128`.
- Produces: `@Composable fun TrimTimeDialog(startMs: Long, endMs: Long, durationMs: Long, minPoints: Int = 0, pointsInRange: (LongRange) -> Int = { minPoints }, onConfirm: (Long, Long) -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Create the shared file by moving the two composables verbatim**

Cut `TrimTimeDialog` (currently `StudioReplayDialog.kt:461`) and `TrimTimeField` (currently `:549`) out of `StudioReplayDialog.kt` and into the new file, changing `private fun` to `fun` on `TrimTimeDialog` and `private fun` to `internal fun` on `TrimTimeField`. Keep the KDoc. The new file's package line is:

```kotlin
package com.eried.eucplanet.ui.common
```

Add these imports to the new file, matching what the two composables already use:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.studio.formatReplayClock
import com.eried.eucplanet.ui.studio.parseReplayClock
```

- [ ] **Step 2: Add the two new parameters for the minimum-points rule**

Trip Details needs Apply disabled when the selection holds too few samples to draw. Studio does not, so the parameters default to a no-op and Studio's call site is unchanged. Change the signature and the `valid` line:

```kotlin
@Composable
fun TrimTimeDialog(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    /** Minimum samples the selection must contain for Apply to enable. 0 disables the check. */
    minPoints: Int = 0,
    /** How many samples fall in a candidate range. Only called when [minPoints] > 0. */
    pointsInRange: (LongRange) -> Int = { minPoints },
    onConfirm: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
```

Replace the existing `val valid = startOk && endOk && startParsed!! < endParsed!!` with:

```kotlin
    val ordered = startOk && endOk && startParsed!! < endParsed!!
    val enoughPoints = !ordered || minPoints <= 0 ||
        pointsInRange(startParsed!!..endParsed!!) >= minPoints
    val valid = ordered && enoughPoints
```

- [ ] **Step 3: Set `dismissOnClickOutside = false` per repo convention**

Replace the `AlertDialog(` opening with:

```kotlin
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
```

- [ ] **Step 4: Point Overlay Studio at the new location**

In `StudioReplayDialog.kt`, add the import and leave the call site at `:427` exactly as it is:

```kotlin
import com.eried.eucplanet.ui.common.TrimTimeDialog
```

- [ ] **Step 5: Verify the build**

```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD SUCCESSFUL|BUILD FAILED"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/common/TrimTimeDialog.kt \
        app/src/main/java/com/eried/eucplanet/ui/studio/StudioReplayDialog.kt
git commit -m "refactor(ui): move TrimTimeDialog to ui/common so Trip Details can reuse it"
```

---

### Task 2: The pure trim logic, with tests

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/ui/recording/TripTrim.kt`
- Test: `app/src/test/java/com/eried/eucplanet/ui/recording/TripTrimTest.kt`

**Interfaces:**
- Consumes: `TripDataPoint` from `app/src/main/java/com/eried/eucplanet/ui/recording/RecordingViewModel.kt:47`. Its `date` field is a `String`, not a `Date`. `TripCsv.parseDate(raw: String?): Long?` from `app/src/main/java/com/eried/eucplanet/util/TripCsv.kt:40`.
- Produces:
  - `TripTrim.elapsedOffsets(points: List<TripDataPoint>): LongArray`
  - `TripTrim.apply(points: List<TripDataPoint>, elapsed: LongArray, range: LongRange?): List<TripDataPoint>`
  - `TripTrim.countInRange(elapsed: LongArray, range: LongRange): Int`
  - `const val TripTrim.MIN_POINTS = 2`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/eried/eucplanet/ui/recording/TripTrimTest.kt`:

```kotlin
package com.eried.eucplanet.ui.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TripTrimTest {

    /** One sample per second from 12:00:00, with the given count. */
    private fun points(count: Int): List<TripDataPoint> = (0 until count).map { i ->
        TripDataPoint(
            date = "2026-08-09 12:00:%02d.000".format(i),
            speed = 0f, voltage = 0f, temperature = 0f, battery = 0,
            altitude = 0f, latitude = 0.0, longitude = 0.0, totalMileage = 0f,
        )
    }

    @Test fun elapsedOffsets_areMillisFromTheFirstSample() {
        val e = TripTrim.elapsedOffsets(points(4))
        assertArrayEquals(longArrayOf(0L, 1_000L, 2_000L, 3_000L), e)
    }

    @Test fun elapsedOffsets_onEmptyInput_isEmpty() {
        assertEquals(0, TripTrim.elapsedOffsets(emptyList()).size)
    }

    @Test fun elapsedOffsets_unparseableRow_fallsBackToZeroOffset() {
        val pts = points(3).toMutableList()
        pts[1] = pts[1].copy(date = "not a date")
        val e = TripTrim.elapsedOffsets(pts)
        assertArrayEquals(longArrayOf(0L, 0L, 2_000L), e)
    }

    @Test fun apply_withNullRange_returnsTheSameListInstance() {
        val pts = points(5)
        val e = TripTrim.elapsedOffsets(pts)
        assertSame(pts, TripTrim.apply(pts, e, null))
    }

    @Test fun apply_selectsOnlySamplesInsideTheRange() {
        val pts = points(10)
        val e = TripTrim.elapsedOffsets(pts)
        val out = TripTrim.apply(pts, e, 3_000L..5_000L)
        assertEquals(3, out.size)
        assertEquals(pts[3], out.first())
        assertEquals(pts[5], out.last())
    }

    @Test fun apply_rangeInsideARecordingGap_keepsOnlyWhatActuallyExists() {
        // Samples at 0 s and 60 s, nothing between: a dropped connection.
        val pts = listOf(
            points(1).first(),
            points(1).first().copy(date = "2026-08-09 12:01:00.000"),
        )
        val e = TripTrim.elapsedOffsets(pts)
        assertEquals(0, TripTrim.apply(pts, e, 10_000L..20_000L).size)
        assertEquals(1, TripTrim.apply(pts, e, 50_000L..70_000L).size)
    }

    @Test fun countInRange_matchesWhatApplyWouldSelect() {
        val pts = points(10)
        val e = TripTrim.elapsedOffsets(pts)
        assertEquals(3, TripTrim.countInRange(e, 3_000L..5_000L))
        assertEquals(0, TripTrim.countInRange(e, 20_000L..30_000L))
    }

    @Test fun minPoints_isTwo_becauseAChartNeedsALine() {
        assertEquals(2, TripTrim.MIN_POINTS)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.ui.recording.TripTrimTest" 2>&1 | grep -E "BUILD SUCCESSFUL|BUILD FAILED"
```

Expected: `BUILD FAILED`, with a compile error saying `TripTrim` is unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/eried/eucplanet/ui/recording/TripTrim.kt`:

```kotlin
package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.util.TripCsv

/**
 * Trimming Trip Details to a section of a ride.
 *
 * The trim is expressed in elapsed milliseconds from the ride's first sample,
 * not as row indices, so it survives a re-read of the CSV and matches what the
 * rider typed into the trim dialog.
 *
 * Kept free of Compose and Android so it can be unit-tested directly.
 */
object TripTrim {

    /** A selection below this many samples has no line to draw, so it is rejected. */
    const val MIN_POINTS = 2

    /**
     * Elapsed millis from the first parseable timestamp, one entry per point.
     *
     * Parsed once per trip and reused: [TripCsv.parseDate] goes through
     * SimpleDateFormat, and a long ride is tens of thousands of rows, so doing
     * this inside the filter would make every trim change visibly slow.
     *
     * A row whose timestamp does not parse falls back to offset 0, matching how
     * [TripCsv.metricsFrom] skips unparseable dates rather than failing.
     */
    fun elapsedOffsets(points: List<TripDataPoint>): LongArray {
        if (points.isEmpty()) return LongArray(0)
        val t0 = points.firstNotNullOfOrNull { TripCsv.parseDate(it.date) } ?: 0L
        return LongArray(points.size) { i ->
            TripCsv.parseDate(points[i].date)?.minus(t0) ?: 0L
        }
    }

    /**
     * The points inside [range], or [points] itself when [range] is null.
     *
     * Returns the original instance for the untrimmed case so callers that key
     * a `remember` on the result do no extra work on a full trip.
     */
    fun apply(
        points: List<TripDataPoint>,
        elapsed: LongArray,
        range: LongRange?,
    ): List<TripDataPoint> {
        if (range == null || elapsed.size != points.size) return points
        return points.filterIndexed { i, _ -> elapsed[i] in range }
    }

    /** How many samples fall inside [range]. Drives the dialog's Apply gate. */
    fun countInRange(elapsed: LongArray, range: LongRange): Int =
        elapsed.count { it in range }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.ui.recording.TripTrimTest" 2>&1 | grep -E "BUILD SUCCESSFUL|BUILD FAILED"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/recording/TripTrim.kt \
        app/src/test/java/com/eried/eucplanet/ui/recording/TripTrimTest.kt
git commit -m "feat(recording): pure trim logic for Trip Details, with tests"
```

---

### Task 3: Wire the trim into Trip Details, including the heal guard

This is the task that can destroy data if done wrong. Read the "heal hazard" section of `docs/superpowers/specs/2026-08-09-trip-details-trim-design.md` before starting.

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt:123` (state), `:176-179` (metrics split), `:225-241` and `:250-262` (both top bars)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `TripTrim.elapsedOffsets`, `TripTrim.apply`, `TripTrim.countInRange`, `TripTrim.MIN_POINTS` from Task 2. `TrimTimeDialog` from Task 1.
- Produces: a `trimRange: LongRange?` in composable scope, null when the full trip is shown. Task 4 reads it.

- [ ] **Step 1: Add the string**

In `app/src/main/res/values/strings.xml`, next to the other `trip_` strings:

```xml
<string name="trip_trim">Trim to a section</string>
```

- [ ] **Step 2: Rename the backing state and add the trim state**

At `TripDetailScreen.kt:123`, replace:

```kotlin
    var dataPoints by remember { mutableStateOf<List<TripDataPoint>>(emptyList()) }
```

with:

```kotlin
    // The ride as recorded. Never filtered, so the heal path and the map's
    // faded context track always see the real trip.
    var allPoints by remember { mutableStateOf<List<TripDataPoint>>(emptyList()) }
    // Elapsed-ms window into the ride, null when the full trip is shown.
    var trimRange by remember { mutableStateOf<LongRange?>(null) }
    var showTrim by remember { mutableStateOf(false) }
    val elapsedMs = remember(allPoints) { TripTrim.elapsedOffsets(allPoints) }
    // Everything else on this screen reads dataPoints, so filtering here trims
    // the tiles, the charts, the header and the map in one move.
    val dataPoints = remember(allPoints, elapsedMs, trimRange) {
        TripTrim.apply(allPoints, elapsedMs, trimRange)
    }
    val trimmed = trimRange != null
```

- [ ] **Step 3: Point the CSV read at the new backing state**

At `:166-168`, replace `dataPoints = viewModel.readTripData(trip)` with:

```kotlin
    LaunchedEffect(trip.id) {
        allPoints = viewModel.readTripData(trip)
        trimRange = null
    }
```

Resetting `trimRange` matters: without it, opening a second trip from the same screen instance would carry the previous trip's window across.

- [ ] **Step 4: Split the metrics so the heal path keeps full-trip numbers**

At `:176-179`, replace:

```kotlin
    val metrics = remember(dataPoints) { viewModel.tripMetrics(dataPoints) }
    LaunchedEffect(metrics, liveState) {
        if (liveState == false) viewModel.healTripMetrics(trip, metrics)
    }
```

with:

```kotlin
    // Two metrics values, deliberately. healTripMetrics WRITES startTime,
    // endTime and distanceKm back onto the trip row, and the trip list reads
    // those stored fields rather than the CSV. Feeding it trimmed numbers would
    // overwrite the ride's real identity with whatever window happened to be
    // showing, with no way back. It gets the full trip, always.
    val fullMetrics = remember(allPoints) { viewModel.tripMetrics(allPoints) }
    val metrics = remember(dataPoints) { viewModel.tripMetrics(dataPoints) }
    LaunchedEffect(fullMetrics, liveState) {
        if (liveState == false) viewModel.healTripMetrics(trip, fullMetrics)
    }
```

- [ ] **Step 5: Add the funnel to the landscape top bar**

At `:229-233`, immediately after the existing edit `IconButton` and before the share one:

```kotlin
                        if (dataPoints.isNotEmpty()) {
                            IconButton(onClick = { showCustomize = true }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_customize))
                            }
                            TrimAction(trimmed = trimmed, onClick = { showTrim = true })
                        }
```

- [ ] **Step 6: Add the funnel to the portrait top bar**

At `:251-255`, the same insertion:

```kotlin
                        if (dataPoints.isNotEmpty()) {
                            IconButton(onClick = { showCustomize = true }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_customize))
                            }
                            TrimAction(trimmed = trimmed, onClick = { showTrim = true })
                        }
```

- [ ] **Step 7: Add the shared funnel button**

As a new private composable at the end of `TripDetailScreen.kt`, so both bars stay in sync:

```kotlin
/**
 * Top-bar funnel. Filled and tinted while a trim is applied, outlined and in
 * the bar's normal colour on the full trip, so the state reads by shape as well
 * as by colour.
 */
@Composable
private fun TrimAction(trimmed: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (trimmed) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
            contentDescription = stringResource(R.string.trip_trim),
            tint = if (trimmed) MaterialTheme.appColors.primary else LocalContentColor.current,
        )
    }
}
```

Add these imports at the top of the file:

```kotlin
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.LocalContentColor
```

- [ ] **Step 8: Host the dialog**

Next to the existing `showShareDialog` block at `:153-164`:

```kotlin
    if (showTrim) {
        val full = if (elapsedMs.isEmpty()) 0L else elapsedMs.last()
        TrimTimeDialog(
            startMs = trimRange?.first ?: 0L,
            endMs = trimRange?.last ?: full,
            durationMs = full,
            minPoints = TripTrim.MIN_POINTS,
            pointsInRange = { r -> TripTrim.countInRange(elapsedMs, r) },
            onConfirm = { s, e ->
                // Reset comes back as the full span, which is "no trim".
                trimRange = if (s <= 0L && e >= full) null else s..e
                showTrim = false
            },
            onDismiss = { showTrim = false },
        )
    }
```

Add the import:

```kotlin
import com.eried.eucplanet.ui.common.TrimTimeDialog
```

- [ ] **Step 9: Verify the build**

```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD SUCCESSFUL|BUILD FAILED"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Verify on a device or emulator**

Open a recorded trip. Confirm: the funnel sits between the pen and the share icon; applying a range changes the funnel to filled and tinted; the distance, duration, top speed and points tiles all change; the charts span only the selection; the header date range shows the trimmed window; Reset returns everything to the full ride. Rotate to landscape and confirm the funnel is there too.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(recording): trim Trip Details to a section of the ride"
```

---

### Task 4: Draw the rest of the track faded on the map

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt:408-423` (call site), `:1131-1132` and `:1165` and `:1211` (`RouteMapView` pass-through), `:1222-1264` (`MapSurface`), `:1361-1432` (`buildMapHtml`)

**Interfaces:**
- Consumes: `trimRange` from Task 3.
- Produces: nothing downstream.

- [ ] **Step 1: Pass the full-trip GPS points down**

`MapSurface` already receives `points = gpsPoints`, which is derived from `dataPoints` and therefore already trimmed. That means the highlighted trace, the endpoint markers, the wheel-change marker indices and `fitBounds` all follow the selection with no change. Only the faded context layer is new, so it arrives as a separate parameter.

At the `routeMap` call site (`:408-423`), add a `fadedPoints` argument after `points`:

```kotlin
                    points = gpsPoints,
                    fadedPoints = if (trimmed) fullGpsPoints else emptyList(),
```

and define `fullGpsPoints` next to the existing `gpsPoints` at `:327-329`:

```kotlin
            val gpsPoints = remember(dataPoints) {
                dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            }
            // The whole ride's fixes, for the faded context track behind a trim.
            val fullGpsPoints = remember(allPoints) {
                allPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            }
```

- [ ] **Step 2: Thread the parameter through `RouteMapView` and `MapSurface`**

Add `fadedPoints: List<TripDataPoint> = emptyList(),` immediately after the `points` parameter of both `RouteMapView` (near `:1131`) and `MapSurface` (`:1223`), and pass it through at both forwarding call sites (`:1165` and `:1211`) as `fadedPoints = fadedPoints,`.

- [ ] **Step 3: Encode the faded coordinates**

In `MapSurface`, next to the existing `coordsJson` at `:1238`:

```kotlin
    val fadedCoordsJson = remember(fadedPoints) {
        fadedPoints.joinToString(",") { "[${it.latitude},${it.longitude}]" }
    }
```

Add it to `buildMapHtml`'s parameter list and to the `remember` key list at `:1262`:

```kotlin
    val html = remember(coordsJson, fadedCoordsJson, isLive, switchesJson, startLabelJs, endLabelJs) {
        buildMapHtml(coordsJson, fadedCoordsJson, switchesJson, startLabelJs, endLabelJs, isLive, mapType)
    }
```

- [ ] **Step 4: Draw the faded layer first**

Change the `buildMapHtml` signature at `:1361` to take `fadedCoordsJson: String` as its second parameter, and add the array next to `coords` at `:1389`:

```javascript
  var coords=[$coordsJson];
  var fadedCoords=[$fadedCoordsJson];
```

Then, inside `render()`, before the existing wheel-change split loop at `:1418`:

```javascript
      // The rest of the ride, drawn faded underneath so a trimmed view still
      // shows where the section sits in the whole trip. Empty when untrimmed,
      // which makes this a no-op and leaves the untrimmed map unchanged.
      if (fadedCoords.length >= 2){
        L.polyline(fadedCoords,
          {color:'#4FC3F7',weight:4,opacity:0.25,interactive:false}).addTo(map);
      }
```

- [ ] **Step 5: Verify the build**

```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD SUCCESSFUL|BUILD FAILED"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Verify on a device or emulator**

Open a trip with GPS. Untrimmed, the map must look exactly as it did before. Trimmed, the selection draws in full-strength blue over a faded version of the whole ride, the green and red dots sit at the selection's ends, and the map is zoomed to the selection with the faded remainder running off the edges. Check a trip recorded across two wheels: the purple stretch and the wheel-change markers must still land in the right places.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt
git commit -m "feat(recording): fade the untrimmed track on the Trip Details map"
```

---

### Task 5: Translate the new string

**Files:**
- Modify: `app/src/main/res/values-*/strings.xml` (18 locale files)

- [ ] **Step 1: Add `trip_trim` to every locale**

Add the translated `<string name="trip_trim">` to each of the 18 non-default locale files listed in Global Constraints, placed next to `trip_customize` so the files stay parallel. Keep it short so it does not truncate as a tooltip. Suggested translations:

| Locale | Value |
|---|---|
| `values-da` | Beskær til et udsnit |
| `values-de` | Auf Abschnitt kürzen |
| `values-es` | Recortar a un tramo |
| `values-b+es+419` | Recortar a un tramo |
| `values-fr` | Réduire à une section |
| `values-it` | Ritaglia a una sezione |
| `values-ja` | 区間を切り出す |
| `values-ko` | 구간 자르기 |
| `values-nl` | Bijsnijden tot deel |
| `values-no` | Beskjær til en del |
| `values-pl` | Przytnij do fragmentu |
| `values-pt-rBR` | Recortar para um trecho |
| `values-ru` | Обрезать до участка |
| `values-sv` | Beskär till en del |
| `values-tr` | Bir bölüme kırp |
| `values-uk` | Обрізати до ділянки |
| `values-zh` | 裁剪到区间 |
| `values-zh-rTW` | 裁剪到區間 |

- [ ] **Step 2: Verify the build**

```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD SUCCESSFUL|BUILD FAILED"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values-*/strings.xml
git commit -m "i18n: translate the Trip Details trim action"
```

---

## Self-Review

**Spec coverage.** Funnel right of the edit pen: Task 3 steps 5 and 6. Trim dialog mirroring Studio's, with Reset: Task 1. Track faded outside the selection: Task 4. Icon signals a partial view: Task 3 step 7, by both shape and colour. Everything recomputes: Task 3 step 2, by filtering the one list the whole screen reads. Session-only persistence: Task 3 step 2, plain `remember`, plus step 3 clearing it when the trip changes. Share and export untouched: no task modifies those paths, which is the point. The heal hazard: Task 3 step 4, plus the reasoning kept in a code comment so it survives future edits. Minimum 2 points: Task 2 and Task 1 step 2. Localization: Task 5.

**Placeholder scan.** No TBD, no "handle edge cases", no "similar to Task N". Every code step carries the actual code.

**Type consistency.** `TripTrim.apply`, `elapsedOffsets`, `countInRange` and `MIN_POINTS` are used in Task 3 exactly as declared in Task 2. `TrimTimeDialog`'s `minPoints` and `pointsInRange` parameters added in Task 1 step 2 match the call in Task 3 step 8. `fadedPoints` is named identically across Task 4 steps 1, 2 and 3. `TripDataPoint.date` is treated as a `String` throughout, which is what it is.

**One deviation from the spec, deliberate.** The spec described moving the start and end dots to the selection's endpoints as separate work. It needs no code: `MapSurface` already receives `points = gpsPoints`, which is derived from the trimmed `dataPoints`, so the dots, the wheel-change marker indices and `fitBounds` all follow the selection for free. Task 4 step 1 records why.
