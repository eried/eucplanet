package com.eried.eucplanet.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings search reads its words from the code through a generated
 * index (shared/settings-search-index.gradle.kts). These pin what it must find,
 * so a change to how the settings are written cannot quietly empty it.
 */
class SettingsSearchIndexTest {

    private fun entry(section: String, name: String) =
        SettingsSearchIndex.sections.getValue(section).firstOrNull { it.res.key == name }

    /** The settings code, wherever it lives now: :app's and :shared's. */
    private fun settingsSources() = (File("src/main/java/com/eried/eucplanet/ui/settings").walk() +
        File("../shared/src/commonMain/kotlin/com/eried/eucplanet/ui/settings").walk())
        .filter { it.extension == "kt" }

    @Test fun `every section has its texts`() {
        assertEquals(
            setOf("general", "dashboard", "display", "speed", "voice", "motor", "cloud", "alarms",
                "auto", "navigator", "location", "integration", "watch", "advanced"),
            SettingsSearchIndex.sections.keys,
        )
        SettingsSearchIndex.sections.forEach { (key, list) ->
            assertTrue("$key has nothing to search", list.size >= 3)
        }
    }

    @Test fun `every sub-heading is searchable`() {
        // The rule the hand-written lists kept missing: "Home screen widget"
        // and six older headings once shipped that nobody could search for.
        // Read off the source, because a heading is a call, not data.
        val headings = settingsSources()
            .flatMap { Regex("""SectionHeader\(\s*stringResource\((?:Res|R)\.string\.(\w+)""").findAll(it.readText()) }
            .map { it.groupValues[1] }.toSet()
        assertTrue("found no headings at all", headings.size > 15)
        val indexed = SettingsSearchIndex.sections.values.flatten().map { it.res.key }.toSet()
        val missing = headings.filter { it !in indexed }.sorted()
        assertTrue("These headings are not searchable: $missing", missing.isEmpty())
    }

    @Test fun `buttons are found, with the heading they sit under`() {
        val crew = entry("cloud", "crews_pass_action")!!
        assertEquals(SettingsSearchIndex.Kind.BUTTON, crew.kind)
        assertEquals("section_online_stats", crew.under?.key)
        assertEquals(SettingsSearchIndex.Kind.BUTTON, entry("integration", "flic_start_scan")!!.kind)
    }

    @Test fun `rows and descriptions are told apart`() {
        assertEquals(SettingsSearchIndex.Kind.LABEL, entry("voice", "voice_speech_speed")!!.kind)
        assertEquals(SettingsSearchIndex.Kind.DESCRIPTION, entry("cloud", "cloud_trips_caption")!!.kind)
    }

    @Test fun `a label picked in a when is found`() {
        assertTrue(entry("voice", "report_speed") != null)
    }

    private fun anywhere(name: String) =
        SettingsSearchIndex.sections.values.flatten().first { it.res.key == name }

    @Test fun `a text in a card inside a card knows both, outermost first`() {
        assertEquals(
            listOf("widget_section", "widget_customize"),
            anywhere("widget_pick_desc").folds.map { it.key },
        )
        // The card's own title shows while it is shut, so it is not in itself.
        assertEquals(listOf("widget_section"), anywhere("widget_customize").folds.map { it.key })
    }

    @Test fun `a card's info line is folded with it`() {
        assertEquals(
            listOf("watch_buttons_touch_label"),
            anywhere("watch_buttons_touch_info").folds.map { it.key },
        )
    }

    @Test fun `every card in the code is known to the search`() {
        // A card is found by its name, so one added later is covered without
        // anyone listing it. This fails if that stops being true.
        val titles = settingsSources()
            .flatMap {
                Regex("""\b(\w*Collaps\w*)\(\s*(?:title\s*=\s*)?stringResource\(\s*(?:Res|R)\.string\.(\w+)""")
                    .findAll(it.readText())
            }
            // The top-level sections count their own matches; this is about
            // the cards inside them.
            .filter { it.groupValues[1] != "CollapsibleSection" }
            .map { it.groupValues[2] }
            .toSet()
        assertTrue("found no cards at all", titles.size > 10)
        val folded = SettingsSearchIndex.sections.values.flatten().flatMap { e -> e.folds.map { it.key } }.toSet()
        val missing = titles.filter { it !in folded }.sorted()
        assertTrue("These cards hide texts the search cannot point to: $missing", missing.isEmpty())
    }

    @Test fun `what only shows in a dialog is left out`() {
        assertEquals(null, entry("speed", "lockdown_title"))
    }
}
