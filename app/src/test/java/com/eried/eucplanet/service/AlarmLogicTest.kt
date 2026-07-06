package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GE = "GREATER_EQUAL"
private const val LT = "LESS_THAN"

/**
 * Pure-logic tests for the alarm engine's decision math: the order-independent
 * "most severe matching rule wins" selection that fixes the eaten-alarm bug,
 * and the predictive projection.
 */
class AlarmLogicTest {

    /** Mirror of [AlarmEngine.evaluateMetricGroup]'s selection: among all rules
     *  on a metric, the matched one with the highest severity rank fires. */
    private data class Rule(val comparator: String, val threshold: Float, val leadTimeMs: Int = 0)

    private fun selectMostSevere(rules: List<Rule>, value: Float, slope: Float?): Rule? {
        var best: Rule? = null
        var bestRank = Float.NEGATIVE_INFINITY
        for (r in rules) {
            val match = AlarmLogic.matchesNow(value, r.comparator, r.threshold) ||
                AlarmLogic.predictiveMatch(r.comparator, r.threshold, r.leadTimeMs, value, slope)
            if (match) {
                val rank = AlarmLogic.severityRank(r.comparator, r.threshold)
                if (rank > bestRank) { bestRank = rank; best = r }
            }
        }
        return best
    }

    // --- severity ordering ---

    @Test
    fun greaterEqualHigherThresholdIsMoreSevere() {
        assertTrue(
            AlarmLogic.severityRank(GE, 35f) > AlarmLogic.severityRank(GE, 30f)
        )
    }

    @Test
    fun lessThanLowerThresholdIsMoreSevere() {
        // battery < 10 is graver than battery < 20
        assertTrue(
            AlarmLogic.severityRank(LT, 10f) > AlarmLogic.severityRank(LT, 20f)
        )
    }

    // --- the eaten-alarm scenario ---

    @Test
    fun higherSpeedRuleFiresWhenBothMatch_notEatenByLower() {
        // Rider at 37 with alarms at 30 and 35; both match -> 35 must win.
        val rules = listOf(Rule(GE, 30f), Rule(GE, 35f))
        val winner = selectMostSevere(rules, value = 37f, slope = null)
        assertEquals(35f, winner?.threshold)
    }

    @Test
    fun lowerRuleWinsWhenOnlyItMatches() {
        // At 32 only the 30 rule matches.
        val rules = listOf(Rule(GE, 30f), Rule(GE, 35f))
        val winner = selectMostSevere(rules, value = 32f, slope = null)
        assertEquals(30f, winner?.threshold)
    }

    @Test
    fun selectionIsOrderIndependent() {
        val a = listOf(Rule(GE, 30f), Rule(GE, 35f))
        val b = listOf(Rule(GE, 35f), Rule(GE, 30f))
        assertEquals(selectMostSevere(a, 37f, null)?.threshold, selectMostSevere(b, 37f, null)?.threshold)
    }

    @Test
    fun pwm85And95_at100_fires95() {
        // The reported PWM case: alarms at 85 and 95, PWM hits 100 -> 95 fires.
        val rules = listOf(Rule(GE, 85f), Rule(GE, 95f))
        assertEquals(95f, selectMostSevere(rules, 100f, null)?.threshold)
    }

    @Test
    fun noMatchReturnsNull() {
        val rules = listOf(Rule(GE, 30f), Rule(GE, 35f))
        assertNull(selectMostSevere(rules, 20f, null))
    }

    // --- predictive ---

    @Test
    fun predictiveFiresWhenRisingTowardThresholdInWindow() {
        // value 80, rising 20/s, 1 s lead -> projects to 100 >= 95.
        assertTrue(AlarmLogic.predictiveMatch(GE, 95f, 1000, value = 80f, slope = 20f))
    }

    @Test
    fun predictiveDoesNotFireWhenFlat() {
        assertFalse(AlarmLogic.predictiveMatch(GE, 95f, 1000, value = 80f, slope = 0f))
    }

    @Test
    fun predictiveDoesNotFireWhenRetreating() {
        assertFalse(AlarmLogic.predictiveMatch(GE, 95f, 1000, value = 80f, slope = -20f))
    }

    @Test
    fun predictiveDoesNotFireWhenTooFarForTheWindow() {
        // 80, rising 5/s, 1 s lead -> 85, still < 95.
        assertFalse(AlarmLogic.predictiveMatch(GE, 95f, 1000, value = 80f, slope = 5f))
    }

    @Test
    fun predictiveOffWhenLeadTimeZero() {
        assertFalse(AlarmLogic.predictiveMatch(GE, 95f, 0, value = 94f, slope = 100f))
    }

    @Test
    fun predictiveNullSlopeNeverFires() {
        assertFalse(AlarmLogic.predictiveMatch(GE, 95f, 1000, value = 90f, slope = null))
    }

    @Test
    fun predictiveLessThanFiresWhenFallingTowardThreshold() {
        // battery 22, dropping 5/s, 1 s lead -> 17 < 20 -> fire.
        assertTrue(AlarmLogic.predictiveMatch(LT, 20f, 1000, value = 22f, slope = -5f))
    }

    @Test
    fun predictiveGivesEarlyWarningThatPlainCheckMisses() {
        // At 80 the instant check for a 95 rule is false, but with a rising
        // trend the predictive check fires first -> earlier warning.
        val plain = AlarmLogic.matchesNow(80f, GE, 95f)
        val predicted = AlarmLogic.predictiveMatch(GE, 95f, 1000, 80f, 20f)
        assertFalse(plain)
        assertTrue(predicted)
    }

    // --- slope ---

    @Test
    fun slopeComputesRisePerSecond() {
        // 0,10,20,30 over 0,1,2,3 s -> 10 per second.
        val samples = listOf(0L to 0f, 1000L to 10f, 2000L to 20f, 3000L to 30f)
        val slope = AlarmLogic.slopePerSec(samples, now = 3000L, windowMs = 5000L)
        assertTrue("expected ~10/s, got $slope", slope != null && kotlin.math.abs(slope - 10f) < 0.01f)
    }

    @Test
    fun slopeNullWithTooFewSamples() {
        val samples = listOf(0L to 0f, 1000L to 10f)
        assertNull(AlarmLogic.slopePerSec(samples, now = 1000L))
    }

    @Test
    fun slopeNullWhenSpanTooShort() {
        // Three samples but all within 100 ms -> below the min span, untrusted.
        val samples = listOf(0L to 0f, 50L to 5f, 100L to 10f)
        assertNull(AlarmLogic.slopePerSec(samples, now = 100L))
    }

    @Test
    fun slopeWindowDropsStaleSamples() {
        // An old flat run then a recent ramp; only the in-window ramp counts.
        val samples = listOf(
            0L to 50f, 200L to 50f,          // stale (outside 1.5 s window at now=3000)
            2000L to 60f, 2500L to 70f, 3000L to 80f,
        )
        val slope = AlarmLogic.slopePerSec(samples, now = 3000L)
        assertTrue("expected ~20/s from the recent ramp, got $slope",
            slope != null && kotlin.math.abs(slope - 20f) < 1f)
    }

    @Test
    fun comparatorParseToleratesLegacyNames() {
        // Defensive: legacy stored strings still map sanely (used via matchesNow).
        assertEquals(AlarmComparator.GREATER_EQUAL, AlarmComparator.parse("GREATER_THAN"))
        assertEquals(AlarmComparator.LESS_THAN, AlarmComparator.parse("LESS_EQUAL"))
    }

    // --- fire decision (Once vs Many + cooldown), safety-critical ---

    private val cd = 5_000L // 5 s cooldown

    @Test
    fun neverFiresWhenNotMatched() {
        assertFalse(AlarmLogic.shouldFire(matched = false, wasActive = false, msSinceLastFire = 999_999, cooldownMs = cd, repeatWhileActive = true))
        assertFalse(AlarmLogic.shouldFire(matched = false, wasActive = true, msSinceLastFire = 999_999, cooldownMs = cd, repeatWhileActive = false))
    }

    @Test
    fun onceFiresOnFreshCrossing() {
        // wasActive false -> matched true is a new crossing; cooldown long past.
        assertTrue(AlarmLogic.shouldFire(matched = true, wasActive = false, msSinceLastFire = 999_999, cooldownMs = cd, repeatWhileActive = false))
    }

    @Test
    fun onceDoesNotReAlertWhileHeld() {
        // Still past the threshold (wasActive true), Once mode: silent.
        assertFalse(AlarmLogic.shouldFire(matched = true, wasActive = true, msSinceLastFire = 999_999, cooldownMs = cd, repeatWhileActive = false))
    }

    @Test
    fun onceDebouncesRapidReCrossingWithinCooldown() {
        // Fresh crossing but only 1 s since the last alert -> cooldown blocks it.
        assertFalse(AlarmLogic.shouldFire(matched = true, wasActive = false, msSinceLastFire = 1_000, cooldownMs = cd, repeatWhileActive = false))
    }

    @Test
    fun manyFiresOnFreshCrossing() {
        assertTrue(AlarmLogic.shouldFire(matched = true, wasActive = false, msSinceLastFire = 999_999, cooldownMs = cd, repeatWhileActive = true))
    }

    @Test
    fun manyReAlertsWhileHeldOnceCooldownElapses() {
        // Still held (wasActive true), Many mode, cooldown elapsed -> re-alert.
        assertTrue(AlarmLogic.shouldFire(matched = true, wasActive = true, msSinceLastFire = cd, cooldownMs = cd, repeatWhileActive = true))
    }

    @Test
    fun manyStaysQuietUntilCooldownElapses() {
        // Held, Many mode, but only 2 s since last alert -> wait.
        assertFalse(AlarmLogic.shouldFire(matched = true, wasActive = true, msSinceLastFire = 2_000, cooldownMs = cd, repeatWhileActive = true))
    }

    @Test
    fun cooldownBoundaryIsInclusive() {
        // Exactly cooldownMs since last alert counts as elapsed.
        assertTrue(AlarmLogic.shouldFire(matched = true, wasActive = true, msSinceLastFire = cd, cooldownMs = cd, repeatWhileActive = true))
        assertFalse(AlarmLogic.shouldFire(matched = true, wasActive = true, msSinceLastFire = cd - 1, cooldownMs = cd, repeatWhileActive = true))
    }

    // --- metric reach span: modulation reaches its factor at the metric's far
    // limit (so it never simulates an impossible value like PWM > 100).

    @Test
    fun reachSpanRespectsMetricLimits() {
        assertEquals(20f, AlarmLogic.metricReachSpan("PWM", GE, 80f))         // 100 - 80
        assertEquals(120f, AlarmLogic.metricReachSpan("SPEED", GE, 30f))      // 150 - 30
        assertEquals(20f, AlarmLogic.metricReachSpan("BATTERY", LT, 20f))     // 20 - 0
        assertEquals(50f, AlarmLogic.metricReachSpan("VOLTAGE", LT, 60f))     // 60 - 10 (min)
        assertEquals(70f, AlarmLogic.metricReachSpan("TEMPERATURE", GE, 20f)) // 90 - 20
    }

    @Test
    fun metricRangesMatchSpec() {
        assertEquals(0f, AlarmLogic.metricReadMin("PWM"));          assertEquals(100f, AlarmLogic.metricReadMax("PWM"))
        assertEquals(0f, AlarmLogic.metricReadMin("SPEED"));        assertEquals(150f, AlarmLogic.metricReadMax("SPEED"))
        assertEquals(-10f, AlarmLogic.metricReadMin("TEMPERATURE")); assertEquals(90f, AlarmLogic.metricReadMax("TEMPERATURE"))
        assertEquals(10f, AlarmLogic.metricReadMin("VOLTAGE"));     assertEquals(480f, AlarmLogic.metricReadMax("VOLTAGE"))
        assertEquals(0f, AlarmLogic.metricReadMin("CURRENT"));      assertEquals(200f, AlarmLogic.metricReadMax("CURRENT"))
        assertEquals(0f, AlarmLogic.metricReadMin("BATTERY"));      assertEquals(100f, AlarmLogic.metricReadMax("BATTERY"))
    }

    // --- beep PITCH modulation: factor x100 (100 = 1.0x = unchanged). Ramps the
    // pitch toward base*factor, reaching the factor at the metric's far limit.
    // >1.0x ramps up, <1.0x reduces. Clamped to [80, 20000] Hz.

    @Test
    fun pitchUnityIsFlat() {
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 90f, comparator = GE, threshold = 30f, factorX100 = 100, metric = "SPEED"))
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 30f, comparator = GE, threshold = 30f, factorX100 = 100, metric = "SPEED"))
    }

    @Test
    fun pitchBaseAtThreshold() {
        // 2x factor but at the threshold (frac 0) -> still base.
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 80f, comparator = GE, threshold = 80f, factorX100 = 200, metric = "PWM"))
    }

    @Test
    fun pitchReachesFactorAtMetricMax() {
        // PWM threshold 80, max 100 -> span 20; the 2x factor is hit at PWM 100, half at 90.
        assertEquals(2000, AlarmLogic.modulatedBeepHz(1000, value = 100f, comparator = GE, threshold = 80f, factorX100 = 200, metric = "PWM"))
        assertEquals(1500, AlarmLogic.modulatedBeepHz(1000, value = 90f, comparator = GE, threshold = 80f, factorX100 = 200, metric = "PWM"))
    }

    @Test
    fun pitchSpeedSpansToMax() {
        // SPEED threshold 30, max 150 -> span 120; value 90 is halfway, 150 is full.
        assertEquals(1500, AlarmLogic.modulatedBeepHz(1000, value = 90f, comparator = GE, threshold = 30f, factorX100 = 200, metric = "SPEED"))
        assertEquals(2000, AlarmLogic.modulatedBeepHz(1000, value = 150f, comparator = GE, threshold = 30f, factorX100 = 200, metric = "SPEED"))
    }

    @Test
    fun pitchBelowUnityReduces() {
        // PWM 80, 0.5x ramps DOWN: at PWM 100 -> 500, halfway -> 750.
        assertEquals(500, AlarmLogic.modulatedBeepHz(1000, value = 100f, comparator = GE, threshold = 80f, factorX100 = 50, metric = "PWM"))
        assertEquals(750, AlarmLogic.modulatedBeepHz(1000, value = 90f, comparator = GE, threshold = 80f, factorX100 = 50, metric = "PWM"))
    }

    @Test
    fun pitchPlateausAndClamps() {
        assertEquals(2000, AlarmLogic.modulatedBeepHz(1000, value = 200f, comparator = GE, threshold = 30f, factorX100 = 200, metric = "SPEED")) // plateau past max
        assertEquals(20000, AlarmLogic.modulatedBeepHz(8000, value = 100f, comparator = GE, threshold = 80f, factorX100 = 1000, metric = "PWM")) // 10x clamps to ceiling
        assertEquals(80, AlarmLogic.modulatedBeepHz(500, value = 100f, comparator = GE, threshold = 80f, factorX100 = 10, metric = "PWM"))       // 0.1x clamps to floor
    }

    @Test
    fun pitchReducesForLessThanAsValueFalls() {
        // battery threshold 20, span 20 (down to 0); 2x at battery 0, midway at 10.
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 20f, comparator = LT, threshold = 20f, factorX100 = 200, metric = "BATTERY"))
        assertEquals(1500, AlarmLogic.modulatedBeepHz(1000, value = 10f, comparator = LT, threshold = 20f, factorX100 = 200, metric = "BATTERY"))
        assertEquals(2000, AlarmLogic.modulatedBeepHz(1000, value = 0f, comparator = LT, threshold = 20f, factorX100 = 200, metric = "BATTERY"))
    }

    // --- beep VOLUME modulation: factor x100 (100 = 1.0x = unchanged).

    @Test
    fun volumeUnityIsConstant() {
        assertEquals(90, AlarmLogic.modulatedVolumePct(90, 100, value = 100f, comparator = GE, threshold = 80f, metric = "PWM"))
    }

    @Test
    fun volumeBaseAtThreshold() {
        assertEquals(80, AlarmLogic.modulatedVolumePct(80, 150, value = 80f, comparator = GE, threshold = 80f, metric = "PWM"))
    }

    @Test
    fun volumeRampsUpCapped() {
        // base 80, 1.5x -> 120 at PWM 100, clamped to 100.
        assertEquals(100, AlarmLogic.modulatedVolumePct(80, 150, value = 100f, comparator = GE, threshold = 80f, metric = "PWM"))
    }

    @Test
    fun volumeBelowUnityReduces() {
        // base 80, 0.5x -> 40 at PWM 100, 60 halfway (PWM 90).
        assertEquals(40, AlarmLogic.modulatedVolumePct(80, 50, value = 100f, comparator = GE, threshold = 80f, metric = "PWM"))
        assertEquals(60, AlarmLogic.modulatedVolumePct(80, 50, value = 90f, comparator = GE, threshold = 80f, metric = "PWM"))
    }

    @Test
    fun volumeReducesForLessThanAsValueFalls() {
        assertEquals(40, AlarmLogic.modulatedVolumePct(80, 50, value = 0f, comparator = LT, threshold = 20f, metric = "BATTERY"))
    }
}
