package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Drift guard for terminal punctuation in helper copy.
 *
 * The settings screens had drifted into two habits: 148 descriptive strings
 * ended with a full stop and 76 did not, with no rule saying which was right.
 * Reading them showed the split was not random - placeholders and value lists
 * genuinely should not take one - so the rule below is what the app follows
 * now, and this test is what keeps the next string from reopening it.
 *
 * THE RULE: a descriptive string that is a sentence explaining something ends
 * with a full stop. These are exempt:
 *  - text-field placeholders and terse field hints
 *  - lists of values ("Garmin: Start / Amazfit: Select")
 *  - anything ending in a URL, a format argument or markup
 *  - widget-surface strings, where the character is not worth the space
 */
class HelperCopyPunctuationTest {

    /** Key suffixes that mark a string as helper copy rather than a label. */
    private val descriptive = listOf(
        "_desc", "_description", "_help", "_hint", "_subtitle", "_body", "_explain", "_note", "_caption",
    )

    /**
     * The exemptions, by name, so the reasoning stays readable. Adding a key
     * here is a deliberate statement that the string is not a sentence.
     */
    private val exempt = setOf(
        // Placeholders and terse field hints.
        "nav_search_hint", "lockdown_code_hint", "cloud_backup_name_label",
        // End in a URL: a stop would read as part of the address.
        "hud_install_hint", "nav_setting_ocm_key_hint", "hud_update_url",
        // Lists of values, not sentences.
        "alarm_voice_template_help",
        "watch_hardware_button_1_subtitle", "watch_hardware_button_2_subtitle",
        "watch_hardware_button_3_subtitle",
    )

    private val terminal = listOf(".", "!", "?", "。", "！", "？", "…", ":", "：", ";", "；", "·")

    private val row = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun needsStop(name: String, body: String): Boolean {
        if (name in exempt) return false
        if (descriptive.none { name.endsWith(it) }) return false
        if (name.startsWith("widget_")) return false
        val t = body.trim()
        if (t.split(Regex("\\s+")).size < 3) return false
        if (terminal.any { t.endsWith(it) }) return false
        if (Regex("""%\d*\$?[sdf]$|\}$|>$|\)$""").containsMatchIn(t)) return false
        if (Regex("""[a-z0-9-]+\.(ried\.no|com|org|net|io)(/\S*)?$""").containsMatchIn(t)) return false
        return true
    }

    @Test
    fun `every explanatory string in English ends with a full stop`() {
        val text = File("src/main/res/values/strings.xml").readText()
        val missing = row.findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (name, body) -> needsStop(name, body) }
            .map { it.first }
            .toList()
        assertEquals("explanatory strings with no terminal stop", emptyList<String>(), missing)
    }

    @Test
    fun `the translations punctuate the same strings, in their own script`() {
        // Japanese and Chinese take a full-width stop; a Latin dot in the
        // middle of their own punctuation is the mistake this catches.
        val cjk = setOf("values-ja", "values-zh", "values-zh-rTW")
        val en = File("src/main/res/values/strings.xml").readText()
        val expected = row.findAll(en)
            .filter { needsStop(it.groupValues[1], it.groupValues[2]) || true }
            .map { it.groupValues[1] }
            .toSet()
        // Only the keys the English file punctuates are checked, so a
        // translation is never asked to end a sentence English left open.
        val punctuatedInEnglish = row.findAll(en)
            .filter { m ->
                val (name, body) = m.groupValues[1] to m.groupValues[2]
                descriptive.any { name.endsWith(it) } && name !in exempt &&
                    !name.startsWith("widget_") &&
                    body.trim().let { t -> terminal.any { t.endsWith(it) } } &&
                    body.trim().split(Regex("\\s+")).size >= 3
            }
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(expected.intersect(punctuatedInEnglish), punctuatedInEnglish)

        val gaps = mutableListOf<String>()
        for (dir in File("src/main/res").listFiles().orEmpty()) {
            if (!dir.isDirectory || !dir.name.startsWith("values-")) continue
            val file = File(dir, "strings.xml")
            if (!file.exists()) continue
            val stop = if (dir.name in cjk) "。" else "."
            row.findAll(file.readText()).forEach { m ->
                val (name, body) = m.groupValues[1] to m.groupValues[2]
                if (name !in punctuatedInEnglish) return@forEach
                val t = body.trim()
                if (t.isEmpty()) return@forEach
                // A translation may legitimately end in an argument or markup.
                if (Regex("""%\d*\$?[sdf]$|\}$|>$""").containsMatchIn(t)) return@forEach
                if (terminal.none { t.endsWith(it) }) gaps += "${dir.name}/$name"
                else if (dir.name in cjk && t.endsWith(".")) gaps += "${dir.name}/$name (Latin stop)"
                else if (dir.name !in cjk && t.endsWith("。")) gaps += "${dir.name}/$name (CJK stop)"
                else if (stop.isEmpty()) Unit
            }
        }
        assertEquals("translations missing or mismatching a terminal stop", emptyList<String>(), gaps)
    }
}
