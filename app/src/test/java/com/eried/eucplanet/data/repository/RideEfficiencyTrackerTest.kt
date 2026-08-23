package com.eried.eucplanet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rolling window behind the CONSUMPTION and RANGE tiles, replayed against a
 * simulated InMotion V8S ride.
 *
 * The ride is the point. A steady bench trace would pass against the version
 * this replaces; what broke the tiles on a real V8S was ordinary braking, which
 * only shows up when the trace has regen in it several times a minute.
 */
class RideEfficiencyTrackerTest {

    private companion object {
        const val WINDOW_MS = 300_000L      // the 5-minute dashboard default
        const val PACK_WH = 1000            // rider-entered pack size
        const val VOLTS = 78f
    }

    /**
     * A ride, integrated the way the repository does it: energy through
     * [ChargeEnergy.stepWh] on the InMotion V1 sign convention, then fed to the
     * tracker once a second along with the odometer.
     */
    private class Ride(private val tracker: RideEfficiencyTracker = RideEfficiencyTracker()) {
        var tMs = 1_000_000_000L
        var odoKm = 2517f
        var outWh = 0f
        var regenWh = 0f
        var pct = 90
        private var lastPowerW = 0f
        private var primed = false

        var blanks = 0
        var readings = 0
        val values = mutableListOf<Float>()
        var lastEstimate = RideEfficiencyTracker.Estimate()

        /** Rider-entered pack size; 0 means they never entered one. */
        var capacityWh = PACK_WH

        /** Run [seconds] at a speed and current, sampling at 1 Hz. */
        fun run(seconds: Int, speedKmh: Float, amps: Float) {
            repeat(seconds) {
                val powerW = VOLTS * amps
                val inc = ChargeEnergy.stepWh(
                    prevPowerW = if (primed) lastPowerW else powerW,
                    nowPowerW = powerW,
                    dtMs = if (primed) 1000L else 0L,
                    dischargeIsPositivePower = true,
                )
                primed = true
                lastPowerW = powerW
                if (inc >= 0f) regenWh += inc else outWh -= inc

                odoKm += speedKmh / 3600f
                tMs += 1000L
                lastEstimate = tracker.sample(
                    nowMs = tMs,
                    whConsumed = outWh,
                    whRegen = regenWh,
                    sourceKm = odoKm,
                    batteryPercent = pct,
                    windowMs = WINDOW_MS,
                    packCapacityWh = capacityWh,
                )
                if (lastEstimate.whPerKm.isNaN()) blanks++
                else { readings++; values += lastEstimate.whPerKm }
            }
        }

        /** One city cycle: accelerate, cruise, brake into the pack, stop. */
        fun cycle() {
            run(8, 12.5f, 14f)
            run(48, 25f, 4.2f)
            run(8, 12.5f, -5f)
            run(8, 0f, 0.02f)
        }

        /** Wheel connected and standing still, drawing only its own idle current. */
        fun park(seconds: Int) = run(seconds, 0f, 0.02f)

        /** Everything the wheel has spent, over everything it has covered. */
        fun trueWhPerKm(startKm: Float) = (outWh - regenWh) / (odoKm - startKm)

        fun coverage() = readings.toFloat() / (readings + blanks)
    }

    @Test
    fun `braking does not blank the tiles for the rest of the ride`() {
        // Regen makes net energy fall, which is not a session restarting. The
        // version this replaces read that fall as a reconnect and cleared the
        // whole window on every brake, so a rider saw a number for a few seconds
        // per cycle and blanks the rest of the time.
        val ride = Ride()
        val startKm = ride.odoKm
        repeat(30) { ride.cycle() }   // 36 minutes of city riding

        assertTrue(
            "tiles were blank ${(1 - ride.coverage()) * 100}% of the ride",
            ride.coverage() > 0.9f,
        )
        assertEquals(ride.trueWhPerKm(startKm), ride.values.average().toFloat(), 2f)
    }

    @Test
    fun `the rate is the ride's own, not a fraction of it`() {
        // Every wiped window restarted the count from wherever the brake left
        // off, so the surviving stretches were whatever a couple of hundred
        // metres happened to cost: single digits on an easy stretch against a
        // real 15 Wh per km.
        val ride = Ride()
        val startKm = ride.odoKm
        repeat(20) { ride.cycle() }

        val expected = ride.trueWhPerKm(startKm)
        assertEquals(expected, ride.lastEstimate.whPerKm, expected * 0.15f)
    }

    @Test
    fun `the rate survives the end of a trip`() {
        // A rider looks at the dashboard once they are off the wheel. A window
        // that only ages by time covers no distance by then and blanks both
        // tiles, which is what "empty after a trip" was.
        val ride = Ride()
        repeat(10) { ride.cycle() }
        val riding = ride.lastEstimate

        ride.park(10 * 60)   // ten minutes parked, still connected

        assertTrue("blank after the trip", !ride.lastEstimate.whPerKm.isNaN())
        assertTrue("blank after the trip", !ride.lastEstimate.rangeKm.isNaN())
        assertEquals(riding.whPerKm, ride.lastEstimate.whPerKm, 2f)
    }

    @Test
    fun `a wheel left parked eventually goes quiet`() {
        // The hold is for a stop, not for a wheel left on the charger overnight.
        val ride = Ride()
        repeat(10) { ride.cycle() }
        ride.park(35 * 60)

        assertTrue(ride.lastEstimate.whPerKm.isNaN())
        assertTrue(ride.lastEstimate.rangeKm.isNaN())
    }

    @Test
    fun `a reconnect starts the window over`() {
        val tracker = RideEfficiencyTracker()
        var t = 1_000L
        // A session's worth of riding: 30 Wh over 2 km.
        repeat(200) {
            t += 1000L
            tracker.sample(t, whConsumed = it * 0.15f, whRegen = 0f, sourceKm = 100f + it * 0.01f,
                batteryPercent = 80, windowMs = WINDOW_MS, packCapacityWh = PACK_WH)
        }
        // The link drops and comes back: session energy restarts at zero while
        // the wheel's odometer carries on from where it was.
        val after = tracker.sample(t + 1000L, whConsumed = 0f, whRegen = 0f, sourceKm = 102f,
            batteryPercent = 80, windowMs = WINDOW_MS, packCapacityWh = PACK_WH)

        assertTrue("a restart must not be read as a 2 km stretch on no energy",
            after.whPerKm.isNaN())
    }

    @Test
    fun `no answer before the window covers any ground`() {
        val ride = Ride()
        ride.run(20, 25f, 4.2f)   // about 140 m
        assertTrue(ride.lastEstimate.whPerKm.isNaN())
        assertTrue(ride.lastEstimate.rangeKm.isNaN())
    }

    @Test
    fun `range needs a pack size until the ride has taught one`() {
        // Consumption stands on its own; range also needs energy per percent,
        // which is the rider's pack size until the pack has dropped enough for
        // the ride to have measured its own.
        val blind = Ride()
        blind.capacityWh = 0
        repeat(5) { blind.cycle() }
        assertTrue(!blind.lastEstimate.whPerKm.isNaN())
        assertTrue(blind.lastEstimate.rangeKm.isNaN())

        val seeded = Ride()
        repeat(5) { seeded.cycle() }
        assertTrue(!seeded.lastEstimate.rangeKm.isNaN())
    }

    @Test
    fun `an hour of riding keeps answering`() {
        // The long-running case: an hour of mixed riding, then a stop, then more.
        val ride = Ride()
        val startKm = ride.odoKm
        repeat(50) { ride.cycle() }
        ride.park(3 * 60)
        repeat(20) { ride.cycle() }

        assertTrue("coverage ${ride.coverage()}", ride.coverage() > 0.9f)
        val expected = ride.trueWhPerKm(startKm)
        assertEquals(expected, ride.values.average().toFloat(), expected * 0.25f)
    }
}
