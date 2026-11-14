package com.eried.eucplanet.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The share menu's static-location texts.
 *
 * The locale cases are the point of this test: the app ships in 23 languages,
 * and a phone set to one that writes decimals with a comma used to be the way
 * a "share my location" link silently landed somewhere else. Google Maps reads
 * `?q=59,91390,10,75220` as four fields, not two coordinates.
 */
class ShareLocationTextTest {

    private val original: Locale = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun `coordinates carry five decimals and a comma space`() {
        Locale.setDefault(Locale.US)
        assertEquals("59.91390, 10.75220", ShareLocationText.coordinates(59.9139, 10.7522))
    }

    @Test
    fun `link is a Google Maps query with no space`() {
        Locale.setDefault(Locale.US)
        assertEquals(
            "https://maps.google.com/?q=59.91390,10.75220",
            ShareLocationText.mapsLink(59.9139, 10.7522)
        )
    }

    @Test
    fun `a comma-decimal locale still writes dots`() {
        // Germany, France and Russia all write 59,9139. Every one of them has
        // to come out of here as 59.91390 or the link is a different place.
        listOf(Locale.GERMANY, Locale.FRANCE, Locale("ru", "RU")).forEach { locale ->
            Locale.setDefault(locale)
            assertEquals(
                "$locale coordinates",
                "59.91390, 10.75220",
                ShareLocationText.coordinates(59.9139, 10.7522)
            )
            assertEquals(
                "$locale link",
                "https://maps.google.com/?q=59.91390,10.75220",
                ShareLocationText.mapsLink(59.9139, 10.7522)
            )
        }
    }

    @Test
    fun `negative and southern positions keep their sign`() {
        Locale.setDefault(Locale.US)
        assertEquals("-33.44890, -70.66930", ShareLocationText.coordinates(-33.4489, -70.6693))
        assertTrue(ShareLocationText.mapsLink(-33.4489, -70.6693).endsWith("-33.44890,-70.66930"))
    }

    @Test
    fun `rounding is to five decimals, not truncation`() {
        Locale.setDefault(Locale.US)
        assertEquals("1.00000, 2.12346", ShareLocationText.coordinates(0.999999, 2.1234567))
    }
}
