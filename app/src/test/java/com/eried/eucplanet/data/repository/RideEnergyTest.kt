package com.eried.eucplanet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole-ride energy figure on Trip Details, integrated from the recorded
 * voltage and current rather than stored in a column, so it works on trips
 * recorded long before the feature existed.
 */
class RideEnergyTest {

    /** One sample per second, at a steady watts. */
    private fun steady(seconds: Int, volts: Float, amps: Float) =
        (0..seconds).map { Triple(it * 1000L, volts, amps) }

    @Test
    fun `an hour at one hundred watts is one hundred watt hours`() {
        // 100 V x 1 A for 3600 s. Positive current is discharge on every family
        // but InMotion V1, so this is the common orientation.
        val e = ChargeEnergy.rideEnergy(steady(3600, 100f, 1f))
        assertEquals(100f, e.outWh, 0.5f)
        assertEquals(0f, e.regenWh, 0.01f)
        assertEquals(100f, e.netWh, 0.5f)
    }

    @Test
    fun `the inverted family convention is inferred, not assumed`() {
        // InMotion V1 reports discharge as positive power, so the raw sign is
        // the other way round. Nothing in the CSV says which family wrote it;
        // the ride still has to come out as consumption rather than as an hour
        // of charging.
        val e = ChargeEnergy.rideEnergy(steady(3600, 100f, -1f))
        assertEquals(100f, e.outWh, 0.5f)
        assertEquals(100f, e.netWh, 0.5f)
    }

    @Test
    fun `regen is counted apart from consumption`() {
        // Half an hour drawing 100 W, then a descent giving 50 W back.
        val out = (0..1800).map { Triple(it * 1000L, 100f, 1f) }
        val back = (1801..3600).map { Triple(it * 1000L, 100f, -0.5f) }
        val e = ChargeEnergy.rideEnergy(out + back)
        assertEquals(50f, e.outWh, 1f)
        assertEquals(25f, e.regenWh, 1f)
        assertEquals(25f, e.netWh, 1f)
    }

    @Test
    fun `rows with no reading are skipped, not read as zero power`() {
        // A CSV from a wheel that reports no current leaves NaN. Integrating
        // those as 0 W would quietly report a free ride.
        val blank = (0..3600).map { Triple(it * 1000L, 100f, Float.NaN) }
        assertEquals(0f, ChargeEnergy.rideEnergy(blank).netWh, 0.01f)

        val half = steady(1800, 100f, 1f) +
            (1801..3600).map { Triple(it * 1000L, Float.NaN, Float.NaN) }
        assertEquals(50f, ChargeEnergy.rideEnergy(half).netWh, 1f)
    }

    @Test
    fun `a trip too short to say anything returns zero`() {
        assertEquals(0f, ChargeEnergy.rideEnergy(emptyList()).netWh, 0.01f)
        assertEquals(0f, ChargeEnergy.rideEnergy(listOf(Triple(0L, 100f, 1f))).netWh, 0.01f)
    }

    @Test
    fun `a gap longer than the cap is not integrated across`() {
        // A paused recording or a clock jump must not bill the rider for the
        // hours it was not riding. Same cap as the live path.
        val beforeGap = steady(60, 100f, 1f)
        val afterGap = listOf(
            Triple(60_000L + ChargeEnergy.MAX_GAP_MS + 60_000L, 100f, 1f),
            Triple(60_000L + ChargeEnergy.MAX_GAP_MS + 61_000L, 100f, 1f),
        )
        val e = ChargeEnergy.rideEnergy(beforeGap + afterGap)
        // 61 s of riding either side of the gap, nothing across it.
        assertEquals(100f * 61f / 3600f, e.netWh, 0.2f)
    }
}
