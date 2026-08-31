package com.eried.eucplanet.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packing that gets a forecast curve through SharedPreferences.
 *
 * It is written on every refresh and read on every launcher inflate, so the
 * interesting cases are the ones where it must not throw: an empty series, a
 * value the app never wrote, a preference file from an older build.
 */
class WeatherSnapshotTest {

    @Test fun `a series survives the round trip`() {
        val v = listOf(-4.2f, -0.5f, 0f, 1.7f, 4.9f)
        val back = WeatherSnapshot.unpackFloats(WeatherSnapshot.packFloats(v))
        assertEquals(v.size, back.size)
        v.forEachIndexed { i, f -> assertEquals(f, back[i], 0.05f) }
    }

    @Test fun `an empty series packs and unpacks to empty`() {
        assertEquals("", WeatherSnapshot.packFloats(emptyList()))
        assertTrue(WeatherSnapshot.unpackFloats("").isEmpty())
        assertTrue(WeatherSnapshot.unpackStrings("").isEmpty())
    }

    @Test fun `rubbish in the preference does not throw`() {
        // A half-written file, or one from a build that packed differently.
        assertTrue(WeatherSnapshot.unpackFloats("not a number").isEmpty())
        assertEquals(1, WeatherSnapshot.unpackFloats("1.5broken").size)
    }

    @Test fun `packing uses a fixed decimal separator`() {
        // Formatted with the default locale, a German phone would write "1,5"
        // and the reader would drop every point in the series.
        assertTrue(WeatherSnapshot.packFloats(listOf(1.5f)).contains("."))
    }

    @Test fun `a snapshot with no fetch has nothing to draw`() {
        assertFalse(WeatherSnapshot().hasData)
        // A fetch that produced no hours is still nothing to draw.
        assertFalse(WeatherSnapshot(fetchedAtMs = 1L).hasData)
        assertTrue(WeatherSnapshot(fetchedAtMs = 1L, series = listOf(1f, 2f)).hasData)
    }

    @Test fun `the signed score reads as an answer, not a measurement`() {
        assertEquals("+4", WeatherWidgetBase.signedScore(3.7f))
        assertEquals("0", WeatherWidgetBase.signedScore(0.2f))
        assertEquals("-3", WeatherWidgetBase.signedScore(-2.6f))
    }
}
