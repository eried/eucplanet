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
     * One list because adding a language used to mean editing four places -
     * the strings folder, locales_config.xml, the settings picker, and the two
     * sets below - and missing one was silent. Japanese, Korean and Turkish
     * shipped complete translations for months while LocaleHelper.detectSystemLanguage
     * still fell through to English for those phones, because only the sets
     * were missed. LocaleCoverageTest now fails the build on that mismatch.
     */
    val SUPPORTED: List<Language> = listOf(
        Language("en", "English"),
        Language("bg", "Български"),
        Language("cs", "Čeština"),
        Language("da", "Dansk"),
        Language("de", "Deutsch"),
        Language("el", "Ελληνικά"),
        Language("es", "Español"),
        Language("es-419", "Español (Latinoamérica)"),
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
        Language("pt-BR", "Português (Brasil)"),
        Language("pt-PT", "Português (Portugal)"),
        Language("ro", "Română"),
        Language("ru", "Русский"),
        Language("sk", "Slovenčina"),
        Language("sv", "Svenska"),
        Language("th", "ไทย"),
        Language("tr", "Türkçe"),
        Language("uk", "Українська"),
        Language("vi", "Tiếng Việt"),
        Language("zh", "简体中文"),
        Language("zh-TW", "繁體中文"),
    )
}
