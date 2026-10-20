package com.eried.eucplanet.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which section a search opens, through the same scoring the settings screen
 * uses. "units" used to open Dashboard layout (its Toggle units tile tied the
 * Units row and sits higher up), and "distance" opened Advanced, which merely
 * mentions a distance four times.
 */
class SectionScoreTest {

    private fun label(text: String) = SearchEntry(text, SettingsSearchIndex.Kind.LABEL, null)
    private fun desc(text: String) = SearchEntry(text, SettingsSearchIndex.Kind.DESCRIPTION, null)

    private fun opens(query: String, sections: Map<String, List<SearchEntry>>, titles: Map<String, String> = emptyMap()): String =
        sections.entries.maxBy { (key, entries) ->
            val hits = entries.filter { it.text.contains(query, ignoreCase = true) }
            sectionScore(titles[key] ?: key, hits, query, 1000)
        }.key

    @Test fun `a row named exactly the query beats a tile that mentions it`() {
        val s = linkedMapOf(
            "Dashboard layout" to listOf(label("Toggle units")),
            "Display and appearance" to listOf(label("Units")),
        )
        assertEquals("Display and appearance", opens("units", s))
    }

    @Test fun `a row named exactly the query beats several passing mentions`() {
        val s = linkedMapOf(
            "Display and appearance" to listOf(label("Distance")),
            "Advanced" to listOf(
                label("Turn prepare distance"), label("Turn execute distance"),
                label("Max start distance"), label("Fast-approach distance"),
            ),
        )
        assertEquals("Display and appearance", opens("distance", s))
    }

    @Test fun `a section title still beats an exactly named row`() {
        val s = linkedMapOf(
            "Display and appearance" to listOf(label("Voice")),
            "Voice" to listOf(label("Speech rate")),
        )
        assertEquals("Voice", opens("voice", s))
    }

    @Test fun `a description that equals the query is not a named row`() {
        val s = linkedMapOf(
            "A" to listOf(desc("Units")),
            "B" to listOf(label("Toggle units"), label("Units per hour")),
        )
        assertEquals("B", opens("units", s))
    }
}
