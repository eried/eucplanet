package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.TripMeterSplit
import com.eried.eucplanet.data.model.TripMeterState
import com.eried.eucplanet.service.ChargingEstimate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetAvailabilityTest {
    private fun s(v: Float) = MetricSample(1_000L, v)
    private val zeros = listOf(s(0f), s(0f), s(0f))

    @Test fun `empty and all-zero buffers have nothing to clear`() {
        assertFalse(emptyList<MetricSample>().hasNonZeroSample())
        assertFalse(zeros.hasNonZeroSample())
        assertFalse(listOf(s(Float.NaN), s(0f)).hasNonZeroSample())
        assertTrue((zeros + s(0.1f)).hasNonZeroSample())
        assertTrue(listOf(s(-3f)).hasNonZeroSample())
    }

    @Test fun `samplesFor reads the buffer resetHistory clears`() {
        val h = FullMetricHistory(
            battery = listOf(s(1f)), temperature = listOf(s(2f)), voltage = listOf(s(3f)),
            current = listOf(s(4f)), load = listOf(s(5f)), speed = listOf(s(6f)),
            extras = mapOf("PITCH" to listOf(s(7f))),
        )
        val expected = mapOf(
            "BATTERY" to 1f, "TEMPERATURE" to 2f, "VOLTAGE" to 3f, "CURRENT" to 4f,
            "LOAD" to 5f, "SPEED" to 6f, "PITCH" to 7f,
        )
        for ((key, v) in expected) {
            assertTrue(key, h.samplesFor(key).single().value == v)
        }
        assertTrue(h.samplesFor("MOTOR_TEMP").isEmpty())
    }

    @Test fun `metric detail tap and long press follow the active and other buffers`() {
        val fresh = FullMetricHistory()
        assertFalse(fresh.hasDataFor("SPEED"))
        assertFalse(fresh.hasAnyData())

        // Parked: speed is all zero, voltage is not. Tap on Speed is off, Reset all is on.
        val parked = FullMetricHistory(speed = zeros, voltage = listOf(s(84f)))
        assertFalse(parked.hasDataFor("SPEED"))
        assertTrue(parked.hasDataFor("VOLTAGE"))
        assertTrue(parked.hasAnyData())

        // An extras buffer alone also keeps Reset all reachable.
        val extraOnly = FullMetricHistory(extras = mapOf("PITCH" to zeros, "ROLL" to listOf(s(2f))))
        assertFalse(extraOnly.hasDataFor("PITCH"))
        assertTrue(extraOnly.hasDataFor("ROLL"))
        assertTrue(extraOnly.hasAnyData())
        assertFalse(FullMetricHistory(extras = mapOf("PITCH" to zeros)).hasAnyData())
    }

    @Test fun `trip meter has data once it shows distance, time or a split`() {
        assertFalse(TripMeterState().hasData())
        assertTrue(TripMeterState(distanceKm = 0.01f).hasData())
        assertTrue(TripMeterState(activeMs = 500L).hasData())
        val split = TripMeterSplit(1, 10f, 1L, 1L, 1f, 1f, -1)
        assertTrue(TripMeterState(splits = listOf(split)).hasData())
        // The start stamp alone is not on screen.
        assertFalse(TripMeterState(startedAtMs = 123L).hasData())
    }

    @Test fun `charging session data covers every field reset clears that is shown`() {
        assertFalse(ChargingSnapshot().hasSessionData())
        assertFalse(ChargingSnapshot(packSeriesUnit = "V", packMinHistory = listOf(emptyList())).hasSessionData())
        val cases = listOf(
            ChargingSnapshot(chargeHistory = listOf(s(50f))),
            ChargingSnapshot(voltageHistory = listOf(s(84f))),
            ChargingSnapshot(tempHistory = listOf(s(30f))),
            ChargingSnapshot(predictionHistory = listOf(PredictionSample(1L, 2L, 3L))),
            ChargingSnapshot(packMinHistory = listOf(emptyList(), listOf(s(1f)))),
            ChargingSnapshot(packMaxHistory = listOf(listOf(s(1f)))),
            ChargingSnapshot(packAvgHistory = listOf(listOf(s(1f)))),
            ChargingSnapshot(targetEtaMs = 1L),
            ChargingSnapshot(fullEtaMs = 1L),
            ChargingSnapshot(sessionEnergyWh = -1f),
            ChargingSnapshot(sessionEnergyInWh = 1f),
            ChargingSnapshot(sessionEnergyOutWh = 1f),
            ChargingSnapshot(estimatedChargedWh = 1f),
            ChargingSnapshot(estimate = ChargingEstimate(addedPercent = 2f)),
        )
        cases.forEach { assertTrue(it.toString(), it.hasSessionData()) }
    }

    @Test fun `reset metrics is live when any of its targets holds something`() {
        val none = metricsResetAvailable(TripMeterState(), FullMetricHistory(), 0f, false)
        assertFalse(none)
        assertFalse(metricsResetAvailable(TripMeterState(), FullMetricHistory(speed = zeros), Float.NaN, false))
        assertTrue(metricsResetAvailable(TripMeterState(distanceKm = 1f), FullMetricHistory(), 0f, false))
        assertTrue(metricsResetAvailable(TripMeterState(), FullMetricHistory(battery = listOf(s(90f))), 0f, false))
        assertTrue(metricsResetAvailable(TripMeterState(), FullMetricHistory(), 12f, false))
        assertTrue(metricsResetAvailable(TripMeterState(), FullMetricHistory(), 0f, true))
    }
}
