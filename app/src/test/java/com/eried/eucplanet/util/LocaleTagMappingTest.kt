package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a tag Android hands back maps onto a language the app ships. */
class LocaleTagMappingTest {

    @Test fun `Portugal and Brazil each get their own Portuguese`() {
        assertEquals("pt-PT", LocaleHelper.normalizeToSupportedTag("pt-PT"))
        assertEquals("pt-BR", LocaleHelper.normalizeToSupportedTag("pt-BR"))
        assertEquals("pt-BR", LocaleHelper.normalizeToSupportedTag("pt"))
    }

    @Test fun `British and American English each get their own`() {
        assertEquals("en-GB", LocaleHelper.normalizeToSupportedTag("en-GB"))
        // The regions that spell it the British way.
        for (region in listOf("IE", "AU", "NZ", "ZA", "IN")) {
            assertEquals("en-$region", "en-GB", LocaleHelper.normalizeToSupportedTag("en-$region"))
        }
        assertEquals("en", LocaleHelper.normalizeToSupportedTag("en-US"))
        assertEquals("en", LocaleHelper.normalizeToSupportedTag("en-CA"))
        assertEquals("en", LocaleHelper.normalizeToSupportedTag("en"))
    }

    @Test fun `the picker names English and Spanish by region`() {
        val names = LocaleHelper.SUPPORTED.associate { it.tag to it.nativeName }
        assertEquals("English (US)", names["en"])
        assertEquals("English (UK)", names["en-GB"])
        assertEquals("Español (ES)", names["es"])
        assertEquals("Español (LA)", names["es-419"])
    }

    @Test fun `Indonesian comes back under its legacy code`() {
        // Android reports Indonesian as "in", the code its resources still use.
        assertEquals("id", LocaleHelper.normalizeToSupportedTag("in"))
        assertEquals("id", LocaleHelper.normalizeToSupportedTag("in-ID"))
        assertEquals("id", LocaleHelper.normalizeToSupportedTag("id"))
    }

    @Test fun `the new plain languages match their own tag`() {
        for (tag in listOf("bg", "el", "sk", "th", "vi")) {
            assertEquals(tag, LocaleHelper.normalizeToSupportedTag(tag))
            assertEquals(tag, LocaleHelper.normalizeToSupportedTag("$tag-XX"))
        }
    }
}
