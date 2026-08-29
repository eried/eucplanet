package com.eried.eucplanet.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The face registry, which the panel and the home screen widgets both read.
 *
 * The keys are persisted in the widget snapshot, so renaming one silently
 * turns every stored face into the fallback. That is what the drift guard here
 * is for.
 */
class WeatherFaceTest {

    private fun breakdown(
        score: Float = 0f,
        cold: Boolean = false,
        hot: Boolean = false,
        rain: Boolean = false,
        snow: Boolean = false,
        wind: Boolean = false,
        night: Boolean = false,
        golden: Boolean = false,
    ) = RidabilityScore.Breakdown(score, cold, hot, rain, snow, wind, night, golden)

    @Test fun `keys are unique, stable and non-blank`() {
        val keys = WeatherFace.entries.map { it.key }
        assertEquals("keys must be unique", keys.size, keys.toSet().size)
        assertTrue(keys.none { it.isBlank() })
        // Spelled out rather than derived: a rename shows up here as a failing
        // test rather than as widgets quietly falling back to a neutral face.
        assertEquals(
            listOf(
                "SNOW", "RAIN", "WIND", "COLD", "HOT",
                "GOLDEN", "NIGHT", "CLEAR", "MEH", "POOR",
            ),
            keys,
        )
    }

    @Test fun `every face carries an emoji and a line`() {
        WeatherFace.entries.forEach {
            assertTrue("${it.key} has no emoji", it.emoji.isNotBlank())
            assertTrue("${it.key} has no text", it.textRes != 0)
        }
    }

    @Test fun `an unknown key falls back rather than throwing`() {
        // A snapshot written by a newer build, or a corrupted preference.
        assertEquals(WeatherFace.MEH, WeatherFace.byKey("SOMETHING_ELSE"))
        assertEquals(WeatherFace.MEH, WeatherFace.byKey(""))
    }

    @Test fun `keys round trip`() {
        WeatherFace.entries.forEach { assertEquals(it, WeatherFace.byKey(it.key)) }
    }

    @Test fun `priority follows danger, not score`() {
        // Snow outranks everything, even on an otherwise lovely afternoon.
        assertEquals(WeatherFace.SNOW, WeatherFace.of(breakdown(score = 4f, snow = true)))
        assertEquals(WeatherFace.RAIN, WeatherFace.of(breakdown(score = 4f, rain = true)))
        // Rain outranks snow only if there is no snow.
        assertEquals(WeatherFace.SNOW, WeatherFace.of(breakdown(snow = true, rain = true)))
        // Wind only takes over once it is actually spoiling the ride.
        assertEquals(WeatherFace.WIND, WeatherFace.of(breakdown(score = -1f, wind = true)))
        assertEquals(WeatherFace.CLEAR, WeatherFace.of(breakdown(score = 3f, wind = true)))
    }

    @Test fun `with nothing biting the score picks the band`() {
        assertEquals(WeatherFace.CLEAR, WeatherFace.of(breakdown(score = 2f)))
        assertEquals(WeatherFace.MEH, WeatherFace.of(breakdown(score = 1.9f)))
        assertEquals(WeatherFace.MEH, WeatherFace.of(breakdown(score = -1f)))
        assertEquals(WeatherFace.POOR, WeatherFace.of(breakdown(score = -1.1f)))
    }

    @Test fun `night only reads as night when it is not a good night`() {
        assertEquals(WeatherFace.NIGHT, WeatherFace.of(breakdown(score = 1f, night = true)))
        assertEquals(WeatherFace.CLEAR, WeatherFace.of(breakdown(score = 3f, night = true)))
    }

    @Test fun `golden hour beats a plain night`() {
        val f = WeatherFace.of(breakdown(score = 1f, night = true, golden = true))
        assertEquals(WeatherFace.GOLDEN, f)
        assertNotNull(f.emoji)
    }
}
