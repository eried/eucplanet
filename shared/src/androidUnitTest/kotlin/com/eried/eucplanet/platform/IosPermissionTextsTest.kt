package com.eried.eucplanet.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The iPhone's permission prompts come from Info.plist in English and from
 * iosApp/<locale>.lproj/InfoPlist.strings in every other language the app
 * declares. A prompt added to Info.plist, or a language added to
 * CFBundleLocalizations, must arrive translated everywhere.
 */
class IosPermissionTextsTest {

    /** Walks up from the test working dir until [relative] exists. */
    private fun repoFile(relative: String): File {
        var dir: File? = File(java.lang.System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("Could not locate '$relative' from ${java.lang.System.getProperty("user.dir")}")
    }

    private val plist = repoFile("iosApp/Info.plist").readText()

    /** Info.plist's usage descriptions, key to English text. */
    private val english: Map<String, String> =
        Regex("<key>(NS\\w+UsageDescription)</key>\\s*<string>([^<]*)</string>")
            .findAll(plist).associate { it.groupValues[1] to it.groupValues[2] }

    private val locales: List<String> =
        Regex("<key>CFBundleLocalizations</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL)
            .find(plist)!!.groupValues[1]
            .let { Regex("<string>([^<]*)</string>").findAll(it).map { m -> m.groupValues[1] }.toList() }

    private fun strings(locale: String): Map<String, String> =
        Regex("\"(\\w+)\"\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\";")
            .findAll(repoFile("iosApp/$locale.lproj/InfoPlist.strings").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun everyLanguageHasEveryPrompt() {
        assertTrue("no usage descriptions found in Info.plist", english.isNotEmpty())
        for (locale in locales) {
            val texts = strings(locale)
            assertEquals("iosApp/$locale.lproj/InfoPlist.strings", english.keys, texts.keys)
            texts.forEach { (key, text) -> assertTrue("$locale $key is empty", text.isNotBlank()) }
        }
    }

    @Test
    fun englishMatchesInfoPlist() {
        assertEquals(english, strings("en"))
    }
}
