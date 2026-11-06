package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.platform.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Both phones write the same trip CSV: the eucviewer and DarknessBot read it. */
class CsvWriterTest {

    private class Lines : TextSink {
        val lines = mutableListOf<String>()
        override fun open() = Unit
        override fun writeLine(line: String) { lines += line }
        override fun flush() = Unit
        override fun close() = Unit
    }

    @Test fun headerThenOneRowPerCall() {
        val sink = Lines()
        val w = CsvWriter(sink)
        w.open()
        val data = WheelData(
            speed = 23.456f, voltage = 84.1f, current = -3.25f, batteryPercent = 77, pwm = 41.5f,
            torque = 12.5f, phaseCurrent = 30f, maxTemperature = 41.25f, totalDistance = 1234.5678f,
            gForce = 1.1f, accelX = 0.25f, accelY = -0.5f, timestamp = System.currentTimeMillis(),
        )
        val fix = PhoneLocation(
            latitude = 59.9127, longitude = 10.7461, altitude = 23.45, speed = 6.5f,
            speedKnown = true, time = System.currentTimeMillis(),
        )
        w.writeRow(data, fix, extra = "wheel.name=V14, \"x\"")
        assertEquals(
            "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage," +
                "GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra",
            sink.lines[0],
        )
        val row = sink.lines[1]
        assertTrue(Regex("""^\d\d\.\d\d\.\d{4} \d\d:\d\d:\d\d\.\d{3},""").containsMatchIn(row), row)
        assertEquals(
            "23.46,84.10,41.25,77,23.5,59.912700,10.746100,1234.568,23.4,-3.25,41.50,1.100,0.250,-0.500,12.50,30.00,wheel.name=V14   x",
            row.substringAfter(','),
        )
        assertEquals(1, w.rows)
    }

    @Test fun withoutAWheelTheSpeedIsTheGps() {
        val sink = Lines()
        val w = CsvWriter(sink)
        w.open()
        val fix = PhoneLocation(latitude = 1.0, longitude = 2.0, speed = 10f, speedKnown = true)
        w.writeRow(WheelData(speed = 40f), fix, wheelConnected = false)
        assertEquals("36.00", sink.lines[1].split(',')[1])
    }
}
