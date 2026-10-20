package com.eried.eucplanet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LocaleCoverageTest checks every key exists in every locale, but not that it
 * has text. This checks the music and reset-after strings are not blank.
 */
class VoiceMusicSettingsStringsTest {

    private val resDir = File("src/main/res")

    private fun localeDirs(): List<File> =
        resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            ?.filter { File(it, "strings.xml").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun stringsIn(dir: File): Map<String, String> {
        val text = File(dir, "strings.xml").readText()
        val re = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun wanted(english: Map<String, String>): List<String> =
        english.keys.filter { it.startsWith("voice_music_") } + "voice_trigger_reset_after"

    @Test fun `the keys under test exist in English`() {
        val english = stringsIn(File(resDir, "values"))
        assertTrue(english.keys.count { it.startsWith("voice_music_") } >= 5)
        assertTrue("voice_trigger_reset_after" in english)
        assertTrue(localeDirs().size >= 20)
    }

    @Test fun `every locale has non-empty text for them`() {
        val english = stringsIn(File(resDir, "values"))
        val keys = wanted(english)
        val missing = mutableListOf<String>()
        for (dir in localeDirs()) {
            val strings = stringsIn(dir)
            for (key in keys) if (strings[key].isNullOrBlank()) missing += "${dir.name}/$key"
        }
        assertEquals("blank or missing: $missing", emptyList<String>(), missing)
    }
}
