package com.eried.eucplanet.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    /** Every shipped language, in picker order; see [AppLanguages.SUPPORTED]. */
    val SUPPORTED: List<AppLanguages.Language> get() = AppLanguages.SUPPORTED

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
     * Latin-American Spanish as "es-419"; Portuguese as "pt-BR" or "pt-PT";
     * Indonesian as the legacy "in".
     * Returns the input unchanged if no normalisation is needed.
     */
    fun normalizeToSupportedTag(tag: String): String {
        val lower = tag.lowercase()
        return when {
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
     * On first launch, pick a default app language based on the phone's system
     * locale. Maps to one of the locales the app actually ships translations for;
     * unsupported languages fall back to English. Some special cases:
     *  - nb / nn (Norwegian Bokmål / Nynorsk) -> "no"
     *  - es in a Latin American region        -> "es-419"
     *  - pt in Portugal or Portuguese Africa   -> "pt-PT", elsewhere "pt-BR"
     *  - in (Android's legacy Indonesian)     -> "id"
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
            primary == "pt" && region in setOf("PT", "AO", "MZ", "CV", "GW", "ST", "TL") -> "pt-PT"
            primary == "pt" -> "pt-BR"
            primary == "in" || primary == "id" -> "id"
            primary == "zh" && region in setOf("TW", "HK", "MO") -> "zh-TW"
            primary in plainTags -> primary
            else -> "en"
        }
    }
}
