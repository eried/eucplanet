package com.eried.eucplanet.util

import com.eried.eucplanet.data.repository.parseTripQuads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Guards the trip-CSV column aliases (drift guard, per project rule 13). An
 * EUC World backup names its columns differently (gps_lat / gps_lon / datetime
 * / temp / gps_alt / distance_total / gps_speed), which is why such a file used
 * to import with lat/lon 0 ("no GPS"). These tests pin that (a) EUC World rows
 * resolve GPS via the aliases and (b) our own EUC Planet format still resolves
 * to the exact same indices. Change TripCsv.Columns and this test together.
 */
class TripCsvColumnsTest {

    private fun header(line: String): List<String> =
        line.split(',').map { it.trim().lowercase(Locale.US) }

    /** The real EUC Planet recorder header (see CsvWriter). */
    private val eucPlanet = header(
        "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude," +
            "Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y"
    )

    /** The real EUC World (euc.world) CSV backup header. */
    private val eucWorld = header(
        "datetime,duration,duration_riding,distance,distance_total,speed,speed_avg," +
            "speed_avg_riding,speed_max,speed_limit,voltage,current,current_phase,power," +
            "battery,temp,temp_motor,temp_batt,safety_margin,cpu_load,tilt,roll,fan,alert," +
            "alarm,gps_datetime,gps_duration,gps_duration_riding,gps_distance,gps_lat,gps_lon," +
            "gps_speed,gps_speed_avg,gps_speed_avg_riding,gps_speed_max,gps_alt,gps_bearing," +
            "gps_acc,hr,extra"
    )

    @Test
    fun eucPlanetColumnsUnchanged() {
        val c = TripCsv.Columns
        assertEquals(0, c.date(eucPlanet))
        assertEquals(1, c.speed(eucPlanet))
        assertEquals(2, c.voltage(eucPlanet))
        assertEquals(3, c.temperature(eucPlanet))
        assertEquals(4, c.battery(eucPlanet))
        assertEquals(5, c.altitude(eucPlanet))
        assertEquals(6, c.latitude(eucPlanet))
        assertEquals(7, c.longitude(eucPlanet))
        assertEquals(8, c.mileage(eucPlanet))
        assertEquals(9, c.gpsSpeed(eucPlanet))
        assertEquals(10, c.current(eucPlanet))
        assertEquals(11, c.pwm(eucPlanet))
    }

    @Test
    fun eucWorldColumnsResolveViaAliases() {
        val c = TripCsv.Columns
        assertEquals(0, c.date(eucWorld))         // datetime
        assertEquals(5, c.speed(eucWorld))
        assertEquals(10, c.voltage(eucWorld))
        assertEquals(11, c.current(eucWorld))
        assertEquals(15, c.temperature(eucWorld)) // temp
        assertEquals(14, c.battery(eucWorld))
        assertEquals(35, c.altitude(eucWorld))    // gps_alt
        assertEquals(29, c.latitude(eucWorld))    // gps_lat
        assertEquals(30, c.longitude(eucWorld))   // gps_lon
        assertEquals(31, c.gpsSpeed(eucWorld))    // gps_speed
        assertEquals(4, c.mileage(eucWorld))      // distance_total (odometer)
        assertEquals(-1, c.pwm(eucWorld))         // absent in EUC World
    }

    @Test
    fun eucWorldTripHasGps() {
        val csv = (
            "datetime,duration,duration_riding,distance,distance_total,speed,speed_avg," +
                "speed_avg_riding,speed_max,speed_limit,voltage,current,current_phase,power," +
                "battery,temp,temp_motor,temp_batt,safety_margin,cpu_load,tilt,roll,fan,alert," +
                "alarm,gps_datetime,gps_duration,gps_duration_riding,gps_distance,gps_lat,gps_lon," +
                "gps_speed,gps_speed_avg,gps_speed_avg_riding,gps_speed_max,gps_alt,gps_bearing," +
                "gps_acc,hr,extra\n" +
                "2023-05-19T15:56:24.869+0200,0,0,0.000,1441.403,0.00,0.00,0.00,0.00,30.00,66.50," +
                "-0.09,,-6,95,30,,,99,80,,,0,,0,2023-05-19T15:56:24.000+0200,0,0,0.000,55.3195459," +
                "11.9658043,0.00,0.00,0.00,0.00,48.3,,9,,manufacturer=Google\n" +
                "2023-05-19T15:56:25.032+0200,0,0,0.000,1441.403,0.00,0.00,0.00,0.00,30.00,66.49," +
                "-0.09,,-6,95,30,,,99,80,,,0,,0,2023-05-19T15:56:24.000+0200,0,0,0.000,55.3195460," +
                "11.9658050,0.00,0.00,0.00,0.00,48.3,,9,,brand=google"
            )
        val quads = parseTripQuads(csv)
        assertTrue("expected rows to parse", quads.isNotEmpty())
        val first = quads.first()
        assertEquals(55.3195459, first.lat, 1e-6)
        assertEquals(11.9658043, first.lon, 1e-6)
        assertNotNull("EUC World timestamp should parse", TripCsv.parseDate(first.date))
    }
}
