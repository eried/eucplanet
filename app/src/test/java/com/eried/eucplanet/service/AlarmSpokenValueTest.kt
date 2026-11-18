package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmMetric
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a voice alarm says for {value} and {threshold}.
 *
 * The engine and the rule editor's voice preview both speak
 * [spokenAlarmValue], so a rider hears in the preview exactly what the alarm
 * will say. The number has to be the reading in the rider's own unit: a tyre
 * rule is stored in kPa and g-force is a fraction, and spoken as rounded
 * stored values a 3 bar rule said "300" and a 1.5 g rule said "2".
 */
class AlarmSpokenValueTest {

    private fun spoken(
        metric: AlarmMetric,
        value: Float,
        speedUnit: String = "kmh",
        tempUnit: String = "C",
        pressureUnit: String = "bar",
    ) = spokenAlarmValue(metric.name, value, speedUnit, tempUnit, pressureUnit)

    @Test fun `a tyre alarm speaks the rider's pressure unit`() {
        // 300 kPa is how a 3 bar rule is stored.
        assertEquals("3.0", spoken(AlarmMetric.TIRE_PRESSURE, 300f, pressureUnit = "bar"))
        assertEquals("0.30", spoken(AlarmMetric.TIRE_PRESSURE, 300f, pressureUnit = "mpa"))
        assertEquals("3.1", spoken(AlarmMetric.TIRE_PRESSURE, 300f, pressureUnit = "kgf"))
        assertEquals("44", spoken(AlarmMetric.TIRE_PRESSURE, 300f, pressureUnit = "psi"))
        assertEquals("300", spoken(AlarmMetric.TIRE_PRESSURE, 300f, pressureUnit = "kpa"))
    }

    @Test fun `a live tyre reading keeps its tenths`() {
        // The value that tripped the rule, not only the threshold.
        assertEquals("2.4", spoken(AlarmMetric.TIRE_PRESSURE, 243f, pressureUnit = "bar"))
    }

    @Test fun `g-force keeps its tenths`() {
        assertEquals("1.5", spoken(AlarmMetric.G_FORCE, 1.5f))
        assertEquals("0.8", spoken(AlarmMetric.LATERAL_G, 0.8f))
    }

    @Test fun `speed reads as a whole number in the rider's unit`() {
        assertEquals("30", spoken(AlarmMetric.SPEED, 30f, speedUnit = "kmh"))
        assertEquals("19", spoken(AlarmMetric.SPEED, 30f, speedUnit = "mph"))
    }

    @Test fun `temperature reads as a whole number in the rider's unit`() {
        assertEquals("60", spoken(AlarmMetric.TEMPERATURE, 60f, tempUnit = "C"))
        assertEquals("140", spoken(AlarmMetric.TEMPERATURE, 60f, tempUnit = "F"))
    }

    @Test fun `gps speed and the single sensors convert like speed and temperature`() {
        assertEquals("31", spoken(AlarmMetric.GPS_SPEED, 50f, speedUnit = "mph"))
        assertEquals("31", spoken(AlarmMetric.EXTERNAL_GPS_SPEED, 50f, speedUnit = "mph"))
        for (sensor in listOf(AlarmMetric.MOTOR_TEMP, AlarmMetric.CONTROLLER_TEMP, AlarmMetric.BATTERY_TEMP)) {
            assertEquals("${sensor.name} in F", "140", spoken(sensor, 60f, tempUnit = "F"))
            assertEquals("${sensor.name} in C", "60", spoken(sensor, 60f, tempUnit = "C"))
        }
    }

    @Test fun `metrics with no unit choice are the rounded reading`() {
        assertEquals("20", spoken(AlarmMetric.BATTERY, 20f, speedUnit = "mph", tempUnit = "F", pressureUnit = "psi"))
        assertEquals("85", spoken(AlarmMetric.PWM, 85.4f))
        assertEquals("-85", spoken(AlarmMetric.BT_RSSI, -85f))
    }

    @Test fun `a metric the app no longer knows is still spoken`() {
        assertEquals("42", spokenAlarmValue("NO_SUCH_METRIC", 42.4f, "kmh", "C", "bar"))
    }
}
