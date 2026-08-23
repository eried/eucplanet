package com.eried.eucplanet.ble

import com.eried.eucplanet.ble.virtual.InMotionV1VirtualWheel
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.ChargeEnergy
import com.eried.eucplanet.data.repository.ChargeRiseDetector
import com.eried.eucplanet.data.repository.RideEfficiencyTracker
import com.eried.eucplanet.service.ChargingEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole V8S session, end to end: the simulator emits real InMotion V1 wire
 * bytes, [InMotionV1Adapter] and [InMotionV1Parser] decode them, and the
 * decoded telemetry drives the same pieces the repository drives.
 *
 * Both of the bugs this covers only appear over time, which is why the fixture
 * runs the full script rather than a handful of frames: forty minutes of city
 * riding with regen on every brake, a park, then two hours on a charger that
 * the wheel never reports a current for.
 */
class InMotionV1SimulationTest {

    private companion object {
        const val TICK_MS = 1000L
        const val PACK_WH = 1000
        const val WINDOW_MS = 300_000L
        const val RIDE_END_S = 40 * 60
        const val CHARGE_START_S = 42 * 60
        /** One pass of the simulator's script, which is 162 minutes long. */
        const val SCRIPT_S = 161 * 60
    }

    /** Everything one replay of the script produced. */
    private class Report {
        var model: String? = null
        var firmware: String? = null
        var maxSpeedKmh = 0f
        var frames = 0

        var rideOutWh = 0f
        var rideRegenWh = 0f
        /** Energy filed while the wheel was on the charger. */
        var chargeOutWh = 0f
        var chargeInWh = 0f

        var chargingTicks = 0
        var minChargePercent = 100
        var maxChargePercent = 0
        var chargeCurrentWentNegative = false
        var addedPercent = 0f

        var startOdoKm = 0f
        var endOdoKm = 0f
        var rideBlanks = 0
        var rideReadings = 0
        var lastWhPerKm = Float.NaN
        var lastRangeKm = Float.NaN
    }

    /**
     * Replay the script. [measuresChargeCurrent] is the capability under test:
     * false is what an InMotion V1 really is, true reproduces what the app did
     * before it knew that.
     */
    private fun replay(measuresChargeCurrent: Boolean): Report {
        var now = 1_000_000_000L
        val wheel = InMotionV1VirtualWheel(clockMs = { now })
        val adapter = InMotionV1Adapter()
        adapter.notifyConnectingTo(wheel.bleName)
        wheel.reset()

        val report = Report()
        val tracker = RideEfficiencyTracker()
        val riseDetector = ChargeRiseDetector()
        val estimator = ChargingEstimator()

        // The current-based half of the repository's charge inference.
        var chargeInferred = false
        var negSamples = 0
        var posSamples = 0

        var outWh = 0f
        var inWh = 0f
        var lastPowerW = 0f
        var primed = false

        // Settings arrive on the slow-info poll, exactly as on a real connect.
        for (raw in wheel.onWrite(InMotionV1Commands.getSlowInfo())) {
            for (result in adapter.onRawNotification(raw)) {
                when (result) {
                    is DecodeResult.ModelName -> report.model = result.name
                    is DecodeResult.Firmware -> report.firmware = result.mainBoard
                    is DecodeResult.Settings -> report.maxSpeedKmh = result.data.maxSpeedKmh
                    else -> {}
                }
            }
        }

        for (second in 0 until SCRIPT_S) {
            now += TICK_MS
            val data: WheelData = wheel.onWrite(InMotionV1Commands.getFastInfo())
                .flatMap { adapter.onRawNotification(it) }
                .filterIsInstance<DecodeResult.Telemetry>()
                .lastOrNull()?.data ?: continue
            report.frames++
            if (report.startOdoKm == 0f) report.startOdoKm = data.totalDistance
            report.endOdoKm = data.totalDistance

            // --- charge status, as deriveChargeStatus works it out ---
            when {
                data.current < -0.3f -> {
                    negSamples++; posSamples = 0
                    if (negSamples >= 5) chargeInferred = true
                }
                data.current > -0.05f -> {
                    posSamples++; negSamples = 0
                    if (posSamples >= 10) chargeInferred = false
                }
            }
            val moving = data.speed > 1f
            val rising = riseDetector.update(
                nowMs = now,
                percent = data.batteryPercent.toFloat(),
                applicable = !moving && !data.charging,   // V1 has no charge flag
            )
            val charging = !moving && (chargeInferred || rising)

            // --- session energy, as updateChargingSession integrates it ---
            val powerW = data.voltage * data.current
            val inc = ChargeEnergy.stepWh(
                prevPowerW = if (primed) lastPowerW else powerW,
                nowPowerW = powerW,
                dtMs = if (primed) TICK_MS else 0L,
                dischargeIsPositivePower = true,
                charging = charging,
                measuresChargeCurrent = measuresChargeCurrent,
            )
            primed = true
            lastPowerW = powerW
            if (inc >= 0f) inWh += inc else outWh -= inc

            estimator.addSample(now, data.batteryPercent.toFloat())

            if (second < RIDE_END_S) {
                report.rideOutWh = outWh
                report.rideRegenWh = inWh
                val estimate = tracker.sample(
                    nowMs = now,
                    whConsumed = outWh,
                    whRegen = inWh,
                    sourceKm = data.totalDistance,
                    batteryPercent = data.batteryPercent,
                    windowMs = WINDOW_MS,
                    packCapacityWh = PACK_WH,
                )
                report.lastWhPerKm = estimate.whPerKm
                report.lastRangeKm = estimate.rangeKm
                if (estimate.whPerKm.isNaN()) report.rideBlanks++ else report.rideReadings++
            } else if (second > CHARGE_START_S) {
                if (charging) report.chargingTicks++
                if (data.current < 0f) report.chargeCurrentWentNegative = true
                report.minChargePercent = minOf(report.minChargePercent, data.batteryPercent)
                report.maxChargePercent = maxOf(report.maxChargePercent, data.batteryPercent)
                report.chargeOutWh = outWh - report.rideOutWh
                report.chargeInWh = inWh - report.rideRegenWh
            }
        }
        report.addedPercent = estimator.estimate().let { it.percent - it.startPercent }
        return report
    }

    @Test
    fun `the simulator speaks the wire format the parser expects`() {
        val report = replay(measuresChargeCurrent = false)
        assertEquals("InMotion V8S", report.model)
        assertEquals("1.2.22", report.firmware)
        assertEquals(45f, report.maxSpeedKmh, 0.01f)
        assertEquals(SCRIPT_S, report.frames)
        // Forty minutes of the cycle averages a bit under 20 km/h.
        assertEquals(13f, report.endOdoKm - report.startOdoKm, 3f)
    }

    @Test
    fun `the tiles answer through a ride full of braking`() {
        val report = replay(measuresChargeCurrent = false)
        val covered = report.rideReadings.toFloat() / (report.rideReadings + report.rideBlanks)
        assertTrue("CONSUMPTION was blank ${(1 - covered) * 100}% of the ride", covered > 0.9f)
        assertTrue("regen has to be in the trace or this proves nothing",
            report.rideRegenWh > 5f)

        val trueWhPerKm = (report.rideOutWh - report.rideRegenWh) /
            (report.endOdoKm - report.startOdoKm)
        assertEquals(trueWhPerKm, report.lastWhPerKm, trueWhPerKm * 0.2f)
        assertTrue("RANGE was blank at the end of the ride", !report.lastRangeKm.isNaN())
    }

    @Test
    fun `a charge with no charge current is still seen as a charge`() {
        val report = replay(measuresChargeCurrent = false)
        assertTrue("the wheel must not be reporting a charge current",
            !report.chargeCurrentWentNegative)
        assertTrue("the charge was never detected", report.chargingTicks > 3600)
        assertTrue("the pack has to climb for this to be the case",
            report.maxChargePercent - report.minChargePercent > 20)
    }

    @Test
    fun `the wheel's idle draw is not filed as charge energy`() {
        // What the rider saw: "+54.0 %" added and "Used 4 Wh" under it, which was
        // the board's own standby draw integrated across the whole charge.
        val wrong = replay(measuresChargeCurrent = true)
        assertTrue("this is the bug being reproduced: ${wrong.chargeInWh + wrong.chargeOutWh} Wh",
            wrong.chargeInWh + wrong.chargeOutWh > 3f)

        // Nothing but the few minutes before the climb is confirmed as a charge.
        val fixed = replay(measuresChargeCurrent = false)
        assertTrue("still filing the idle draw: ${fixed.chargeInWh + fixed.chargeOutWh} Wh",
            fixed.chargeInWh + fixed.chargeOutWh < 0.3f)
    }

    @Test
    fun `charged energy comes from the percentage instead`() {
        val report = replay(measuresChargeCurrent = false)
        val estimated = ChargeEnergy.chargedWhFromPercent(report.addedPercent, PACK_WH)
        // Forty minutes of riding takes about 200 Wh out of a 1000 Wh pack, and
        // the charge puts it back.
        assertTrue("nothing to show: added ${report.addedPercent} %", estimated > 150f)
        assertEquals(report.addedPercent / 100f * PACK_WH, estimated, 0.01f)
    }
}
