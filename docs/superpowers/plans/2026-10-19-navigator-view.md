# Navigator View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While navigation runs, the phone map becomes a navigator: turn card with arrival time and distance left, speed bubble, 4 rider-chosen telemetry tiles, rider-following camera, today's stops panel folded.

**Architecture:** The navigation engine exposes what it already computes (distance left, distance to the next stop) plus an arrival time built by small pure helpers (moving average, closing speed, ETA speed). A shared, non-Compose metric formatter is pulled out of the dashboard so the map tiles and dashboard print identical text. The map screen draws a Compose overlay over the existing Leaflet WebView and tells the map script to follow the rider low on screen, heading-up or north-up.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Leaflet in a WebView (`MapHtml.kt`), JUnit4 unit tests (`app/src/test`), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-19-navigator-view-design.md`

Repo root: `D:\Downloads\eucplanet-ne`, branch `feature/navigator-view`. All source paths below are under `app/src/main/java/com/eried/eucplanet/` unless they start with `app/`.

## Global Constraints

- No em-dashes anywhere (UI strings, code comments, commit text). Use commas, " - ", or separate sentences.
- Colors only through `MaterialTheme.appColors.*`; never `Color(0x...)` or `MaterialTheme.colorScheme.*` in feature UI. Always set explicit on-colors.
- Every user-facing string in `app/src/main/res/values/strings.xml` and translated in all 22 other locales: `values-b+es+419 values-cs values-da values-de values-es values-fi values-fr values-hu values-it values-ja values-ko values-nl values-no values-pl values-pt-rBR values-ro values-ru values-sv values-tr values-uk values-zh values-zh-rTW`. Apostrophes escaped as `\'`. Write XML with the Write/Edit tools or Python, not Bash heredocs (they drop backslashes).
- Rider terminology: the device is a wheel, the user is a rider.
- Global tunables are `AdvancedSettings` fields with an `ADVANCED_SPECS` entry (range, step, reset).
- Settings are read through `SettingsRepository`: `currentOrLoad()` in per-fix or per-tick loops.
- `AppSettings` must stay under the 255 constructor-argument limit: new grouped fields go in a nested data class.
- Build check: run gradle and grep for `BUILD SUCCESSFUL` / `BUILD FAILED`; never pipe gradle to `tail`. Commands (PowerShell, repo root):
  - unit tests: `.\gradlew.bat :app:testDebugUnitTest --tests "<pattern>" 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|FAILED|tests completed"`
  - full build: `.\gradlew.bat :app:assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:"`
- Commits end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GHUZZ33sT9FYS6sXiMogJc
  ```
- Do not push. Do not touch emulators or the physical device.

## Review Focus

1. Imperial riders (distance unit `mi`): distance left, next-stop countdown and tiles must show miles/feet, never km. Covered by Task 4 (MetricText `mi` case) and Task 6 (uses `NavFormat.distance(..., imperial)`).
2. A wheel with no tiltback known or a rider without a wheel connected: ETA must still show (cap skipped, GPS speed used). Covered by Task 3 tests `noTiltbackSkipsCap` and Task 5 wiring.
3. Rider standing still at a light for minutes: moving average and ETA speed must not fall (stopped time is not riding time). Covered by Task 3 test `stoppedTimeDoesNotCount`.
4. Homing rider moving away from the goal or crawling: ETA hidden, not a wild time. Covered by Task 3 tests `closingSpeedHiddenWhenMovingAway`, `closingSpeedHiddenWhenSlow`.
5. A saved tile key that no longer exists (or a corrupted CSV): the slot falls back to its default instead of a blank or crash. Covered by Task 1 test `unknownKeyFallsBackToSlotDefault`.

---

### Task 1: Settings model (cruising speed, navigator tiles, north-up)

**Files:**
- Create: `data/model/NavigatorViewSettings.kt`
- Modify: `data/model/AppSettings.kt` (add nested field near `val androidAuto` at line ~125; add `navCruiseKmh` to `AdvancedSettings` near `navMovingKmh` at line ~1642; add getter near `val navMovingKmh: Int get()` at line ~1021)
- Modify: `data/model/AdvancedSpec.kt` (entry after `navMovingKmh` at line ~180)
- Modify: `data/store/SettingsJson.kt` (write near line 253 `androidAutoMetrics`, read near line 748)
- Modify: `app/src/main/res/values/strings.xml` and the 22 locale files: add `adv_nav_cruise`, `adv_nav_cruise_desc` (needed by the spec entry)
- Test: `app/src/test/java/com/eried/eucplanet/data/model/NavigatorViewSettingsTest.kt`

**Interfaces:**
- Produces:
  - `data class NavigatorViewSettings(val metrics: String = DEFAULT_METRICS, val northUp: Boolean = false)` with companion `const val SLOTS = 4`, `const val DEFAULT_METRICS = "VOLTAGE,BATTERY,TRIP,LOAD"`, `val DEFAULT_KEYS: List<String>`, `fun metricSlots(csv: String, known: Set<String>): List<String>` (always 4; blank/unknown slot gets that slot's default).
  - `AppSettings.navigatorView: NavigatorViewSettings`
  - `AdvancedSettings.navCruiseKmh: Int = 25`, getter `AppSettings.navCruiseKmh: Int`
  - JSON keys `navigatorViewMetrics` (String), `navigatorViewNorthUp` (Boolean); `navCruiseKmh` is persisted automatically through `ADVANCED_SPECS`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigatorViewSettingsTest {
    private val known = setOf("VOLTAGE", "BATTERY", "TRIP", "LOAD", "SPEED", "TEMPERATURE")

    @Test fun defaultsAreVoltageBatteryTripPwm() {
        assertEquals(listOf("VOLTAGE", "BATTERY", "TRIP", "LOAD"),
            NavigatorViewSettings.metricSlots(NavigatorViewSettings.DEFAULT_METRICS, known))
    }

    @Test fun keepsValidChoices() {
        assertEquals(listOf("SPEED", "BATTERY", "TEMPERATURE", "LOAD"),
            NavigatorViewSettings.metricSlots("SPEED,BATTERY,TEMPERATURE,LOAD", known))
    }

    @Test fun unknownKeyFallsBackToSlotDefault() {
        assertEquals(listOf("VOLTAGE", "BATTERY", "TRIP", "LOAD"),
            NavigatorViewSettings.metricSlots("GONE,,TRIP", known))
    }

    @Test fun alwaysFourSlots() {
        assertEquals(4, NavigatorViewSettings.metricSlots("SPEED,BATTERY,TRIP,LOAD,VOLTAGE,SPEED", known).size)
        assertEquals(4, NavigatorViewSettings.metricSlots("", known).size)
    }

    @Test fun cruiseSpecIsRegisteredWithDefault25() {
        val spec = ADVANCED_SPECS.single { it.id == "navCruiseKmh" }
        assertEquals(25, spec.get(AdvancedSettings()))
        assertEquals(10..60, spec.range)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*NavigatorViewSettingsTest*" 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:"`
Expected: BUILD FAILED, unresolved reference `NavigatorViewSettings`.

- [ ] **Step 3: Implement**

`data/model/NavigatorViewSettings.kt`:

```kotlin
package com.eried.eucplanet.data.model

/**
 * The map's navigator view: the 4 telemetry tiles beside the map while
 * navigating, and whether the camera keeps north up instead of turning with
 * the rider. Nested so AppSettings stays under the constructor-argument limit.
 */
data class NavigatorViewSettings(
    /** CSV of dashboard metric keys (MetricCatalog), one per tile. */
    val metrics: String = DEFAULT_METRICS,
    val northUp: Boolean = false,
) {
    companion object {
        const val SLOTS = 4
        /** LOAD is the catalog's PWM. */
        const val DEFAULT_METRICS = "VOLTAGE,BATTERY,TRIP,LOAD"
        val DEFAULT_KEYS: List<String> = DEFAULT_METRICS.split(',')

        /** Always [SLOTS] keys; a blank or unknown slot takes that slot's default. */
        fun metricSlots(csv: String, known: Set<String>): List<String> {
            val parts = csv.split(',').map { it.trim() }
            return List(SLOTS) { i ->
                parts.getOrNull(i)?.takeIf { it in known } ?: DEFAULT_KEYS[i]
            }
        }
    }
}
```

In `AppSettings` add next to `val androidAuto`:

```kotlin
    val navigatorView: NavigatorViewSettings = NavigatorViewSettings(),
```

In `AdvancedSettings` next to `navMovingKmh`:

```kotlin
    val navCruiseKmh: Int = 25,
```

Getter next to `val navMovingKmh: Int get() = advanced.navMovingKmh`:

```kotlin
    val navCruiseKmh: Int get() = advanced.navCruiseKmh
```

`AdvancedSpec.kt`, after the `navMovingKmh` entry:

```kotlin
    AdvancedSpec("navCruiseKmh", AdvGroup.NAV_BEHAVIOUR, R.string.adv_nav_cruise, R.string.adv_nav_cruise_desc,
        10..60, 1, unit = "km/h", get = { it.navCruiseKmh }, set = { s, v -> s.copy(navCruiseKmh = v) }),
```

`SettingsJson.kt` write (beside `androidAutoMetrics`):

```kotlin
        put("navigatorViewMetrics", s.navigatorView.metrics)
        put("navigatorViewNorthUp", s.navigatorView.northUp)
```

read (beside the `androidAuto = ...` block):

```kotlin
        navigatorView = com.eried.eucplanet.data.model.NavigatorViewSettings(
            metrics = j.optString("navigatorViewMetrics", base.navigatorView.metrics),
            northUp = j.optBoolean("navigatorViewNorthUp", base.navigatorView.northUp),
        ),
```

Strings (EN, translate into all 22 locales, insert after `adv_nav_moving_desc` in each file):

```xml
    <string name="adv_nav_cruise">Navigation cruising speed</string>
    <string name="adv_nav_cruise_desc">Affects the arrival time while navigating, until your own riding takes over. Danger: too high shows an arrival you will not make.</string>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*NavigatorViewSettingsTest*" --tests "*AdvancedSettingsSpecGuardTest*" --tests "*AdvancedSpecTest*" 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|FAILED"`
Expected: BUILD SUCCESSFUL. If a settings round-trip test exists (`grep -rl "SettingsJson" app/src/test`), run it too and make it cover the two new keys if it enumerates fields.

- [ ] **Step 5: Commit**

```
git add -A app/src/main/java/com/eried/eucplanet/data app/src/main/res app/src/test
git commit -m "feat(nav): settings for the navigator view tiles, north-up and cruising speed"
```

---

### Task 2: Navigator view strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` and all 22 locale `strings.xml` files.

**Interfaces:**
- Produces string resources used by Tasks 7 and 8: `nav_view_straight_line`, `nav_view_heading_up`, `nav_view_north_up`, `nav_view_tiles`, `nav_view_tiles_desc`, `nav_view_tile_n`, `nav_view_pick_metric`, `nav_view_reset_tiles`, `nav_view_speed`.

- [ ] **Step 1: Add the EN strings** at the end of the navigation block in `values/strings.xml` (after `nav_reset_north`):

```xml
    <string name="nav_view_straight_line">straight line</string>
    <string name="nav_view_heading_up">Heading up, tap for north up</string>
    <string name="nav_view_north_up">North up, tap for heading up</string>
    <string name="nav_view_tiles">Navigator tiles</string>
    <string name="nav_view_tiles_desc">The 4 numbers beside the map while navigating. Long-press a tile on the map to change it.</string>
    <string name="nav_view_tile_n">Tile %1$d</string>
    <string name="nav_view_pick_metric">Pick a number</string>
    <string name="nav_view_reset_tiles">Reset tiles</string>
    <string name="nav_view_speed">Speed</string>
```

- [ ] **Step 2: Translate** each of the 9 strings into all 22 locales, inserted after `nav_reset_north` in each file. Keep them short (they sit on small controls), keep `%1$d`, escape apostrophes `\'`, no em-dashes. Use a Python script written with the Write tool if editing many files.

- [ ] **Step 3: Verify resources compile and every locale has every key**

Run: `.\gradlew.bat :app:mergeDebugResources 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error"` → BUILD SUCCESSFUL.
Then (Bash): `for f in app/src/main/res/values*/strings.xml; do echo "$f $(grep -c 'name=\"nav_view_' $f)"; done` → every line ends in 9.
If a translation drift-guard test exists (`grep -rl "values-" app/src/test | head`), run it.

- [ ] **Step 4: Commit**

```
git add app/src/main/res
git commit -m "feat(nav): navigator view strings in all languages"
```

---

### Task 3: Arrival-time helpers (pure, tested)

**Files:**
- Create: `nav/NavEta.kt`
- Test: `app/src/test/java/com/eried/eucplanet/nav/NavEtaTest.kt`

**Interfaces:**
- Produces:
  - `object NavEta { const val FLOOR_KMH = 5.0; const val BLEND_FULL_S = 600.0; const val TILTBACK_SHARE = 0.8; fun speedKmh(settingKmh: Double, movingAvgKmh: Double?, movingSeconds: Double, tiltbackKmh: Double): Double; fun etaEpochMs(nowMs: Long, remainingM: Double, speedKmh: Double): Long }`
  - `class MovingAverage { fun add(timeMs: Long, speedKmh: Double, movingKmh: Double); val movingSeconds: Double; val avgKmh: Double?; fun reset() }`
  - `class ClosingSpeed(windowMs: Long = 120_000L, minSpanMs: Long = 20_000L) { fun add(timeMs: Long, distM: Double); fun kmh(): Double?; fun reset() }` where `kmh()` is null until the window spans `minSpanMs`, and null when the distance is not shrinking by at least `NavEta.FLOOR_KMH`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.eried.eucplanet.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavEtaTest {
    @Test fun noRideYetUsesSetting() =
        assertEquals(25.0, NavEta.speedKmh(25.0, null, 0.0, 50.0), 1e-9)

    @Test fun blendHalfwayAtFiveMinutes() =
        assertEquals(27.5, NavEta.speedKmh(25.0, 30.0, 300.0, 50.0), 1e-9)

    @Test fun fullyRideAverageAfterTenMinutes() =
        assertEquals(30.0, NavEta.speedKmh(25.0, 30.0, 900.0, 50.0), 1e-9)

    @Test fun cappedAtEightyPercentOfTiltback() =
        assertEquals(28.0, NavEta.speedKmh(25.0, 40.0, 900.0, 35.0), 1e-9)

    @Test fun noTiltbackSkipsCap() =
        assertEquals(40.0, NavEta.speedKmh(25.0, 40.0, 900.0, 0.0), 1e-9)

    @Test fun neverBelowFloor() =
        assertEquals(5.0, NavEta.speedKmh(25.0, 1.0, 900.0, 50.0), 1e-9)

    @Test fun etaIsDistanceOverSpeed() =
        // 10 km at 20 km/h = 30 min
        assertEquals(1_000L + 30 * 60_000L, NavEta.etaEpochMs(1_000L, 10_000.0, 20.0))

    @Test fun movingAverageCountsOnlyMovingTime() {
        val m = MovingAverage()
        m.add(0L, 20.0, 4.0)
        m.add(1_000L, 20.0, 4.0)
        m.add(2_000L, 20.0, 4.0)
        assertEquals(2.0, m.movingSeconds, 1e-9)
        assertEquals(20.0, m.avgKmh!!, 1e-9)
    }

    @Test fun stoppedTimeDoesNotCount() {
        val m = MovingAverage()
        m.add(0L, 30.0, 4.0)
        m.add(1_000L, 30.0, 4.0)
        // two minutes at a light
        var t = 1_000L
        repeat(120) { t += 1_000L; m.add(t, 0.0, 4.0) }
        m.add(t + 1_000L, 30.0, 4.0)
        assertEquals(2.0, m.movingSeconds, 1e-9)
        assertEquals(30.0, m.avgKmh!!, 1e-9)
    }

    @Test fun movingAverageSkipsGaps() {
        val m = MovingAverage()
        m.add(0L, 20.0, 4.0)
        m.add(60_000L, 20.0, 4.0) // a 60 s gap (GPS lost) counts at most 5 s
        assertEquals(5.0, m.movingSeconds, 1e-9)
    }

    @Test fun movingAverageEmptyIsNull() = assertNull(MovingAverage().avgKmh)

    @Test fun closingSpeedNeedsTwentySeconds() {
        val c = ClosingSpeed()
        c.add(0L, 1_000.0)
        c.add(10_000L, 900.0)
        assertNull(c.kmh())
    }

    @Test fun closingSpeedFromShrinkingDistance() {
        val c = ClosingSpeed()
        c.add(0L, 1_000.0)
        c.add(36_000L, 800.0) // 200 m in 36 s = 20 km/h
        assertEquals(20.0, c.kmh()!!, 1e-9)
    }

    @Test fun closingSpeedHiddenWhenMovingAway() {
        val c = ClosingSpeed()
        c.add(0L, 800.0)
        c.add(36_000L, 1_000.0)
        assertNull(c.kmh())
    }

    @Test fun closingSpeedHiddenWhenSlow() {
        val c = ClosingSpeed()
        c.add(0L, 1_000.0)
        c.add(36_000L, 990.0) // 1 km/h
        assertNull(c.kmh())
    }

    @Test fun closingSpeedForgetsOldSamples() {
        val c = ClosingSpeed()
        c.add(0L, 5_000.0)       // dropped: older than 120 s
        c.add(100_000L, 1_000.0)
        c.add(136_000L, 800.0)
        assertEquals(20.0, c.kmh()!!, 1e-9)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*NavEtaTest*" 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:"` → BUILD FAILED (unresolved `NavEta`).

- [ ] **Step 3: Implement `nav/NavEta.kt`**

```kotlin
package com.eried.eucplanet.nav

/**
 * Arrival time for the map's navigator view. The speed starts at the rider's
 * cruising-speed setting and slides to their own moving average over the
 * first ten minutes of riding, never above 80% of tiltback, never below a
 * crawl that would put the arrival days away.
 */
object NavEta {
    const val FLOOR_KMH = 5.0
    const val BLEND_FULL_S = 600.0
    const val TILTBACK_SHARE = 0.8

    fun speedKmh(settingKmh: Double, movingAvgKmh: Double?, movingSeconds: Double, tiltbackKmh: Double): Double {
        val w = if (movingAvgKmh == null) 0.0 else (movingSeconds / BLEND_FULL_S).coerceIn(0.0, 1.0)
        var speed = settingKmh * (1 - w) + (movingAvgKmh ?: 0.0) * w
        if (tiltbackKmh > 0) speed = minOf(speed, tiltbackKmh * TILTBACK_SHARE)
        return maxOf(speed, FLOOR_KMH)
    }

    fun etaEpochMs(nowMs: Long, remainingM: Double, speedKmh: Double): Long =
        nowMs + (remainingM / (speedKmh / 3.6) * 1000.0).toLong()
}

/** Average speed over the time actually spent moving; stops do not count. */
class MovingAverage {
    private var lastMs = -1L
    private var distKm = 0.0
    var movingSeconds = 0.0
        private set
    val avgKmh: Double? get() = if (movingSeconds > 0) distKm / (movingSeconds / 3600.0) else null

    fun add(timeMs: Long, speedKmh: Double, movingKmh: Double) {
        val prev = lastMs
        lastMs = timeMs
        if (prev < 0 || speedKmh <= movingKmh) return
        // A long gap (GPS lost, app paused) is not riding time we saw.
        val dtS = ((timeMs - prev) / 1000.0).coerceIn(0.0, MAX_STEP_S)
        movingSeconds += dtS
        distKm += speedKmh * dtS / 3600.0
    }

    fun reset() { lastMs = -1L; distKm = 0.0; movingSeconds = 0.0 }

    private companion object { const val MAX_STEP_S = 5.0 }
}

/** How fast the straight-line distance to a goal shrinks, for Treasure hunt and Homing. */
class ClosingSpeed(private val windowMs: Long = 120_000L, private val minSpanMs: Long = 20_000L) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(timeMs: Long, distM: Double) {
        samples.addLast(timeMs to distM)
        while (samples.isNotEmpty() && timeMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun kmh(): Double? {
        if (samples.size < 2) return null
        val (t0, d0) = samples.first()
        val (t1, d1) = samples.last()
        val spanMs = t1 - t0
        if (spanMs < minSpanMs) return null
        val kmh = (d0 - d1) / (spanMs / 1000.0) * 3.6
        return kmh.takeIf { it >= NavEta.FLOOR_KMH }
    }

    fun reset() = samples.clear()
}
```

- [ ] **Step 4: Run tests** (same command) → BUILD SUCCESSFUL, all NavEtaTest pass.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/eried/eucplanet/nav/NavEta.kt app/src/test/java/com/eried/eucplanet/nav/NavEtaTest.kt
git commit -m "feat(nav): arrival-time speed, moving average and closing speed helpers"
```

---

### Task 4: Shared metric text formatter (dashboard output unchanged)

**Files:**
- Create: `ui/metrics/MetricText.kt`
- Modify: `ui/dashboard/DashboardScreen.kt` (replace the body of the local `fun displayValueFor(metricKey: String): String` at line ~1646 with a call; move private `formatWhPerDistance` at line ~204 into `MetricText` and make the dashboard's remaining callers use `MetricText.whPerDistance`)
- Test: `app/src/test/java/com/eried/eucplanet/ui/metrics/MetricTextTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class MetricTextInputs(
    val live: Boolean,
    val wheel: WheelData,
    val speedUnit: String,      // "kmh" | "mph" | "ms" | "kn"
    val distanceUnit: String,   // "km" | "mi" | ...
    val tempUnit: String,
    val pressureUnit: String,
    val currentAsWatts: Boolean,
    val tripMeterKm: Float,
    val phoneBatteryPct: Int,
    val externalGpsBatteryPct: Int?,
    val gpsAltitudeM: Double?,
    val gpsSpeedKmh: Float?,     // null when the fix has no speed
    val gpsBearingDeg: Float?,   // null when the fix has no bearing
    val gpsAccuracyM: Float?,    // null when the fix has no accuracy
)
object MetricText {
    const val PLACEHOLDER = "--"
    fun of(key: String, i: MetricTextInputs): String
    fun speedUnitLabel(speedUnit: String): String
    fun whPerDistance(whPerKm: Float, distanceUnit: String): String?
}
```

- [ ] **Step 1: Write the failing test** (expected strings are what today's dashboard prints for these inputs; read `displayValueFor` to confirm each before running)

```kotlin
package com.eried.eucplanet.ui.metrics

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class MetricTextTest {
    @Before fun locale() { Locale.setDefault(Locale.US) }

    private val wheel = WheelData(
        voltage = 72.4f, current = 12.3f, batteryPercent = 68, pwm = 41f,
        tripDistance = 12.34f, speed = 31.6f, totalDistance = 1234.5f, batteryPower = 900,
    )
    private fun inputs(live: Boolean = true, dist: String = "km", speed: String = "kmh", watts: Boolean = false) =
        MetricTextInputs(
            live = live, wheel = wheel, speedUnit = speed, distanceUnit = dist, tempUnit = "C",
            pressureUnit = "bar", currentAsWatts = watts, tripMeterKm = 3.21f, phoneBatteryPct = 77,
            externalGpsBatteryPct = null, gpsAltitudeM = 123.4, gpsSpeedKmh = 30.0f,
            gpsBearingDeg = 90f, gpsAccuracyM = 4f,
        )

    @Test fun matchesDashboard() {
        val i = inputs()
        assertEquals("68%", MetricText.of("BATTERY", i))
        assertEquals("72.4V", MetricText.of("VOLTAGE", i))
        assertEquals("41%", MetricText.of("LOAD", i))
        assertEquals("12.3 km", MetricText.of("TRIP", i))
        assertEquals("3.2 km", MetricText.of("TRIP_METER", i))
        assertEquals("32 km/h", MetricText.of("SPEED", i))
        assertEquals("12.3A", MetricText.of("CURRENT", i))
        assertEquals("900W", MetricText.of("BATTERY_POWER", i))
        assertEquals("1234.5 km", MetricText.of("ODOMETER", i))
        assertEquals("77%", MetricText.of("PHONE_BATTERY", i))
        assertEquals("123m", MetricText.of("GPS_ALTITUDE", i))
        assertEquals("30.0 km/h", MetricText.of("GPS_SPEED", i))
        assertEquals("90°", MetricText.of("GPS_HEADING", i))
    }

    @Test fun wattsMode() = assertEquals("891W", MetricText.of("CURRENT", inputs(watts = true)))

    @Test fun imperialUnits() {
        val i = inputs(dist = "mi", speed = "mph")
        assertEquals("7.7 mi", MetricText.of("TRIP", i))
        assertEquals("20 mph", MetricText.of("SPEED", i))
        assertEquals("405ft", MetricText.of("GPS_ALTITUDE", i))
    }

    @Test fun notLiveIsPlaceholder() {
        listOf("BATTERY", "VOLTAGE", "LOAD", "TRIP", "SPEED").forEach {
            assertEquals(MetricText.PLACEHOLDER, MetricText.of(it, inputs(live = false)))
        }
    }

    @Test fun unknownKeyIsPlaceholder() = assertEquals("--", MetricText.of("NOPE", inputs()))

    @Test fun zeroVoltageIsPlaceholder() =
        assertEquals("--", MetricText.of("VOLTAGE", inputs().copy(wheel = WheelData())))
}
```

Note on expected values: `Units.distance(12.34f, "mi")` and `Units.speed(31.6f, "mph")` decide the imperial numbers; if the existing converters round differently, correct the expected literal to what the unchanged converter returns, never change the converter.

- [ ] **Step 2: Run to verify failure**: `.\gradlew.bat :app:testDebugUnitTest --tests "*MetricTextTest*" ...` → BUILD FAILED (unresolved `MetricText`).

- [ ] **Step 3: Implement** `ui/metrics/MetricText.kt`: move every branch of the dashboard's `displayValueFor` `when` verbatim into `MetricText.of`, replacing captured locals:
  - `live` → `i.live`; `placeholder` → `PLACEHOLDER`; `wheelData` → `i.wheel`
  - `tempValue` → `Units.temperature(i.wheel.maxTemperature, i.tempUnit)`; `tempUnitLabel` → `Units.tempUnit(i.tempUnit)`
  - `currentText` → `if (i.currentAsWatts) "%.0fW".format(i.wheel.voltage * i.wheel.current) else "%.1fA".format(i.wheel.current)`
  - `pwm` → `i.wheel.pwm.absoluteValue`
  - `tripValue` → `Units.distance(i.wheel.tripDistance, i.distanceUnit)`; `distUnit` → `Units.distanceUnit(i.distanceUnit)`
  - `tripMeterState.distanceKm` → `i.tripMeterKm`
  - `speedUnit` → `i.speedUnit`; `speedUnitLabel` → `speedUnitLabel(i.speedUnit)` (same `when` as the dashboard: mph, ms → m/s, kn, else km/h)
  - `distanceUnit` → `i.distanceUnit`; `tempUnit` → `i.tempUnit`; `pressureUnit` → `i.pressureUnit`
  - `phoneBatteryPct` → `i.phoneBatteryPct`; `externalGpsBattery` → `i.externalGpsBatteryPct`
  - GPS branches use the four nullable fields (`gpsAltitudeM`, `gpsSpeedKmh` already in km/h so convert with `Units.speed(it, i.speedUnit)` and format `"%.1f %s"`, `gpsBearingDeg`, `gpsAccuracyM`), returning `PLACEHOLDER` when null.
  - `formatWhPerDistance` → `whPerDistance` (same body).

  In `DashboardScreen.kt` keep `displayValueFor` as a one-liner so its callers do not change:

```kotlin
            val metricInputs = com.eried.eucplanet.ui.metrics.MetricTextInputs(
                live = live, wheel = wheelData, speedUnit = speedUnit, distanceUnit = distanceUnit,
                tempUnit = tempUnit, pressureUnit = pressureUnit, currentAsWatts = showWatts,
                tripMeterKm = tripMeterState.distanceKm, phoneBatteryPct = phoneBatteryPct,
                externalGpsBatteryPct = externalGpsBattery,
                gpsAltitudeM = gpsLocation?.altitude,
                gpsSpeedKmh = gpsLocation?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6f },
                gpsBearingDeg = gpsLocation?.takeIf { it.hasBearing() }?.bearing,
                gpsAccuracyM = gpsLocation?.takeIf { it.hasAccuracy() }?.accuracy,
            )
            fun displayValueFor(metricKey: String): String =
                com.eried.eucplanet.ui.metrics.MetricText.of(metricKey, metricInputs)
```

  Check the real types first: `externalGpsBattery` and `phoneBatteryPct` may be `Int?`/`Int`; adapt `MetricTextInputs` field types to match and keep the test compiling. Remove locals that become unused only if nothing else in the composable reads them (`currentText`, `tripValue` etc. may still feed other UI: grep before deleting).

- [ ] **Step 4: Run** `MetricTextTest` and then the full suite: `.\gradlew.bat :app:testDebugUnitTest 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|FAILED"` → BUILD SUCCESSFUL. Then `.\gradlew.bat :app:assembleDebug ...` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add -A app/src/main/java/com/eried/eucplanet/ui app/src/test/java/com/eried/eucplanet/ui/metrics
git commit -m "refactor(dashboard): metric text in a shared formatter the map can use"
```

---

### Task 5: Engine exposes distance left, next-stop distance and arrival time

**Files:**
- Modify: `data/model/NavModels.kt` (`NavState`, line ~198)
- Modify: `nav/NavigationEngine.kt` (`onFix` ~465, `computeTurnByTurn` ~595, `guideHoming` ~796, `computeTreasureHunt` ~821, settings mirror ~152-174, `start` ~300-320)

**Interfaces:**
- Consumes: `NavEta`, `MovingAverage`, `ClosingSpeed` (Task 3); `AppSettings.navCruiseKmh` (Task 1).
- Produces new `NavState` fields (all defaulted, so the watch mirror and existing copies compile unchanged):

```kotlin
    /** Distance left to the end of what is being navigated, metres; NaN when unknown. */
    val remainingM: Double = Double.NaN,
    /** Distance left to the next stop along the route, metres; NaN when unknown. */
    val nextStopRemainingM: Double = Double.NaN,
    /** True when distances are straight lines to a goal (Treasure hunt, Homing). */
    val straightLine: Boolean = false,
    /** Estimated arrival, epoch ms; 0 when there is no estimate to show. */
    val etaEpochMs: Long = 0L,
```

- [ ] **Step 1: Add the fields** to `NavState` (after `cueVisible`).

- [ ] **Step 2: Engine state.** Add fields next to the other runtime state:

```kotlin
    // Navigator-view arrival time. The moving average spans the whole session
    // (reset in start(), not per leg); closing speed is per goal.
    private val movingAverage = MovingAverage()
    private val closingSpeed = ClosingSpeed()
    @Volatile private var cruiseKmh: Double = 25.0
    @Volatile private var tiltbackKmh: Double = 0.0
```

In the settings collector add:

```kotlin
                cruiseKmh = s.navCruiseKmh.toDouble()
                tiltbackKmh = (if (wheelRepository.safetySpeedActive.value) s.safetyTiltbackKmh
                    else s.tiltbackSpeedKmh).toDouble()
```

In `start(...)`, beside `lastArrivalPoint = null`: `movingAverage.reset(); closingSpeed.reset()`. Wherever `currentGoal` advances (in `computeTreasureHunt` after `currentGoal++`, in the heading-null branch after `currentGoal = 2`) and in `advanceLeg`'s reset, call `closingSpeed.reset()`.

- [ ] **Step 3: Feed the average** in `onFix`, right after `val moving = ...`:

```kotlin
        // Wheel speed when a wheel reports one, GPS otherwise.
        val rideKmh = if (wheelKmh > 0.0) wheelKmh else gpsSpeed * 3.6
        movingAverage.add(now, rideKmh, movingKmh)
```

And a helper:

```kotlin
    private fun routedEta(now: Long, remainingM: Double): Long =
        NavEta.etaEpochMs(now, remainingM,
            NavEta.speedKmh(cruiseKmh, movingAverage.avgKmh, movingAverage.movingSeconds, tiltbackKmh))

    private fun straightEta(now: Long, distM: Double): Long {
        closingSpeed.add(now, distM)
        return closingSpeed.kmh()?.let { NavEta.etaEpochMs(now, distM, it) } ?: 0L
    }
```

`computeTurnByTurn` already receives `now`; `guideHoming` does not: add a `now: Long` parameter and pass `now` from its caller in `computeTurnByTurn`.

- [ ] **Step 4: Set the fields** in each `_navState.value = _navState.value.copy(...)`:
  - `computeTurnByTurn` main copy:

```kotlin
            remainingM = distToEnd,
            nextStopRemainingM = waypointAlongM.getOrNull(reached + 1)
                ?.let { (it - hit.alongM).coerceAtLeast(0.0) } ?: distToEnd,
            straightLine = false,
            etaEpochMs = routedEta(now, distToEnd),
```

  - `guideHoming`: `remainingM = dist, nextStopRemainingM = dist, straightLine = true, etaEpochMs = straightEta(now, dist)`
  - `computeTreasureHunt` copy: same four with `dist`.
  - heading-null branch copy in `onFix` (waiting): `remainingM = distToGoal, nextStopRemainingM = distToGoal, straightLine = navMode == NavMode.TREASURE_HUNT, etaEpochMs = 0L`.

- [ ] **Step 5: Build and run all unit tests**

`.\gradlew.bat :app:testDebugUnitTest 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|FAILED"` → BUILD SUCCESSFUL. Check the watch module still builds if it shares `NavState` (`grep -rl "NavState(" --include=*.kt . | grep -v app/src/main`); if a `wear` module uses it, run `.\gradlew.bat :wear:assembleDebug` too.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/eried/eucplanet/data/model/NavModels.kt app/src/main/java/com/eried/eucplanet/nav/NavigationEngine.kt
git commit -m "feat(nav): the engine reports distance left, next-stop distance and arrival time"
```

---

### Task 6: Map camera follows the rider low on screen, heading-up or north-up

**Files:**
- Modify: `ui/navigator/MapHtml.kt` (auto-follow block ~404-616, bridge list comment at top ~49)

**Interfaces:**
- Produces JS bridge functions called by Task 7:
  - `nativeSetNavigatorView(on)`: when true, auto-follow targets the rider (not the rider/stop midpoint).
  - `nativeSetNavLookAhead(px)`: CSS px the rider sits BELOW the visible centre (lower third).
  - `nativeSetNorthUp(on)`: when true, auto-follow keeps bearing 0 instead of the rider's heading.

- [ ] **Step 1: Add state and bridge** next to `navRecenterOffsetPx`:

```javascript
  // Navigator view: follow the rider (not the midpoint to the next stop),
  // low on screen so more road ahead shows, heading-up or north-up.
  var navigatorView = false;
  var navLookAheadPx = 0;
  var navNorthUp = false;
  window.nativeSetNavigatorView = function(on){ navigatorView = !!on; };
  window.nativeSetNavLookAhead = function(px){ navLookAheadPx = px || 0; };
  window.nativeSetNorthUp = function(on){ navNorthUp = !!on; bearingEase = null; };
```

- [ ] **Step 2: Target the rider.** In the target-centre block, wrap the midpoint so it only applies outside navigator view, and lower the rider:

```javascript
      var centerLat = riderLL.lat;
      var centerLng = riderLL.lng;
      if (!navigatorView && nextActiveMarkerIdx >= 0 && nextActiveMarkerIdx < markers.length) {
        var nextLL2 = markers[nextActiveMarkerIdx].getLatLng();
        centerLat = (riderLL.lat + nextLL2.lat) / 2;
        centerLng = (riderLL.lng + nextLL2.lng) / 2;
      }
      // centerLatLngFor puts the point ABOVE centre for a positive offset,
      // so the look-ahead is subtracted to put the rider below it.
      var followOffsetPx = navRecenterOffsetPx - (navigatorView ? navLookAheadPx : 0);
      var targetCenter = centerLatLngFor(
        centerLat, centerLng, newZoom, followOffsetPx
      );
```

- [ ] **Step 3: North-up.** In the rotation part, right after `if (now - movingSinceMs < ROTATE_START_DELAY_MS) {...}`, change `var target = -smoothedHeading;` to:

```javascript
    var target = navNorthUp ? 0 : -smoothedHeading;
```

Switching to north-up while standing still is handled from Kotlin: Task 7 calls the existing `nativeResetNorth()` when the rider turns north-up on, so no extra JS is needed for that case.

- [ ] **Step 4: Document** the three functions in the bridge list comment at the top of `MapHtml.kt`.

- [ ] **Step 5: Build**: `.\gradlew.bat :app:assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:"` → BUILD SUCCESSFUL. (Behaviour is checked on the emulator in Task 9.) Watch the 65535-byte constant limit: if `ROUTE_BUILDER_HTML_1` grows past it the build fails with "string too large"; move the added block into `ROUTE_BUILDER_HTML_2` if so.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/eried/eucplanet/ui/navigator/MapHtml.kt
git commit -m "feat(map): navigator camera follows the rider low on screen, north-up option"
```

---

### Task 7: The navigator overlay on the map screen

**Files:**
- Create: `ui/navigator/NavigatorHud.kt`
- Modify: `ui/navigator/RouteBuilderViewModel.kt` (expose nav state, wheel data, units, tile settings, setters)
- Modify: `ui/navigator/RouteBuilderScreen.kt` (Start stays on the map ~726-737; compass ~1174-1207; overlay placement inside the map `Box`; JS pushes near `nativeSetRecenterOffset` ~991-1001; panel fold; keep screen on; next-stop countdown passed to the stops panel)

**Interfaces:**
- Consumes: `NavState.remainingM/nextStopRemainingM/straightLine/etaEpochMs` (Task 5), `MetricText`, `MetricTextInputs` (Task 4), `NavigatorViewSettings` (Task 1), strings (Task 2), JS bridge (Task 6).
- Produces in `RouteBuilderViewModel`:

```kotlin
    val navState: StateFlow<NavState>                       // navigationEngine.navState
    val wheelData: StateFlow<WheelData>                     // wheelRepository.wheelData
    val wheelLive: StateFlow<Boolean>                       // connection state == CONNECTED
    val navigatorView: StateFlow<NavigatorViewSettings>     // from settings
    val unitsForMetrics: StateFlow<MetricUnits>             // speed/distance/temp/pressure unit strings
    fun setNavigatorTile(index: Int, key: String)
    fun toggleNavNorthUp()
```

  with `data class MetricUnits(val speed: String, val distance: String, val temp: String, val pressure: String)` declared in `NavigatorHud.kt`, built with `Units.effectiveSpeedUnit/effectiveDistanceUnit/effectiveTempUnit/effectivePressureUnit(settings)`. Check whether the VM already injects `WheelRepository` (grep `wheelRepository` in the VM); inject it if not. Settings writes go through `settingsRepository.update { it.copy(navigatorView = ...) }`.

- [ ] **Step 1: ViewModel.** Add the flows and setters above. `setNavigatorTile` uses `NavigatorViewSettings.metricSlots(csv, KNOWN_DASHBOARD_METRICS.toSet())`, replaces the index, joins with `,`. `toggleNavNorthUp` flips `northUp`.

- [ ] **Step 2: `NavigatorHud.kt` composables** (all colors from `MaterialTheme.appColors`; pick existing tokens used by `OverlayFab` / the stops panel for card background and text, read `ui/theme/AppThemeColors.kt` for names):
  - `NavTurnCard(state: NavState, imperial: Boolean, modifier)`: rotating arrow (`Icons.AutoMirrored.Filled.ArrowUpward` rotated by `state.arrowAngleDeg()`), `state.distanceText` large, `state.primaryText` + `state.nextStreet` small (when `straightLine`, show `stringResource(R.string.nav_view_straight_line)` instead of the street), and an info line `"$clock · $left"` where `clock = DateFormat.getTimeFormat(context).format(Date(state.etaEpochMs))` only when `etaEpochMs > 0`, and `left = NavFormat.distance(context, state.remainingM, imperial)` only when `!remainingM.isNaN()`. Off route: show the engine's `primaryText` as is (the engine already says it). Width wraps content, max 220.dp.
  - `NavSpeedBubble(kmh: Float, speedUnit: String, modifier)`: 64.dp circle, value `"%.0f".format(Units.speed(kmh, speedUnit))`, unit `MetricText.speedUnitLabel(speedUnit)`. Content description `stringResource(R.string.nav_view_speed)`.
  - `NavTileColumn(keys: List<String>, valueOf: (String) -> String, onLongPress: (Int) -> Unit, modifier)`: 4 tiles stacked, each value (bold) over `metricChipLabel(key, short = true)` (make `metricChipLabel` `internal` reachable; it already is `internal`), width 76.dp, `combinedClickable(onClick = {}, onLongClick = { onLongPress(i) })`.
  - `NavMetricPicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit)`: `ModalBottomSheet` (containerColor `MaterialTheme.appColors.sheetBackground`) with title `nav_view_pick_metric` and a `LazyColumn` of `KNOWN_DASHBOARD_METRICS` rows labelled `metricChipLabel(key)`, current one marked with a check icon.

- [ ] **Step 3: Wire into `RouteBuilderScreen`.**
  - Start stays on the map: in `startNav` and `startNavForce` replace `{ onExit() }` with `{ panelExpanded = false }` (portrait folds; landscape ignores `panelExpanded`).
  - Compute `val navigatorOn = navRunning` and push to JS when it or the page changes:

```kotlin
        LaunchedEffect(pageReady, navRunning, navigatorView.northUp, lookAheadPx) {
            val wv = webView ?: return@LaunchedEffect
            if (!pageReady) return@LaunchedEffect
            wv.evaluateJavascript("nativeSetNavigatorView(${navRunning});", null)
            wv.evaluateJavascript("nativeSetNavLookAhead($lookAheadPx);", null)
            wv.evaluateJavascript("nativeSetNorthUp(${navigatorView.northUp});", null)
        }
```

    with `lookAheadPx = (visibleMapHeightPx / 6f) / density.density`, where `visibleMapHeightPx` is the map box height minus `effectiveTopBarPx` and minus `effectivePanelPx` in portrait (0 panel in landscape). Measure the map box with `onSizeChanged` if no height is tracked yet.
  - Compass: while `navRunning`, `onClick = { viewModel.toggleNavNorthUp(); if (!navigatorView.northUp) webView?.evaluateJavascript("nativeResetNorth();", null) }` (switching TO north-up resets north at once), `contentDescription` from `nav_view_north_up` / `nav_view_heading_up`, full alpha. Not navigating: unchanged.
  - Overlay, only `if (navRunning && navState.active)`:
    - `NavTurnCard` at `Alignment.TopStart`, `padding(top = padding.calculateTopPadding() + 12.dp, start = 12.dp)`.
    - `NavTileColumn` at `Alignment.TopEnd`, below the compass (`top = topPadding + 12.dp + 56.dp + 8.dp`, `end = 12.dp`). In landscape add the sidebar width on the sidebar's side so it sits on the map (`navStopsSide`, `advancedVars.navSidebarWidthDp`).
    - `NavSpeedBubble` at `Alignment.BottomStart`, bottom padding = folded panel height (`effectivePanelPx` in dp) + 12.dp in portrait, 12.dp in landscape. Speed: `wheelLive ? wheelData.speed.absoluteValue : (gps speed km/h from the VM's current location, or 0)`.
    - Tile values: `MetricText.of(key, inputs)` with `MetricTextInputs` built from `wheelData`, `wheelLive`, `unitsForMetrics`, `currentAsWatts = false`, `tripMeterKm = -1f` (TRIP_METER then prints from the trip meter only if the VM has it; acceptable placeholder otherwise), phone battery `-1`, GPS fields from the VM's location flow when available, else null.
    - Long-press opens `NavMetricPicker` for that slot; `onPick = { viewModel.setNavigatorTile(slot, it) }`.
  - Make sure the FABs (Layers, My-location) do not overlap the tile column: they live bottom-end; the column is top-end. If they collide on short screens, cap the column with `heightIn(max = ...)` so it never reaches the FAB stack.
  - Keep the screen on while navigating on the map:

```kotlin
    val view = LocalView.current
    DisposableEffect(navRunning) {
        view.keepScreenOn = navRunning
        onDispose { view.keepScreenOn = false }
    }
```

    `View.keepScreenOn` is OR-ed with the window flag the dashboard's keep-on setting sets, so when that setting is on nothing changes.
  - Next-stop countdown: pass `nextStopRemainingM = if (navRunning) navState.nextStopRemainingM else Double.NaN` into the stops panel composable (the one receiving `stopLegs`, ~1505-1530) and, in the row of the first not-passed stop, show `NavFormat.distance(context, it, imperial)` instead of the planned leg distance when it is not NaN. Later rows keep `stopLegs`. When navigation ends the value is NaN, so the planned distance returns.

- [ ] **Step 4: Build** `.\gradlew.bat :app:assembleDebug 2>&1 | Select-String "BUILD SUCCESSFUL|BUILD FAILED|error:"` → BUILD SUCCESSFUL, and full unit tests → BUILD SUCCESSFUL (`LifecycleWiringTest`, `AppStartupTest` must still pass).

- [ ] **Step 5: Commit**

```
git add -A app/src/main/java/com/eried/eucplanet/ui/navigator
git commit -m "feat(nav): navigator view on the map, turn card, speed, tiles; Start stays on the map"
```

---

### Task 8: Navigator tiles in Settings > Navigator

**Files:**
- Modify: `ui/settings/NavigatorSettingsContent.kt` (new block before the weather section, line ~284)
- Modify: `ui/settings/SettingsViewModel.kt` (setters next to `updateAndroidAutoMetricSlot` ~1054)

**Interfaces:**
- Consumes: `NavigatorViewSettings` (Task 1), `NavMetricPicker` (Task 7), strings (Task 2).
- Produces: `SettingsViewModel.updateNavigatorTile(index: Int, key: String)`, `SettingsViewModel.resetNavigatorTiles()`.

- [ ] **Step 1: ViewModel setters**

```kotlin
    fun updateNavigatorTile(index: Int, key: String) = update {
        val slots = com.eried.eucplanet.data.model.NavigatorViewSettings
            .metricSlots(navigatorView.metrics, KNOWN_DASHBOARD_METRICS.toSet()).toMutableList()
        if (index in slots.indices) slots[index] = key
        copy(navigatorView = navigatorView.copy(metrics = slots.joinToString(",")))
    }

    fun resetNavigatorTiles() = update {
        copy(navigatorView = navigatorView.copy(
            metrics = com.eried.eucplanet.data.model.NavigatorViewSettings.DEFAULT_METRICS))
    }
```

- [ ] **Step 2: UI block** using the page's existing `SectionHeader` and row conventions: header `nav_view_tiles`, description `nav_view_tiles_desc`, 4 rows `stringResource(R.string.nav_view_tile_n, i + 1)` with the current `metricChipLabel(key)` as a clickable value that opens `NavMetricPicker`, and a `TextButton` `nav_view_reset_tiles` that also shows the default (list the 4 default labels in its row). Colors from `MaterialTheme.appColors`.

- [ ] **Step 3: Build + unit tests** (commands above) → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```
git add -A app/src/main/java/com/eried/eucplanet/ui/settings
git commit -m "feat(settings): pick and reset the navigator tiles"
```

---

### Task 9: Emulator verification and tester notes (controller does this)

Not dispatched to an implementer: the controller runs it on its private emulator (port 5560+, never 5554/5556/5566 or the physical device), after checking the installed APK md5 matches the fresh build.

- [ ] Build `.\gradlew.bat :app:assembleDebug`, install on the private emulator, confirm md5.
- [ ] Virtual wheel connected (debug survey broadcasts), build a 2-stop route, Start: the app stays on the map, the panel folds, turn card + arrival time + distance left show, speed bubble moves, 4 tiles show Voltage/Battery/Trip/PWM with live numbers.
- [ ] Long-press a tile, pick Speed: it changes and survives an app restart. Settings > Navigator shows the same 4, Reset tiles restores them.
- [ ] Compass toggles north-up and back; pan the map: following and rotation return after about 6 s; tap a stop: pans to it, returns after 6 s.
- [ ] Open the panel: first stop counts down, End navigation confirms and ends; the map returns to normal with planned distances.
- [ ] Imperial units: card and countdown in miles/feet.
- [ ] Straight (Treasure hunt) route: card says straight line; arrival time appears once closing in.
- [ ] Screen stays on while navigating on the map with the dashboard keep-on setting off; normal timeout after End.
- [ ] Landscape: card and tiles on the map, sidebar unchanged.
- [ ] Dashboard: nav arrow and turn card still work; dashboard tiles show the same text as before the refactor.
- [ ] `adb logcat` has no `AndroidRuntime: FATAL`.
- [ ] Update `BRANCH.md` (Worked on, Please test), remove `docs/superpowers/` files before any merge into a long-lived branch, commit.
