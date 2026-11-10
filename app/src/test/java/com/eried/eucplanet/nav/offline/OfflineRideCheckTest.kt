package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineRideCheckTest {

    // Oslo to Gothenburg crosses E10_N55 (Oslo) and E10_N55 again, then
    // Gothenburg at 11.97 E 57.7 N is still E10_N55: one square.
    private val oslo = GeoPoint(59.91, 10.75)
    private val gothenburg = GeoPoint(57.70, 11.97)
    // Stockholm at 18.07 E is in E15_N55.
    private val stockholm = GeoPoint(59.33, 18.07)

    @Test fun everySquareOnThePhone_nothingMissing() {
        assertEquals(emptyList<String>(), OfflineRideCheck.missing(listOf(oslo, gothenburg), has = { true }, served = null))
    }

    @Test fun aSquareNotOnThePhone_isMissing() {
        val m = OfflineRideCheck.missing(listOf(oslo, stockholm), has = { it == "E10_N55" }, served = null)
        assertEquals(listOf("E15_N55"), m)
    }

    @Test fun aSquareTheServerDoesNotHave_isNotAsked() {
        // Open sea has no square on the server: nothing to download there.
        val m = OfflineRideCheck.missing(listOf(oslo, stockholm), has = { false }, served = setOf("E10_N55"))
        assertEquals(listOf("E10_N55"), m)
    }

    @Test fun fewerThanTwoPoints_nothingMissing() {
        assertEquals(emptyList<String>(), OfflineRideCheck.missing(listOf(oslo), has = { false }, served = null))
    }
}
