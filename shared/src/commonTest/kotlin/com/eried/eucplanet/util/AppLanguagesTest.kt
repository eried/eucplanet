package com.eried.eucplanet.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLanguagesTest {
    private fun supported(tag: String) = AppLanguages.SUPPORTED.any { it.tag == tag }

    /** The names iOS gives the app's languages (CFBundleLocalizations) all land on a picker key. */
    @Test
    fun iosLocalizationsMapToPickerKeys() {
        val ios = listOf(
            "en", "es-419", "bg", "cs", "da", "de", "el", "es", "fi", "fr", "hu", "id", "it", "ja",
            "ko", "nl", "nb", "pl", "pt-BR", "pt-PT", "ro", "ru", "sk", "sv", "th", "tr", "uk", "vi",
            "zh-Hans", "zh-Hant",
        )
        for (tag in ios) {
            assertTrue(supported(AppLanguages.normalizeToSupportedTag(tag)), tag)
        }
        assertEquals("no", AppLanguages.normalizeToSupportedTag("nb"))
        assertEquals("zh", AppLanguages.normalizeToSupportedTag("zh-Hans"))
        assertEquals("zh-TW", AppLanguages.normalizeToSupportedTag("zh-Hant"))
    }

    @Test
    fun androidTagsMapAsBefore() {
        assertEquals("no", AppLanguages.normalizeToSupportedTag("nb-NO"))
        assertEquals("es-419", AppLanguages.normalizeToSupportedTag("es-MX"))
        assertEquals("es", AppLanguages.normalizeToSupportedTag("es-ES"))
        assertEquals("pt-BR", AppLanguages.normalizeToSupportedTag("pt"))
        assertEquals("id", AppLanguages.normalizeToSupportedTag("in"))
        assertEquals("ar", AppLanguages.normalizeToSupportedTag("ar"))
    }
}
