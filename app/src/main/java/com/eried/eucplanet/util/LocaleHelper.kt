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

    fun current(): String {
        val list = AppCompatDelegate.getApplicationLocales()
        return if (list.isEmpty) "en" else list.get(0)?.language ?: "en"
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
