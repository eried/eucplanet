package com.eried.eucplanet.util

/** The languages the app ships translations for; LocaleHelper applies them on Android. */
object AppLanguages {
    /**
     * A language the app ships translations for: its BCP-47 tag and its name
     * written in that language (a rider looking for their own language scans
     * for "Suomi", not "Finnish").
     */
    data class Language(val tag: String, val nativeName: String)

    /**
     * Every shipped language, in picker order: English first, then by tag.
     *
     * English, Spanish, Portuguese and Chinese each come in two kinds, named
     * the way the trip viewer and eucstats name them, so all four show one
     * list. British English is generated from the English strings
     * (tools/i18n/en_gb.py), never written by hand.
     *
     * One list because adding a language used to mean editing four places -
     * the strings folder, locales_config.xml, the settings picker, and the tag
     * sets - and missing one was silent. Japanese, Korean and Turkish
     * shipped complete translations for months while LocaleHelper.detectSystemLanguage
     * still fell through to English for those phones, because only the sets
     * were missed. LocaleCoverageTest now fails the build on that mismatch.
     */
    val SUPPORTED: List<Language> = listOf(
        Language("en", "English (US)"),
        Language("en-GB", "English (UK)"),
        Language("bg", "Български"),
        Language("cs", "Čeština"),
        Language("da", "Dansk"),
        Language("de", "Deutsch"),
        Language("el", "Ελληνικά"),
        Language("es", "Español (ES)"),
        Language("es-419", "Español (LA)"),
        Language("fi", "Suomi"),
        Language("fr", "Français"),
        Language("hu", "Magyar"),
        Language("id", "Bahasa Indonesia"),
        Language("it", "Italiano"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("nl", "Nederlands"),
        Language("no", "Norsk"),
        Language("pl", "Polski"),
        Language("pt-BR", "Português (BR)"),
        Language("pt-PT", "Português (PT)"),
        Language("ro", "Română"),
        Language("ru", "Русский"),
        Language("sk", "Slovenčina"),
        Language("sv", "Svenska"),
        Language("th", "ไทย"),
        Language("tr", "Türkçe"),
        Language("uk", "Українська"),
        Language("vi", "Tiếng Việt"),
        Language("zh", "中文 (简体)"),
        Language("zh-TW", "中文 (繁體)"),
    )

    /** Where English is the British kind: the regions eucstats sends there. */
    val BRITISH_REGIONS = setOf("GB", "UK", "IE", "AU", "NZ", "ZA", "IN")

    /** Tags with no region part, so a bare language ("fi") matches them directly. */
    private val plainTags: Set<String> = SUPPORTED.map { it.tag }.filter { '-' !in it }.toSet()

    /**
     * Maps a platform's language tag back to a key in [SUPPORTED]. Android
     * stores Norwegian as "nb" / "nb-NO" after we apply "no", iOS names it
     * "nb"; Latin-American Spanish is "es-419" or a country; Portuguese is
     * "pt-BR" or "pt-PT"; Indonesian can be the legacy "in"; Chinese can carry
     * its script ("zh-Hans", "zh-Hant-TW"); English is "en-GB" or "en-US".
     * Returns the input unchanged if no normalisation is needed.
     */
    fun normalizeToSupportedTag(tag: String): String {
        val lower = tag.lowercase()
        return when {
            lower.startsWith("en-") ->
                if (lower.substringAfter('-').substringBefore('-').uppercase() in BRITISH_REGIONS) "en-GB" else "en"
            lower.startsWith("nb") || lower.startsWith("nn") -> "no"
            lower.startsWith("pt-pt") -> "pt-PT"
            lower == "pt" || lower.startsWith("pt-") -> "pt-BR"
            lower == "in" || lower.startsWith("in-") -> "id"
            lower == "es-419" -> "es-419"
            lower.startsWith("es-") && lower != "es-es" -> "es-419"
            lower.startsWith("es") -> "es"
            // Traditional Chinese is a different script, not an accent: folding
            // it into "zh" would show a Taipei rider simplified characters.
            lower.startsWith("zh-tw") || lower.startsWith("zh-hant") ||
                lower.startsWith("zh-hk") || lower.startsWith("zh-mo") -> "zh-TW"
            else -> lower.substringBefore('-').let { primary ->
                if (primary in plainTags) primary else tag
            }
        }
    }

    /**
     * The locale the iPhone's string lookup is handed for [localization], the
     * name iOS gives the applied language (a CFBundleLocalizations entry).
     * iOS already folds the rider's language into one of those: es-MX to
     * es-419, en-AU to en-GB, zh-Hant-HK to zh-Hant. The lookup only knows a
     * language and a two-letter region, the names shared/compose-strings.gradle.kts
     * writes the folders under.
     */
    fun iosStringsLocale(localization: String): String = when (localization) {
        "es-419" -> "es-XL"
        "zh-Hans" -> "zh"
        "zh-Hant" -> "zh-TW"
        else -> localization
    }
}
