package com.eried.eucplanet.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    /**
     * A language the app ships translations for: its BCP-47 tag and its name
     * written in that language (a rider looking for their own language scans
     * for "Suomi", not "Finnish").
     */
    data class Language(val tag: String, val nativeName: String)

    /**
     * Every shipped language, in picker order: English first, then by tag.
     *
     * One list because adding a language used to mean editing four places -
     * the strings folder, locales_config.xml, the settings picker, and the two
     * sets below - and missing one was silent. Japanese, Korean and Turkish
     * shipped complete translations for months while [detectSystemLanguage]
     * still fell through to English for those phones, because only the sets
     * were missed. LocaleCoverageTest now fails the build on that mismatch.
     */
    val SUPPORTED: List<Language> = listOf(
        Language("en", "English"),
        Language("cs", "Čeština"),
        Language("da", "Dansk"),
        Language("de", "Deutsch"),
        Language("es", "Español"),
        Language("es-419", "Español (Latinoamérica)"),
        Language("fi", "Suomi"),
        Language("fr", "Français"),
        Language("hu", "Magyar"),
        Language("it", "Italiano"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("nl", "Nederlands"),
        Language("no", "Norsk"),
        Language("pl", "Polski"),
        Language("pt-BR", "Português (Brasil)"),
        Language("ro", "Română"),
        Language("ru", "Русский"),
        Language("sv", "Svenska"),
        Language("tr", "Türkçe"),
        Language("uk", "Українська"),
        Language("zh", "简体中文"),
        Language("zh-TW", "繁體中文"),
    )

    /**
     * Tags with no region part, so a bare system language ("fi") can be matched
     * against them directly. The region-carrying tags are special-cased in the
     * two functions below, since each needs its own rule.
     */
    private val plainTags: Set<String> =
        SUPPORTED.map { it.tag }.filter { '-' !in it }.toSet()

    fun apply(tag: String) {
        val list = if (tag.isBlank() || tag == "en") {
            LocaleListCompat.forLanguageTags("en")
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(list)
    }

    /**
     * The locale currently applied via AppCompatDelegate, as a full BCP-47
     * tag ("pt-BR", "es-419"). Empty string if nothing has been applied.
     * The full tag matters because we store full tags in settings.language
     * and the picker compares for equality.
     */
    fun current(): String {
        val list = AppCompatDelegate.getApplicationLocales()
        if (list.isEmpty) return ""
        val loc = list.get(0) ?: return ""
        return loc.toLanguageTag()
    }

    /**
     * Maps an Android-normalised tag back to a key in our supported list.
     * Android stores Norwegian as "nb" / "nb-NO" after we apply "no";
     * Latin-American Spanish as "es-419"; Brazilian Portuguese as "pt-BR".
     * Returns the input unchanged if no normalisation is needed.
     */
    fun normalizeToSupportedTag(tag: String): String {
        val lower = tag.lowercase()
        return when {
            lower.startsWith("nb") || lower.startsWith("nn") -> "no"
            lower == "pt" || lower.startsWith("pt-") -> "pt-BR"
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
     * On first launch, pick a default app language based on the phone's system
     * locale. Maps to one of the locales the app actually ships translations for;
     * unsupported languages fall back to English. Some special cases:
     *  - nb / nn (Norwegian Bokmål / Nynorsk) -> "no"
     *  - es in a Latin American region        -> "es-419"
     *  - pt anywhere                          -> "pt-BR" (we only ship Brazilian)
     *  - zh in TW / HK / MO                   -> "zh-TW"
     */
    fun detectSystemLanguage(): String {
        val sys = Locale.getDefault()
        val primary = sys.language.lowercase()
        val region = sys.country.uppercase()
        // Region codes covered by Spanish-Latin-America. "419" is the UN M49
        // region itself, which Android reports directly when the phone is set
        // to "Español (Latinoamérica)" rather than to a single country.
        val latAmEs = setOf(
            "419",
            "AR", "BO", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN",
            "MX", "NI", "PA", "PE", "PR", "PY", "SV", "US", "UY", "VE"
        )
        return when {
            primary == "nb" || primary == "nn" || primary == "no" -> "no"
            primary == "es" && region in latAmEs -> "es-419"
            primary == "es" -> "es"
            primary == "pt" -> "pt-BR"
            primary == "zh" && region in setOf("TW", "HK", "MO") -> "zh-TW"
            primary in plainTags -> primary
            else -> "en"
        }
    }
}
