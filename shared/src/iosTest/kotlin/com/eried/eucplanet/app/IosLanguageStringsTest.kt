package com.eried.eucplanet.app

import kotlin.test.Test
import kotlin.test.assertEquals

class IosLanguageStringsTest {

    @Test
    fun aVoiceTagFindsItsFolder() {
        // The forms the voice setting stores ("es_MX") and the ones iOS names.
        val cases = mapOf(
            "en_US" to "values",
            "en-AU" to "values-en-rGB",
            "en_GB" to "values-en-rGB",
            "es_ES" to "values-es",
            "es_MX" to "values-es-rXL",
            "es-419" to "values-es-rXL",
            "nb_NO" to "values-no",
            "id_ID" to "values-id",
            "pt_BR" to "values-pt-rBR",
            "pt_PT" to "values-pt-rPT",
            "zh_CN" to "values-zh",
            "zh-Hant-HK" to "values-zh-rTW",
            "zh_TW" to "values-zh-rTW",
            "fi_FI" to "values-fi",
            // A language the app does not have reads English, as on Android.
            "ca_ES" to "values",
        )
        for ((tag, folder) in cases) assertEquals(folder, IosLanguageStrings.folderFor(tag), tag)
    }

    @Test
    fun theStringFileIsReadAsTheLibraryWritesIt() {
        // "Hola" and "%1${'$'}s km" in base64, a plural and an array that are skipped.
        val cvr = """
            version:0
            plurals|route_stop_count|ONE:JWQgc3RvcA==,OTHER:JWQgc3RvcHM=
            string|greeting|SG9sYQ==
            string|distance|JTEkcyBrbQ==
            string-array|ordinals|Rmlyc3Q=
        """.trimIndent()
        val parsed = IosLanguageStrings.parse(cvr)
        assertEquals(mapOf("greeting" to "Hola", "distance" to "%1\$s km"), parsed)
    }
}

class IosSpeechLocaleTest {
    private val apple = listOf(
        "en-US", "en-GB", "en-AU", "es-ES", "es-MX", "es-US", "nb-NO", "id-ID",
        "zh-CN", "zh-TW", "zh-HK", "pt-BR", "pt-PT", "fi-FI",
    )

    @Test
    fun thePickersTagsFindAnAppleRecogniser() {
        val cases = mapOf(
            "en-US" to "en-US", "en" to "en-US", "en-GB" to "en-GB",
            "es-419" to "es-MX", "es" to "es-ES", "es_ES" to "es-ES",
            "no" to "nb-NO", "id" to "id-ID", "zh-TW" to "zh-TW", "zh" to "zh-CN",
            "pt-BR" to "pt-BR", "fi_FI" to "fi-FI",
        )
        for ((tag, want) in cases) {
            assertEquals(want, IosSpeechListener.recognizerLocale(tag, apple, region = "SE"), tag)
        }
        // The phone's own region decides between kinds of one language.
        assertEquals("en-AU", IosSpeechListener.recognizerLocale("en", apple, region = "AU"))
        // No recogniser for the language at all.
        assertEquals(null, IosSpeechListener.recognizerLocale("th-TH", apple, region = "TH"))
    }
}
