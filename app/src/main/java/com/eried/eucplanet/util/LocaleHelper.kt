package com.eried.eucplanet.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
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
            else -> lower.substringBefore('-').let { primary ->
                if (primary in setOf("da","de","en","fr","it","nl","pl","ru","sv","uk","zh")) primary else tag
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
     */
    fun detectSystemLanguage(): String {
        val sys = Locale.getDefault()
        val primary = sys.language.lowercase()
        val region = sys.country.uppercase()
        // Region codes covered by Spanish-Latin-America (UN M49 region 419).
        val latAmEs = setOf(
            "AR", "BO", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN",
            "MX", "NI", "PA", "PE", "PR", "PY", "SV", "US", "UY", "VE"
        )
        return when {
            primary == "nb" || primary == "nn" || primary == "no" -> "no"
            primary == "es" && region in latAmEs -> "es-419"
            primary == "es" -> "es"
            primary == "pt" -> "pt-BR"
            primary == "zh" -> "zh"
            primary in setOf(
                "da", "de", "en", "fr", "it", "nl", "pl", "ru", "sv", "uk"
            ) -> primary
            else -> "en"
        }
    }
}
