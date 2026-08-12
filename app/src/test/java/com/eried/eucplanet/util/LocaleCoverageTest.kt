package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Drift guard for the shipped-language registry.
 *
 * Adding a language touches three things that live apart: the strings folder,
 * locales_config.xml (which is what Android's per-app language picker reads),
 * and [LocaleHelper.SUPPORTED]. Missing one is silent at build time and only
 * shows up as a rider seeing English on a phone set to their own language,
 * which is exactly how Japanese, Korean and Turkish went unmapped. These tests
 * make that mismatch a build failure.
 */
class LocaleCoverageTest {

    /** res folder name for a tag: "pt-BR" -> "values-pt-rBR", "es-419" -> "values-b+es+419". */
    private fun resFolder(tag: String): String {
        if (tag == "es-419") return "values-b+es+419"
        val parts = tag.split("-")
        return if (parts.size == 1) "values-${parts[0]}" else "values-${parts[0]}-r${parts[1]}"
    }

    private fun res(name: String) = File("src/main/res/$name")

    @Test
    fun `every supported language ships a strings file`() {
        val missing = LocaleHelper.SUPPORTED
            .filter { it.tag != "en" } // English is the default values/ folder
            .filter { !res("${resFolder(it.tag)}/strings.xml").exists() }
            .map { it.tag }
        assertEquals("supported languages with no strings.xml", emptyList<String>(), missing)
    }

    /** Names declared by `<string-array name="...">` in one strings file. */
    private fun stringArrayNames(file: File): Set<String> =
        Regex("""<string-array\s+name="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every language translates every string-array`() {
        // Lint's MissingTranslation covers <string> and stops there, so a
        // <string-array> can be absent from a language for a whole release
        // without anything complaining. nav_stop_ordinals shipped that way in
        // Czech, Finnish, Hungarian and Romanian: those riders were read
        // English stop names by a navigator speaking their own language
        // everywhere else.
        val expected = stringArrayNames(res("values/strings.xml"))
        val gaps = LocaleHelper.SUPPORTED
            .filter { it.tag != "en" }
            .mapNotNull { lang ->
                val file = res("${resFolder(lang.tag)}/strings.xml")
                if (!file.exists()) return@mapNotNull null  // covered by the test above
                val missing = expected - stringArrayNames(file)
                if (missing.isEmpty()) null else "${lang.tag}: $missing"
            }
        assertEquals("languages missing a string-array", emptyList<String>(), gaps)
    }

    @Test
    fun `every translated folder is a supported language`() {
        val known = LocaleHelper.SUPPORTED.map { resFolder(it.tag) }.toSet()
        val orphans = (res(".").listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .filter { File(it, "strings.xml").exists() }
            .map { it.name }
            .filter { it !in known }
        assertEquals("translated folders absent from LocaleHelper.SUPPORTED", emptyList<String>(), orphans)
    }

    @Test
    fun `locales_config lists exactly the supported languages`() {
        val xml = File("src/main/res/xml/locales_config.xml").readText()
        val declared = Regex("""android:name="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals(LocaleHelper.SUPPORTED.map { it.tag }.toSet(), declared)
    }

    @Test
    fun `system locale detection reaches every shipped language`() {
        // The regression this file exists for: a phone set to a language we
        // translate must not land on English.
        val default = Locale.getDefault()
        try {
            for (lang in LocaleHelper.SUPPORTED) {
                if (lang.tag == "en") continue
                Locale.setDefault(Locale.forLanguageTag(lang.tag))
                val detected = LocaleHelper.detectSystemLanguage()
                assertEquals("system locale ${lang.tag}", lang.tag, detected)
            }
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `regional variants map to the variant we actually ship`() {
        val default = Locale.getDefault()
        try {
            // Traditional Chinese regions must not fall back to simplified.
            for (region in listOf("TW", "HK", "MO")) {
                Locale.setDefault(Locale.forLanguageTag("zh-$region"))
                assertEquals("zh-$region", "zh-TW", LocaleHelper.detectSystemLanguage())
            }
            Locale.setDefault(Locale.forLanguageTag("zh-CN"))
            assertEquals("zh", LocaleHelper.detectSystemLanguage())

            Locale.setDefault(Locale.forLanguageTag("es-MX"))
            assertEquals("es-419", LocaleHelper.detectSystemLanguage())
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            assertEquals("es", LocaleHelper.detectSystemLanguage())

            Locale.setDefault(Locale.forLanguageTag("pt-PT"))
            assertEquals("pt-BR", LocaleHelper.detectSystemLanguage())

            // Android reports Norwegian as nb; we ship it as "no".
            Locale.setDefault(Locale.forLanguageTag("nb-NO"))
            assertEquals("no", LocaleHelper.detectSystemLanguage())
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `normalize agrees with the tags we store`() {
        assertEquals("zh-TW", LocaleHelper.normalizeToSupportedTag("zh-Hant-TW"))
        assertEquals("zh", LocaleHelper.normalizeToSupportedTag("zh-CN"))
        assertEquals("no", LocaleHelper.normalizeToSupportedTag("nb-NO"))
        assertEquals("pt-BR", LocaleHelper.normalizeToSupportedTag("pt-PT"))
        assertEquals("es-419", LocaleHelper.normalizeToSupportedTag("es-MX"))
        // Previously fell through unmapped, so the picker showed nothing selected.
        assertEquals("ja", LocaleHelper.normalizeToSupportedTag("ja-JP"))
        assertEquals("ko", LocaleHelper.normalizeToSupportedTag("ko-KR"))
        assertEquals("tr", LocaleHelper.normalizeToSupportedTag("tr-TR"))
    }

    @Test
    fun `picker entries are unique and named in their own language`() {
        val tags = LocaleHelper.SUPPORTED.map { it.tag }
        assertEquals("duplicate tags", tags.size, tags.toSet().size)
        val names = LocaleHelper.SUPPORTED.map { it.nativeName }
        assertEquals("duplicate names", names.size, names.toSet().size)
        assertTrue("every language needs a name", names.none { it.isBlank() })
    }
}
