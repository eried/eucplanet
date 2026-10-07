package com.eried.eucplanet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which section a query opens.
 *
 * The search used to hide every section that did not match and expand every
 * one that did, which cost the rider the map of the page and buried the answer
 * in a wall of open sections: "speed" opened five at once. Now the page keeps
 * its shape, closed sections carry a count of what they hold, and exactly one
 * section opens.
 *
 * That makes "which one" the whole question, and getting it wrong is worse
 * than before: with only one section opening, opening the wrong one leaves a
 * rider staring at a screen with no visible match at all. Which is what
 * happened - "speed" opened Display and appearance, because it mentions a
 * speed unit once and sits higher up the page than Wheel parameters.
 *
 * The rules are mirrored from SectionDef.searchScore. They are duplicated
 * because the real one lives inside a @Composable a unit test cannot call, so
 * this fixes the BEHAVIOUR rather than the call; SettingsSearchIndexTest
 * guards the corpora themselves.
 */
class SettingsSearchRankingTest {

    private val titleWeight = 1000

    private class Section(val title: String, val labels: List<String>)

    private fun score(s: Section, query: String): Int {
        if (query.isEmpty()) return 0
        val titleHit = if (s.title.contains(query, ignoreCase = true)) titleWeight else 0
        return titleHit + s.labels.count { it.contains(query, ignoreCase = true) }
    }

    private fun opened(sections: List<Section>, query: String): String? =
        sections.map { it to score(it, query) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first?.title

    // The real page order, abbreviated to the sections that matter here.
    private val display = Section(
        "Display & appearance",
        listOf("Units", "Speed unit", "Keep screen on", "Language", "Theme"),
    )
    private val wheel = Section(
        "Wheel parameters",
        listOf(
            "Speed calibration", "Speed offset", "Battery calibration",
            "Speed limits", "Tiltback Speed", "Alarm Speed", "Legal mode speed",
        ),
    )
    private val voice = Section("Voice", listOf("Voice announcements", "Speak speed"))
    private val page = listOf(display, wheel, voice)

    @Test fun `the section that says the word most is the one that opens`() {
        // The regression: Display sits above Wheel parameters and mentions
        // speed once, so "first match down the page" opened it and the rider
        // saw a section with the answer nowhere on screen.
        assertEquals("Wheel parameters", opened(page, "speed"))
    }

    @Test fun `a section named for the word wins however often others mention it`() {
        // A rider typing "voice" means the Voice section. Nothing else may
        // outrank it by repeating the word in its labels.
        val chatty = Section("Alarms", List(40) { "voice template $it" })
        assertEquals("Voice", opened(listOf(chatty, voice), "voice"))
    }

    @Test fun `a query nothing matches opens nothing`() {
        assertEquals(null, opened(page, "zzzz"))
        assertEquals(null, opened(page, ""))
    }

    private fun count(s: Section, query: String) =
        s.labels.count { it.contains(query, ignoreCase = true) }

    @Test fun `the count on a closed section is what it actually holds`() {
        // The badge is the rider's reason to open it, so it has to be true.
        // Counted off the labels, not off the score: the score folds in the
        // title weight, and six of Wheel parameters' labels say speed while
        // its title does not.
        assertEquals(1, count(display, "speed"))
        assertEquals(6, count(wheel, "speed"))
        assertEquals(1, count(wheel, "tiltback"))
        assertEquals(0, count(display, "tiltback"))
    }

    @Test fun `matching is case insensitive, because a rider types lowercase`() {
        assertTrue(score(wheel, "SPEED") > 0)
        assertTrue(score(wheel, "Speed") > 0)
        assertEquals(score(wheel, "speed"), score(wheel, "SpEeD"))
    }
}
