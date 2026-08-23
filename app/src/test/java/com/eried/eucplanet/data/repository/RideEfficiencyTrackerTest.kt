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
        /** Pack state of charge, in percent of [PACK_WH]. */
        var socPct = 92f
        /**
         * What the wheel reports. On the InMotion V1 family that is not a state
         * of charge at all: the app works it out from pack voltage, which sags
         * under load and comes back at a standstill. A 14 A pull moves it about
         * seven points on a V8S.
         */
        var reportsSaggingPercent = true
        var charging = false
        private var lastPowerW = 0f
        private var primed = false

        var blanks = 0
        var readings = 0
        val values = mutableListOf<Float>()
        /** Wh the estimator thinks is left in the pack: range x consumption,
         *  which strips the consumption rate back out of the range. */
        val packWhLeft = mutableListOf<Float>()
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

                socPct = (socPct - powerW / 3600f / PACK_WH * 100f).coerceIn(0f, 100f)
                odoKm += speedKmh / 3600f
                tMs += 1000L
                // 0.08 ohm of pack resistance over a 14.5 V percent scale.
                val sagPct = if (reportsSaggingPercent) amps * 0.08f / 14.5f * 100f else 0f
                lastEstimate = tracker.sample(
                    nowMs = tMs,
                    whConsumed = outWh,
                    whRegen = regenWh,
                    sourceKm = odoKm,
                    batteryPercent = (socPct - sagPct).toInt().coerceIn(0, 100),
                    windowMs = WINDOW_MS,
                    packCapacityWh = capacityWh,
                    charging = charging,
                )
                if (lastEstimate.whPerKm.isNaN()) blanks++
                else { readings++; values += lastEstimate.whPerKm }
                if (!lastEstimate.rangeKm.isNaN()) {
                    packWhLeft += lastEstimate.rangeKm * lastEstimate.whPerKm
                }
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
        blind.reportsSaggingPercent = false   // a wheel with a steady percentage
        repeat(2) { blind.cycle() }           // barely a percent off the pack
        assertTrue(!blind.lastEstimate.whPerKm.isNaN())
        assertTrue(blind.lastEstimate.rangeKm.isNaN())

        val seeded = Ride()
        seeded.reportsSaggingPercent = false
        repeat(2) { seeded.cycle() }
        assertTrue(!seeded.lastEstimate.rangeKm.isNaN())
    }

    @Test
    fun `an early range is the pack the rider entered`() {
        // Five minutes in, the ride has not drained enough for its own rate to
        // mean anything, and the pack size the rider entered is the better
        // answer. It read a fraction of that while a sag-sized drop was being
        // taken for real discharge.
        val ride = Ride()
        repeat(4) { ride.cycle() }

        val honest = PACK_WH * ride.socPct / 100f / ride.lastEstimate.whPerKm
        assertEquals(honest, ride.lastEstimate.rangeKm, honest * 0.25f)
    }

    @Test
    fun `range does not swing with the throttle`() {
        // The percentage this family reports drops several points the moment the
        // rider accelerates, so a range read straight off it lurched between a
        // third and the whole of the real figure, several times a minute.
        fun swingOverThreeCycles(sagging: Boolean): Float {
            val ride = Ride()
            ride.reportsSaggingPercent = sagging
            repeat(10) { ride.cycle() }
            val settled = ride.packWhLeft.takeLast(72 * 3)
            return settled.max() / settled.min()
        }

        // Against a wheel whose percentage holds steady, which is the best this
        // can be: what is left is the percentage arriving in whole numbers, and
        // the step it takes each time it drops one.
        val steady = swingOverThreeCycles(sagging = false)
        val sagging = swingOverThreeCycles(sagging = true)
        assertTrue(
            "sag adds $sagging against $steady on a steady wheel",
            sagging <= steady * 1.1f,
        )
    }

    @Test
    fun `range is the whole pack, not one sag in the percentage`() {
        // The V1 family works its percentage out from pack voltage, so it drops
        // several points the moment the rider accelerates and comes back when
        // they stop. Rebasing on every rebound measured a few seconds of energy
        // against a sag-sized drop, and RANGE read a fraction of the truth.
        val ride = Ride()
        ride.capacityWh = 0   // no rider-entered pack size, so this is the learned rate
        repeat(20) { ride.cycle() }

        val whPerKm = ride.lastEstimate.whPerKm
        val pct = ride.socPct
        // The pack really holds PACK_WH, so what is left is pct percent of it.
        val honest = PACK_WH * pct / 100f / whPerKm
        assertEquals(honest, ride.lastEstimate.rangeKm, honest * 0.35f)
    }

    @Test
    fun `a brake at a red light does not count as a charge`() {
        // The current-based half of charge detection latches on the regen of a
        // hard brake and lets go a couple of seconds after the wheel stops, so
        // every stop reads as a charge for a moment. Moving the pack baseline
        // there restarts the measurement every minute or so, and RANGE goes back
        // to a fraction of the pack.
        val ride = Ride()
        ride.capacityWh = 0
        repeat(20) { ride.cycle() }
        val honest = PACK_WH * ride.socPct / 100f / ride.lastEstimate.whPerKm

        repeat(6) {
            ride.charging = true
            ride.park(3)          // the flicker at a stop
            ride.charging = false
            ride.run(60, 25f, 4.2f)
        }
        val after = PACK_WH * ride.socPct / 100f / ride.lastEstimate.whPerKm
        assertEquals(after, ride.lastEstimate.rangeKm, after * 0.4f)
        assertTrue("range collapsed after six stops", ride.lastEstimate.rangeKm > honest * 0.6f)
    }

    @Test
    fun `a charge rebases the pack baseline`() {
        // Ride the pack down, put it on a charger, ride again. Without moving the
        // baseline the second ride measures its energy against a percentage that
        // went up, and the learned rate is nonsense.
        val ride = Ride()
        ride.capacityWh = 0
        repeat(20) { ride.cycle() }

        ride.charging = true
        ride.socPct = 96f
        ride.park(180)
        ride.charging = false

        repeat(20) { ride.cycle() }
        val whPerKm = ride.lastEstimate.whPerKm
        val honest = PACK_WH * ride.socPct / 100f / whPerKm
        assertEquals(honest, ride.lastEstimate.rangeKm, honest * 0.4f)
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
