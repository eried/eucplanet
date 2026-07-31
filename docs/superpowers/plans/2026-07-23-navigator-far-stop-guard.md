# Navigator far-stop guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the Route Builder, stop drawing the rider-to-first-stop line and block "start navigation" when the first stop is farther than a configurable distance, while keeping planning usable; plus stop-name GPX filenames and origin-free saved tracks.

**Architecture:** A new Advanced knob (`navMaxStartDistanceKm`, default 50) drives a pure decision helper (`NavStartGuard`). `RouteBuilderViewModel` consults it in `scheduleRecompute` (skip prepending the origin), `addWaypoint` (suppress the preview edge), and `startNavigation` (block + toast). A second pure helper (`RouteFileName`) builds the save filename. `saveGpx` re-derives a stops-only route so the `<trk>` never starts at the rider.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, JUnit4 unit tests, Gradle. Android app module `:app`.

## Global Constraints

- **No em-dashes anywhere** (UI strings, comments, commit/PR text). Use commas, " - ", or separate sentences.
- **Globals live in Advanced settings:** every global tunable is an `AdvancedSettings` field plus an `ADVANCED_SPECS` entry.
- **Modern toast only:** transients go through the Compose snackbar (`SnackbarHostState`) already wired in `RouteBuilderScreen`, never `Toast.makeText`.
- **Read settings through `SettingsRepository`** so values pass `sanitized()`; every numeric global needs a spec range (it clamps).
- **Keep `AppSettings` under the 255-arg limit:** the new field goes on the nested `AdvancedSettings`, not `AppSettings`' primary constructor.
- **Localize all user-facing text:** every new string lives in `res/values/strings.xml` and is translated to all 18 locales. Use rider/wheel terminology.
- **Verify builds** by grepping for `BUILD SUCCESSFUL` / `BUILD FAILED`; never mask the exit code.
- **Data-driven registries** (`ADVANCED_SPECS`) over per-item boilerplate; the drift-guard test (`AdvancedSpecTest`) covers new specs automatically.
- Build: `./gradlew :app:assembleDebug`  Unit tests: `./gradlew :app:testDebugUnitTest`

---

### Task 1: Advanced "max start distance" knob

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt` (AdvancedSettings field ~966, mirror getter ~858)
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AdvancedSpec.kt` (after line 164)
- Modify: `app/src/main/res/values/strings.xml` (near the other `adv_nav_*` strings)
- Test: `app/src/test/java/com/eried/eucplanet/data/model/AdvancedSpecTest.kt` (existing, no edit - it iterates the registry)

**Interfaces:**
- Produces: `AdvancedSettings.navMaxStartDistanceKm: Int` (default 50), read via `settings.advanced.navMaxStartDistanceKm`; strings `R.string.adv_nav_max_start`, `R.string.adv_nav_max_start_desc`.

- [ ] **Step 1: Add the field to `AdvancedSettings`**

In `AppSettings.kt`, after the line `val navMinInterStopMoveM: Int = 30,` (in the nav-behaviour block, ~line 966) add:

```kotlin
    val navMaxStartDistanceKm: Int = 50,
```

- [ ] **Step 2: Add the convenience getter on `AppSettings`**

After the line `val navMinInterStopMoveM: Int get() = advanced.navMinInterStopMoveM` (~line 858) add:

```kotlin
    val navMaxStartDistanceKm: Int get() = advanced.navMaxStartDistanceKm
```

- [ ] **Step 3: Register the spec**

In `AdvancedSpec.kt`, immediately after the `navMinInterStopMoveM` spec (line 164, the last entry in the `// --- Navigation behaviour ---` block) add:

```kotlin
    AdvancedSpec("navMaxStartDistanceKm", AdvGroup.NAV_BEHAVIOUR, R.string.adv_nav_max_start, R.string.adv_nav_max_start_desc,
        5..1000, 5, unit = "km", get = { it.navMaxStartDistanceKm }, set = { s, v -> s.copy(navMaxStartDistanceKm = v) }),
```

- [ ] **Step 4: Add the English strings**

In `app/src/main/res/values/strings.xml`, near the other `adv_nav_*` entries add:

```xml
    <string name="adv_nav_max_start">Max start distance</string>
    <string name="adv_nav_max_start_desc">Farthest a first stop can be to start navigating. Past this, the line from your position is hidden and start is blocked. Planning still works.</string>
```

- [ ] **Step 5: Run the registry drift-guard test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.data.model.AdvancedSpecTest"`
Expected: `BUILD SUCCESSFUL`. It round-trips the new spec's get/set, checks the id is unique, and that default 50 is in `5..1000`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt \
        app/src/main/java/com/eried/eucplanet/data/model/AdvancedSpec.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(nav): add max-start-distance Advanced knob"
```

---

### Task 2: `NavStartGuard` pure helper

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/nav/NavStartGuard.kt`
- Test: `app/src/test/java/com/eried/eucplanet/nav/NavStartGuardTest.kt`

**Interfaces:**
- Consumes: `GeoMath.distanceM(a: GeoPoint, b: GeoPoint): Double` (existing), `GeoPoint(lat, lng)` (`data.model`).
- Produces: `NavStartGuard.originWithinStart(rider: GeoPoint, firstStop: GeoPoint, maxKm: Int): Boolean`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/eried/eucplanet/nav/NavStartGuardTest.kt`:

```kotlin
package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStartGuardTest {

    private val rider = GeoPoint(59.9139, 10.7522) // Oslo-ish

    @Test
    fun `near stop is within start distance`() {
        val near = GeoPoint(59.9139, 10.7700) // ~1 km east
        assertTrue(NavStartGuard.originWithinStart(rider, near, maxKm = 50))
    }

    @Test
    fun `far stop is beyond start distance`() {
        val far = GeoPoint(60.4500, 10.7522) // ~60 km north
        assertFalse(NavStartGuard.originWithinStart(rider, far, maxKm = 50))
    }

    @Test
    fun `zero distance is within any positive threshold`() {
        assertTrue(NavStartGuard.originWithinStart(rider, rider, maxKm = 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.nav.NavStartGuardTest"`
Expected: FAIL / compile error - `NavStartGuard` is unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/eried/eucplanet/nav/NavStartGuard.kt`:

```kotlin
package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint

/**
 * Decides whether the rider's current position is close enough to the first
 * stop to be used as the route origin. Past [maxKm] the Route Builder hides the
 * you-to-first-stop line and refuses to start navigation, while still letting
 * the rider plan stop-to-stop.
 */
object NavStartGuard {
    fun originWithinStart(rider: GeoPoint, firstStop: GeoPoint, maxKm: Int): Boolean =
        GeoMath.distanceM(rider, firstStop) <= maxKm * 1000.0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.nav.NavStartGuardTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/nav/NavStartGuard.kt \
        app/src/test/java/com/eried/eucplanet/nav/NavStartGuardTest.kt
git commit -m "feat(nav): NavStartGuard distance decision helper"
```

---

### Task 3: `RouteFileName` pure helper

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/nav/RouteFileName.kt`
- Test: `app/src/test/java/com/eried/eucplanet/nav/RouteFileNameTest.kt`

**Interfaces:**
- Produces: `RouteFileName.suggest(stopNames: List<String>, fallback: String): String` - a sanitised base name, no extension.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/eried/eucplanet/nav/RouteFileNameTest.kt`:

```kotlin
package com.eried.eucplanet.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteFileNameTest {

    @Test
    fun `two named stops become first to last`() {
        assertEquals(
            "Storgata to Tromsdalen",
            RouteFileName.suggest(listOf("Storgata", "Tromsdalen"), fallback = "My route")
        )
    }

    @Test
    fun `single stop uses its name`() {
        assertEquals("Storgata", RouteFileName.suggest(listOf("Storgata"), fallback = "My route"))
    }

    @Test
    fun `blank endpoints fall back to route name`() {
        assertEquals("My route", RouteFileName.suggest(listOf("", ""), fallback = "My route"))
    }

    @Test
    fun `illegal filename characters are stripped`() {
        assertEquals(
            "ab to cd",
            RouteFileName.suggest(listOf("a/b:", "c*d?"), fallback = "My route")
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.nav.RouteFileNameTest"`
Expected: FAIL / compile error - `RouteFileName` is unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/eried/eucplanet/nav/RouteFileName.kt`:

```kotlin
package com.eried.eucplanet.nav

/**
 * Builds a filesystem-safe base name (no extension) for a saved route GPX.
 * Two or more named stops read "first to last"; one named stop uses its name;
 * anything blank falls back to [fallback] (the route's own name). Sanitised to
 * the same path-safe set the app uses for backup and theme files.
 */
object RouteFileName {

    fun suggest(stopNames: List<String>, fallback: String): String {
        val names = stopNames.map { it.trim() }
        val first = names.firstOrNull().orEmpty()
        val last = names.lastOrNull().orEmpty()
        val raw = when {
            names.size >= 2 && first.isNotBlank() && last.isNotBlank() -> "$first to $last"
            names.size == 1 && first.isNotBlank() -> first
            else -> fallback
        }
        return sanitize(raw).ifBlank { sanitize(fallback).ifBlank { "route" } }
    }

    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(48)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.nav.RouteFileNameTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/nav/RouteFileName.kt \
        app/src/test/java/com/eried/eucplanet/nav/RouteFileNameTest.kt
git commit -m "feat(nav): RouteFileName save-name builder"
```

---

### Task 4: Wire the gate + toast into the ViewModel

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `NavStartGuard.originWithinStart(...)` (Task 2), `advanced.value.navMaxStartDistanceKm` (Task 1), `NavFormat.distance(context, meters, imperial)`, `GeoMath.distanceM`, `imperialUnits` (existing VM flow).
- Produces: `RouteBuilderViewModel.toasts: SharedFlow<String>` (Task 6 collects it); `R.string.nav_too_far_to_start`.

- [ ] **Step 1: Add imports**

Near the other `com.eried.eucplanet.nav.*` imports at the top of `RouteBuilderViewModel.kt` add:

```kotlin
import com.eried.eucplanet.nav.NavFormat
import com.eried.eucplanet.nav.NavStartGuard
```

- [ ] **Step 2: Add the toast flow**

Right after the `_messages` / `messages` declaration (~line 248-249) add:

```kotlin
    /** One-shot pre-formatted toast strings (a distance etc. that the
     *  resource-id [_messages] channel cannot carry). Screen shows them as
     *  snackbars, same host as [messages]. */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()
```

- [ ] **Step 3: Skip the origin in `scheduleRecompute` when the first stop is far**

In `scheduleRecompute`, replace these two lines (~1085-1086):

```kotlin
        val routedTargets = if (_solveFullPath.value) nonPassed else listOf(nonPassed.first())
        val navWps = listOf(Waypoint(origin.latitude, origin.longitude)) + routedTargets
```

with:

```kotlin
        val routedTargets = if (_solveFullPath.value) nonPassed else listOf(nonPassed.first())
        // Prepend the rider as the origin only when the first stop is close
        // enough (Advanced "max start distance"). Past that we solve stop-to-
        // stop only, so the line from the rider to a distant first stop is not
        // drawn. Planning still works; starting is blocked (see startNavigation).
        val originPt = GeoPoint(origin.latitude, origin.longitude)
        val includeOrigin = NavStartGuard.originWithinStart(
            originPt, nonPassed.first().point(), advanced.value.navMaxStartDistanceKm
        )
        val navWps = if (includeOrigin) {
            listOf(Waypoint(origin.latitude, origin.longitude)) + routedTargets
        } else {
            routedTargets
        }
        // Origin dropped and a single far stop: nothing to draw between points,
        // show just the pin (pins render from _waypoints).
        if (navWps.size < 2) {
            _route.value = null
            _routing.value = false
            _pendingPreview.value = emptyList()
            bumpRender(fit)
            return
        }
```

- [ ] **Step 4: Suppress the rider preview edge in `addWaypoint`**

In `addWaypoint`, replace the body from `val before = _waypoints.value` through the `scheduleRecompute(...)` call (~lines 781-790) with:

```kotlin
        val before = _waypoints.value
        val hadNonPassed = before.any { !it.passed }
        val neighbor = before.lastOrNull { !it.passed }?.point()
            ?: currentLocation.value?.let { GeoPoint(it.latitude, it.longitude) }
        _waypoints.value = before + Waypoint(lat, lng, name)
        // A plain stop was just added; clear the "last preset" memory so the
        // Home / Work search suggestions both come back. addPreset() re-sets it.
        _lastAddedPresetKind.value = null
        cacheDraft()
        // Suppress the gold rider->stop preview when this first stop is beyond
        // the start distance (the rider is planning, not riding there now).
        val newPt = GeoPoint(lat, lng)
        val suppressRiderEdge = !hadNonPassed && neighbor != null &&
            !NavStartGuard.originWithinStart(neighbor, newPt, advanced.value.navMaxStartDistanceKm)
        val edge = if (neighbor != null && !suppressRiderEdge) listOf(neighbor, newPt) else emptyList()
        scheduleRecompute(fit = fit, previewEdge = edge)
```

- [ ] **Step 5: Block start in `startNavigation` with a toast**

In `startNavigation`, immediately after the `if (loc == null) { ... return }` block and BEFORE `onStarted()` (~line 1683) insert:

```kotlin
        // Block starting when the first stop is too far to ride to now; the
        // rider can still plan. The toast names the distance in their units.
        val originPt = GeoPoint(loc.latitude, loc.longitude)
        val firstStop = (dests.firstOrNull { !it.passed } ?: dests.first()).point()
        if (!NavStartGuard.originWithinStart(originPt, firstStop, advanced.value.navMaxStartDistanceKm)) {
            val distM = GeoMath.distanceM(originPt, firstStop)
            _toasts.tryEmit(
                context.getString(
                    R.string.nav_too_far_to_start,
                    NavFormat.distance(context, distM, imperialUnits.value)
                )
            )
            return
        }
```

- [ ] **Step 6: Add the toast string**

In `app/src/main/res/values/strings.xml`, near the other `nav_*` entries add:

```xml
    <string name="nav_too_far_to_start">First stop is %1$s away, too far to start navigation.</string>
```

- [ ] **Step 7: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderViewModel.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(nav): hide origin line and block start past max distance"
```

---

### Task 5: Stop-name filename + origin-free GPX save

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderViewModel.kt`

**Interfaces:**
- Consumes: `RouteFileName.suggest(...)` (Task 3), `routeName` (existing private field), `RoutingService.straightLineRoute`, `routingService.route`, `TravelMode` (all existing).
- Produces: `RouteBuilderViewModel.suggestedFileName(): String` (Task 6 uses it).

- [ ] **Step 1: Add the import**

Near the other `com.eried.eucplanet.nav.*` imports add:

```kotlin
import com.eried.eucplanet.nav.RouteFileName
```

- [ ] **Step 2: Add `suggestedFileName()`**

Directly above `fun saveGpx(uri: Uri)` (~line 1218) add:

```kotlin
    /** Suggested save filename from the stop names, e.g. "A to B.gpx". Falls
     *  back to the route name when stops are not geocoded yet. */
    fun suggestedFileName(): String =
        RouteFileName.suggest(_waypoints.value.map { it.name }, routeName) + ".gpx"
```

- [ ] **Step 3: Make `saveGpx` write a stops-only route**

In `saveGpx`, replace these two lines (~1221-1222):

```kotlin
                val route = _route.value
                    ?: RoutingService.straightLineRoute(routeName, _waypoints.value)
```

with:

```kotlin
                // Save a stops-only route so the <trk> never starts at the
                // rider's current position (only the planned stops + the
                // geometry between them). Reload re-routes from the rtept list,
                // so nothing is lost. Re-derive between-stop geometry from the
                // stops alone; straight-line if the router is unavailable.
                val stops = _waypoints.value
                val saveMode = _travelMode.value
                val route = when {
                    stops.size < 2 || saveMode == TravelMode.STRAIGHT ->
                        RoutingService.straightLineRoute(routeName, stops)
                    else ->
                        routingService.route(routeName, stops, saveMode, routerUrl, avoidances)
                            ?: RoutingService.straightLineRoute(routeName, stops)
                }
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderViewModel.kt
git commit -m "feat(nav): stop-name GPX filename and origin-free saved track"
```

---

### Task 6: Screen wiring (toasts + save filename)

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderScreen.kt` (message collector ~283-287, save launcher call ~646)

**Interfaces:**
- Consumes: `viewModel.toasts` (Task 4), `viewModel.suggestedFileName()` (Task 5), existing `snackbarHost`, `saveLauncher`.

- [ ] **Step 1: Collect the toast flow into the snackbar**

Immediately after the existing messages collector (the `LaunchedEffect` block containing `viewModel.messages.collect { resId -> snackbarHost.showSnackbar(context.getString(resId)) }`, ~lines 283-288) add:

```kotlin
    // Pre-formatted toast strings (e.g. the too-far-to-start distance).
    LaunchedEffect(Unit) {
        viewModel.toasts.collect { msg -> snackbarHost.showSnackbar(msg) }
    }
```

- [ ] **Step 2: Use the suggested filename for the save launcher**

At ~line 646 replace:

```kotlin
                            onSave = { saveLauncher.launch("route.gpx") },
```

with:

```kotlin
                            onSave = { saveLauncher.launch(viewModel.suggestedFileName()) },
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderScreen.kt
git commit -m "feat(nav): show too-far toast and name saves from stops"
```

---

### Task 7: Localize the three new strings

**Files:**
- Modify (add the 3 keys, translated) to each of the 18 locale files:
  `app/src/main/res/values-b+es+419/strings.xml`, `values-da`, `values-de`, `values-es`, `values-fr`, `values-it`, `values-ja`, `values-ko`, `values-nl`, `values-no`, `values-pl`, `values-pt-rBR`, `values-ru`, `values-sv`, `values-tr`, `values-uk`, `values-zh-rTW`, `values-zh` (all `.../strings.xml`).

**Interfaces:**
- Consumes: the three English source strings added in Tasks 1 and 4.

English source (verbatim keys - do not rename):

```xml
<string name="adv_nav_max_start">Max start distance</string>
<string name="adv_nav_max_start_desc">Farthest a first stop can be to start navigating. Past this, the line from your position is hidden and start is blocked. Planning still works.</string>
<string name="nav_too_far_to_start">First stop is %1$s away, too far to start navigation.</string>
```

- [ ] **Step 1: Add translated entries to every locale file**

For each of the 18 files above, add the three keys, translated into that language. This is real translation work, done with each file open:
- Match the tone and phrasing of the sibling `adv_nav_*` and `nav_*` entries already in that same file (they are the style reference).
- Keep the `%1$s` placeholder intact and in a natural position for the language.
- Use rider/wheel terminology (the device is a wheel, the user is a rider), never bike/car/driver.
- No em-dashes.

- [ ] **Step 2: Build (catches malformed XML and any bad placeholder)**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values-*/strings.xml
git commit -m "i18n(nav): localize far-stop guard strings across all locales"
```

---

## Final verification (not a task, run after Task 7)

- [ ] Full unit suite: `./gradlew :app:testDebugUnitTest` -> `BUILD SUCCESSFUL` (includes `NavStartGuardTest`, `RouteFileNameTest`, `AdvancedSpecTest`, `SettingsJsonDriftGuardTest`).
- [ ] Release-style assemble sanity: `./gradlew :app:assembleDebug` -> `BUILD SUCCESSFUL`.
- [ ] Manual smoke (emulator or device):
  - Plan a route whose first stop is > 50 km away: no line from your marker to it, tapping Start shows the "too far" toast with the distance, adding more stops still draws stop-to-stop.
  - Move/simulate within 50 km: the origin line reappears and Start works.
  - Save a GPX: the suggested name reads "<first> to <last>.gpx"; open the file and confirm the first `<trkpt>` is the first stop, not your position.

## Spec coverage check

- Goal 1 (hide origin line + block start, keep planning): Tasks 2, 4.
- Goal 2 (current position not saved to GPX): Task 5 (stops-only save).
- Goal 3 (better filename): Tasks 3, 5, 6.
- Goal 4 (toast + Advanced threshold): Tasks 1, 4, 6, 7.
