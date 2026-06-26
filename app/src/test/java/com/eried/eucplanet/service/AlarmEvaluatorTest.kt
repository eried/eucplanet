package com.eried.eucplanet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GE = "GREATER_EQUAL"
private const val LT = "LESS_THAN"
private const val SPEED = "SPEED"
private const val BATTERY = "BATTERY"
private const val RADAR = "RADAR_DISTANCE"

/**
 * Behavioural tests for the actual alarm triggering: which rule fires, exactly
 * when, across a sequence of telemetry ticks. This is the safety-critical part,
 * so it's covered directly rather than only through the pure helpers.
 *
 * Timestamps use a realistic epoch base (a never-fired rule's lastFire is 0, and
 * production `now` is always >> any cooldown, so the first match always fires).
 */
class AlarmEvaluatorTest {

    private val t0 = 1_700_000_000_000L

    private fun rule(
        id: Long,
        threshold: Float,
        metric: String = SPEED,
        comparator: String = GE,
        cd: Int = 5,
        repeat: Boolean = false,
        lead: Int = 0,
    ) = AlarmEvaluator.Rule(id, metric, comparator, threshold, cd, repeat, lead)

    /** Fire a single-metric tick; return the ids that fired. */
    private fun AlarmEvaluator.tick(rules: List<AlarmEvaluator.Rule>, now: Long, value: Float): List<Long> =
        evaluate(rules, now) { value }.map { it.ruleId }

    // --- single rule, Once ---

    @Test
    fun onceFiresOnCrossingThenSilentWhileHeld() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 30f, repeat = false))
        assertEquals(emptyList<Long>(), e.tick(r, t0, 20f))          // below
        assertEquals(listOf(1L), e.tick(r, t0 + 1000, 32f))         // crosses -> fires
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 2000, 33f))  // still over -> silent
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 3000, 40f))  // still over -> silent
    }

    @Test
    fun onceReArmsAfterSafeSideThenFiresAgain() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 30f, repeat = false))
        assertEquals(listOf(1L), e.tick(r, t0, 32f))                // fire
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 1000, 20f))  // back to safe -> re-arm
        assertEquals(listOf(1L), e.tick(r, t0 + 10_000, 31f))       // crosses again -> fire
    }

    @Test
    fun onceDebouncesReCrossingWithinCooldown() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 30f, cd = 5, repeat = false))
        assertEquals(listOf(1L), e.tick(r, t0, 32f))                 // fire @ t0
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 1000, 20f))   // safe
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 2000, 33f))   // re-cross 2s later -> debounced
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 2500, 20f))   // safe again
        assertEquals(listOf(1L), e.tick(r, t0 + 8000, 33f))          // re-cross 8s after last fire -> fires
    }

    // --- single rule, Many ---

    @Test
    fun manyReFiresEveryCooldownWhileHeld() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 30f, cd = 5, repeat = true))
        assertEquals(listOf(1L), e.tick(r, t0, 32f))                 // fire @ t0
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 2000, 33f))   // 2s later -> wait
        assertEquals(listOf(1L), e.tick(r, t0 + 5000, 33f))          // 5s -> re-fire
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 9000, 33f))   // 4s later -> wait
        assertEquals(listOf(1L), e.tick(r, t0 + 10_000, 33f))        // 5s -> re-fire
    }

    @Test
    fun manyStopsWhenValueLeavesAlarmZone() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 30f, cd = 5, repeat = true))
        assertEquals(listOf(1L), e.tick(r, t0, 32f))
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 6000, 20f))   // safe -> no fire even though cooldown elapsed
    }

    // --- the eaten-alarm scenario (the whole reason for the rework) ---

    @Test
    fun higher35FiresWhenReaching37WithLower30MidCooldown() {
        val e = AlarmEvaluator()
        val rules = listOf(rule(1, 30f, repeat = false), rule(2, 35f, repeat = false))
        assertEquals(listOf(1L), e.tick(rules, t0, 32f))            // 30 fires
        // 37 reached 1s later, within 30's 5s cooldown: 35 must still fire.
        assertEquals(listOf(2L), e.tick(rules, t0 + 1000, 37f))     // 35 fires, NOT eaten
    }

    @Test
    fun selectionIsOrderIndependent() {
        val a = AlarmEvaluator()
        val b = AlarmEvaluator()
        val fwd = listOf(rule(1, 30f), rule(2, 35f))
        val rev = listOf(rule(2, 35f), rule(1, 30f))
        // Regardless of list order, the most-severe matched rule (35, id 2) fires.
        assertEquals(listOf(2L), a.tick(fwd, t0, 37f))
        assertEquals(listOf(2L), b.tick(rev, t0, 37f))
    }

    @Test
    fun mostSevereOfThreeTiersFires() {
        val e = AlarmEvaluator()
        val rules = listOf(rule(1, 30f), rule(2, 35f), rule(3, 40f))
        assertEquals(listOf(3L), e.tick(rules, t0, 42f))            // 40 is most severe matched
    }

    // --- LESS_THAN (battery) ---

    @Test
    fun lessThanFiresAndPicksLowerThresholdAsMoreSevere() {
        val e = AlarmEvaluator()
        val rules = listOf(rule(1, 20f, metric = BATTERY, comparator = LT), rule(2, 10f, metric = BATTERY, comparator = LT))
        assertEquals(listOf(1L), e.tick(rules, t0, 15f))            // only <20 matches
        assertEquals(listOf(2L), e.tick(rules, t0 + 10_000, 8f))    // both match -> <10 (more severe)
    }

    // --- predictive ---

    @Test
    fun predictiveFiresEarlyOnRisingRamp() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 95f, lead = 2000, repeat = false)) // 2 s look-ahead
        // 10 units/s ramp, 250 ms ticks. Slope needs >=3 samples spanning >=300 ms.
        assertEquals(emptyList<Long>(), e.tick(r, t0, 70f))
        assertEquals(emptyList<Long>(), e.tick(r, t0 + 250, 72.5f))
        val fired = e.evaluate(r, t0 + 500) { 75f }                 // slope ~10/s -> projects 75 + 20 = 95
        assertEquals(listOf(1L), fired.map { it.ruleId })
        assertTrue("should fire while still below the threshold", fired[0].value < 95f)
    }

    @Test
    fun predictiveDoesNotFireWhenFlat() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 95f, lead = 2000, repeat = false))
        repeat(6) { i -> assertEquals(emptyList<Long>(), e.tick(r, t0 + i * 250L, 80f)) }
    }

    // --- multi-metric independence ---

    @Test
    fun metricsAreIndependent() {
        val e = AlarmEvaluator()
        val rules = listOf(
            rule(1, 30f, metric = SPEED, comparator = GE),
            rule(2, 20f, metric = BATTERY, comparator = LT),
        )
        val v1 = mapOf(SPEED to 32f, BATTERY to 50f)
        assertEquals(listOf(1L), e.evaluate(rules, t0) { v1[it] }.map { it.ruleId })
        val v2 = mapOf(SPEED to 10f, BATTERY to 15f)
        assertEquals(listOf(2L), e.evaluate(rules, t0 + 10_000) { v2[it] }.map { it.ruleId })
    }

    // --- no-reading policies ---

    @Test
    fun skipLeavesStateUntouched() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 30f, metric = SPEED, repeat = false))
        assertEquals(listOf(1L), e.evaluate(r, t0) { 32f }.map { it.ruleId })    // fire, armed
        // SPEED absent this tick (null) with default SKIP -> state preserved.
        assertEquals(emptyList<Long>(), e.evaluate(r, t0 + 1000, AlarmEvaluator.NoReading.SKIP) { null })
        // Still held -> Once stays silent (proves SKIP did not disarm).
        assertEquals(emptyList<Long>(), e.evaluate(r, t0 + 2000) { 33f }.map { it.ruleId })
    }

    @Test
    fun radarResetReArmsOnLaneClear() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 40f, metric = RADAR, comparator = LT, cd = 5, repeat = false))
        // Car at 30 m -> fire.
        assertEquals(listOf(1L), e.evaluate(r, t0, AlarmEvaluator.NoReading.RESET) { 30f }.map { it.ruleId })
        // Lane clear -> disarm.
        assertEquals(emptyList<Long>(), e.evaluate(r, t0 + 1000, AlarmEvaluator.NoReading.RESET) { null })
        // New car well after the cooldown -> fires fresh.
        assertEquals(listOf(1L), e.evaluate(r, t0 + 8000, AlarmEvaluator.NoReading.RESET) { 30f }.map { it.ruleId })
    }

    @Test
    fun radarReAppearWithinCooldownDoesNotFire_documentsCurrentBehaviour() {
        // NOTE: after a lane-clear, the cooldown still gates the next car. A car
        // re-appearing within the cooldown stays silent. Documented so any future
        // change to "fresh car always alerts" is a conscious decision.
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 40f, metric = RADAR, comparator = LT, cd = 5, repeat = false))
        assertEquals(listOf(1L), e.evaluate(r, t0, AlarmEvaluator.NoReading.RESET) { 30f }.map { it.ruleId })
        assertEquals(emptyList<Long>(), e.evaluate(r, t0 + 1000, AlarmEvaluator.NoReading.RESET) { null })
        assertEquals(emptyList<Long>(), e.evaluate(r, t0 + 2000, AlarmEvaluator.NoReading.RESET) { 30f }.map { it.ruleId })
    }

    // --- pruning ---

    @Test
    fun pruneClearsStateForRemovedRules() {
        val e = AlarmEvaluator()
        val r = listOf(rule(1, 30f, repeat = false))
        assertEquals(listOf(1L), e.tick(r, t0, 32f))               // fire, armed
        e.prune(emptySet(), emptySet())                            // rule removed
        // Fresh state -> a held value fires again (would stay silent if state survived).
        assertEquals(listOf(1L), e.tick(r, t0 + 1000, 33f))
    }
}
