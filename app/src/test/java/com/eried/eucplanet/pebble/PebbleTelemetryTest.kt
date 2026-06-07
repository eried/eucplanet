package com.eried.eucplanet.pebble

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin (no SDK) assembly of the Pebble telemetry dict from a
 * [WheelData] snapshot. Mirrors the Garmin/Wear protocol tests: build a
 * canonical-metric `Map<Int, Any>` so the watchapp converts to the rider's
 * units locally.
 */
class PebbleTelemetryTest {
    @Test
    fun buildsMetricTelemetryDict() {
        val data = WheelData(
            speed = 23.4f,
            batteryPercent = 77,
            voltage = 84.1f,
            current = 5.2f,
            pwm = 41f,
            maxTemperature = 30f
        )
        val s = AppSettings() // metric defaults
        val m = PebbleTelemetry.build(data, connected = true, settings = s)

        // Every live field a connected wheel reports must encode correctly —
        // this is the "sends real speed/battery/volts/amps/pwm/temp" guarantee.
        assertEquals(234, m[PebbleKeys.SPEED])      // speed * 10, int (km/h)
        assertEquals(77, m[PebbleKeys.BATTERY])     // percent
        assertEquals(841, m[PebbleKeys.VOLTAGE])    // volts * 10
        assertEquals(52, m[PebbleKeys.CURRENT])     // amps * 10
        assertEquals(41, m[PebbleKeys.PWM])         // percent
        assertEquals(300, m[PebbleKeys.TEMP])       // temp * 10, int (°C)
        assertEquals(1, m[PebbleKeys.CONNECTED])
        assertEquals("kmh", m[PebbleKeys.UNIT_SPEED])
        assertEquals("C", m[PebbleKeys.UNIT_TEMP])
    }

    @Test
    fun disconnectedZeroesLive() {
        val m = PebbleTelemetry.build(WheelData(), connected = false, settings = AppSettings())
        assertEquals(0, m[PebbleKeys.CONNECTED])
    }

    @Test
    fun imperialUnitsAreReportedAsCodes() {
        val s = AppSettings(unitSpeed = "mph", unitTemp = "F")
        val m = PebbleTelemetry.build(WheelData(speed = 10f), connected = true, settings = s)
        // Speed stays canonical metric on the wire; the watch converts.
        assertEquals(100, m[PebbleKeys.SPEED])
        assertEquals("mph", m[PebbleKeys.UNIT_SPEED])
        assertEquals("F", m[PebbleKeys.UNIT_TEMP])
    }
}
