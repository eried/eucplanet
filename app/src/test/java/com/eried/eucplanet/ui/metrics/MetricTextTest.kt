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
