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

    // --- beep PITCH modulation (factorPct) ---
    // factorPct = 100 means "the pitch has risen by 100% of base (doubled) once
    // the value is 50% of the threshold past it"; frac scales linearly in between.

    @Test
    fun pitchFactorZeroIsAlwaysFixed() {
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 90f, comparator = GE, threshold = 30f, factorPct = 0))
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 30f, comparator = GE, threshold = 30f, factorPct = 0))
    }

    @Test
    fun pitchBaseAtOrBeforeThreshold() {
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 30f, comparator = GE, threshold = 30f, factorPct = 100))
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 20f, comparator = GE, threshold = 30f, factorPct = 100))
    }

    @Test
    fun pitchFactor100DoublesAtHalfThresholdPast() {
        // 45 = +50% of 30 => frac 1 => base + 100%*base = 2000.
        assertEquals(2000, AlarmLogic.modulatedBeepHz(1000, value = 45f, comparator = GE, threshold = 30f, factorPct = 100))
    }

    @Test
    fun pitchIsLinearMidway() {
        // 37.5 = +25% => frac 0.5 => base + 100%*base*0.5 = 1500.
        assertEquals(1500, AlarmLogic.modulatedBeepHz(1000, value = 37.5f, comparator = GE, threshold = 30f, factorPct = 100))
    }

    @Test
    fun pitchFactorScalesTheClimb() {
        // Same overshoot, different strength: 50% -> +500, 200% -> +2000.
        assertEquals(1500, AlarmLogic.modulatedBeepHz(1000, value = 45f, comparator = GE, threshold = 30f, factorPct = 50))
        assertEquals(3000, AlarmLogic.modulatedBeepHz(1000, value = 45f, comparator = GE, threshold = 30f, factorPct = 200))
    }

    @Test
    fun pitchOvershootClampsToFullStrength() {
        // Far past the threshold still caps at the factor's full rise (frac=1).
        assertEquals(2000, AlarmLogic.modulatedBeepHz(1000, value = 200f, comparator = GE, threshold = 30f, factorPct = 100))
    }

    @Test
    fun pitchRespectsHardCeiling() {
        // 8000 base, +200% would be 24000; ceiling clamps to 20000.
        assertEquals(20000, AlarmLogic.modulatedBeepHz(8000, value = 45f, comparator = GE, threshold = 30f, factorPct = 200))
        // Mid-range no longer clamps at the old 4 kHz: 2500 +200% = 7500.
        assertEquals(7500, AlarmLogic.modulatedBeepHz(2500, value = 45f, comparator = GE, threshold = 30f, factorPct = 200))
    }

    @Test
    fun pitchRisesForLessThanAsValueFalls() {
        // battery threshold 20, base 1000, factor 100: 10 (50% below) doubles, 15 midway.
        assertEquals(1000, AlarmLogic.modulatedBeepHz(1000, value = 20f, comparator = LT, threshold = 20f, factorPct = 100))
        assertEquals(1500, AlarmLogic.modulatedBeepHz(1000, value = 15f, comparator = LT, threshold = 20f, factorPct = 100))
        assertEquals(2000, AlarmLogic.modulatedBeepHz(1000, value = 10f, comparator = LT, threshold = 20f, factorPct = 100))
    }

    // --- beep VOLUME modulation (base% + modPct, capped at system 100%) ---

    @Test
    fun volumeModZeroIsConstantBase() {
        assertEquals(90, AlarmLogic.modulatedVolumePct(90, 0, value = 45f, comparator = GE, threshold = 30f))
        assertEquals(60, AlarmLogic.modulatedVolumePct(60, 0, value = 90f, comparator = GE, threshold = 30f))
    }

    @Test
    fun volumeBaseAtThreshold() {
        assertEquals(90, AlarmLogic.modulatedVolumePct(90, 100, value = 30f, comparator = GE, threshold = 30f))
    }

    @Test
    fun volumeReachesSystemAtFullOvershoot() {
        // base 90 climbs the remaining 10 to 100 at +50% past.
        assertEquals(100, AlarmLogic.modulatedVolumePct(90, 100, value = 45f, comparator = GE, threshold = 30f))
    }

    @Test
    fun volumeIsLinearMidway() {
        // base 80, +25% (frac 0.5) => 80 + (100-80)*0.5 = 90.
        assertEquals(90, AlarmLogic.modulatedVolumePct(80, 100, value = 37.5f, comparator = GE, threshold = 30f))
    }

    @Test
    fun volumeModFactorScalesTheClimb() {
        // base 80, full overshoot, half strength => 80 + (100-80)*0.5 = 90.
        assertEquals(90, AlarmLogic.modulatedVolumePct(80, 50, value = 45f, comparator = GE, threshold = 30f))
    }

    @Test
    fun volumeBase100HasNothingToClimb() {
        // The "disable mod at 100%" case: already at system, modulation is a no-op.
        assertEquals(100, AlarmLogic.modulatedVolumePct(100, 100, value = 45f, comparator = GE, threshold = 30f))
    }

    @Test
    fun volumeNeverExceedsSystem() {
        assertEquals(100, AlarmLogic.modulatedVolumePct(90, 300, value = 90f, comparator = GE, threshold = 30f))
    }

    @Test
    fun volumeRisesForLessThanAsValueFalls() {
        assertEquals(100, AlarmLogic.modulatedVolumePct(80, 100, value = 10f, comparator = LT, threshold = 20f))
    }
}
