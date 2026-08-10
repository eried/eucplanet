package com.eried.eucplanet.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard for the Settings search index.
 *
 * Each settings section carries a hand-written `searchCorpus`, and typing in the
 * search field hides every section whose corpus does not contain the query. A
 * section header that nobody remembered to add is therefore unreachable by
 * search: "Home screen widget" shipped that way, and so had six older sections.
 *
 * Reads the source rather than the compiled screen because the corpora are
 * built inside a @Composable, which a unit test cannot invoke. Crude, but it
 * fails for exactly the reason a human would.
 */
class SettingsSearchIndexTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** Every `R.string.x` named in a SectionHeader call anywhere in the screen. */
    private fun renderedSectionHeaders(): Set<String> =
        Regex("""SectionHeader\(\s*stringResource\(R\.string\.(\w+)""")
            .findAll(source).map { it.groupValues[1] }.toSet()

    /** Every `R.string.x` listed in any of the `corpusXxx` definitions. */
    private fun indexedStrings(): Set<String> {
        val block = Regex("""val corpusGeneral = listOf\(.*?val sections""", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.value
        assertTrue("Could not locate the corpus block; did the corpora move?", block != null)
        return Regex("""R\.string\.(\w+)""").findAll(block!!).map { it.groupValues[1] }.toSet()
    }

    @Test fun everySectionHeaderIsSearchable() {
        val missing = (renderedSectionHeaders() - indexedStrings()).sorted()
        assertTrue(
            "These settings section headers are not in any searchCorpus, so searching " +
                "for them finds nothing. Add each to the corpus of the tab it appears " +
                "in: $missing",
            missing.isEmpty(),
        )
    }

    @Test fun theHeadersAndCorpusAreBothActuallyFound() {
        // Guards the guard: a regex that silently matches nothing would make the
        // test above pass forever.
        assertTrue("found no SectionHeader calls at all", renderedSectionHeaders().size > 15)
        assertTrue("found no corpus entries at all", indexedStrings().size > 50)
    }
}
