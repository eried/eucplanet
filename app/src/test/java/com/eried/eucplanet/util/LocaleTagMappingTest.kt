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
