package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bulk trips generated for Dropbox pagination testing have to be readable by
 * the app's own CSV parser, or a tester uploads 2000 files and learns nothing.
 * This pins one generated file against the column resolver and the metric pass.
 */
class GeneratedTripCsvTest {

    private val header = ("Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude," +
        "Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Extra")
        .lowercase().split(",").map { it.trim() }

    private val rows = listOf(
        "01.01.2026 06:00:00.000,4.2,79.8,28.0,53,12.0,59.895510,10.743162,1000.0,4.2,1.5,6.7,1.000,0.000,0.010,",
        "01.01.2026 06:00:01.000,8.3,79.8,28.1,53,13.0,59.895531,10.743200,1000.2,8.3,2.9,13.3,1.010,0.010,0.009,",
        "01.01.2026 06:00:02.000,12.2,79.8,28.1,53,13.9,59.895562,10.743257,1000.5,12.2,4.3,19.5,1.017,0.017,0.005,",
    )

    @Test
    fun `every column the app looks for resolves`() {
        assertTrue("date", TripCsv.Columns.date(header) >= 0)
        assertTrue("speed", TripCsv.Columns.speed(header) >= 0)
        assertTrue("voltage", TripCsv.Columns.voltage(header) >= 0)
        assertTrue("current", TripCsv.Columns.current(header) >= 0)
        assertTrue("latitude", TripCsv.Columns.latitude(header) >= 0)
        assertTrue("longitude", TripCsv.Columns.longitude(header) >= 0)
        assertTrue("battery", TripCsv.Columns.battery(header) >= 0)
        assertTrue("mileage", TripCsv.Columns.mileage(header) >= 0)
    }

    @Test
    fun `the timestamps parse`() {
        for (row in rows) {
            val date = row.split(",")[TripCsv.Columns.date(header)]
            assertTrue("unparsed: $date", TripCsv.parseDate(date) != null)
        }
    }

    @Test
    fun `distance comes out of the mileage column`() {
        val iDate = TripCsv.Columns.date(header)
        val iLat = TripCsv.Columns.latitude(header)
        val iLon = TripCsv.Columns.longitude(header)
        val iMil = TripCsv.Columns.mileage(header)
        val quads = rows.map { r ->
            val c = r.split(",")
            TripCsv.Quad(c[iDate], c[iLat].toDouble(), c[iLon].toDouble(), c[iMil].toFloat())
        }
        val metrics = TripCsv.metricsFrom(quads)
        // 1000.0 -> 1000.5 across the rows.
        assertEquals(0.5f, metrics.distanceKm, 0.01f)
    }
}
