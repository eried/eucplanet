package com.eried.eucplanet.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Toggle rows are searchable, and a card's title id tells it apart from the others. */
class SettingsSearchCoverageTest {

    /** The settings code, wherever it lives now: :app's and :shared's. */
    private val src = listOf(
        File("src/main/java/com/eried/eucplanet/ui/settings"),
        File("../shared/src/commonMain/kotlin/com/eried/eucplanet/ui/settings"),
    )

    @Test fun `every toggle row label is in the index`() {
        val labels = src.asSequence().flatMap { it.walk() }.filter { it.extension == "kt" }
            .flatMap {
                Regex(
                    """\b(SwitchSetting|SwitchSettingWithDesc|AnnounceSwitchSetting|SwitchRow|ToggleRow)\(\s*""" +
                        """(?:label\s*=\s*)?stringResource\(\s*(?:Res|R)\.string\.(\w+)"""
                ).findAll(it.readText())
            }
            .map { it.groupValues[2] }.toSet()
        assertTrue("found no toggle rows at all", labels.size > 30)
        val indexed = SettingsSearchIndex.sections.values.flatten().map { it.res.key }.toSet()
        val missing = labels.filter { it !in indexed }.sorted()
        assertTrue("These toggles are not searchable: $missing", missing.isEmpty())
    }

    // A card finds its search hits by its title's string id, not by the words
    // it shows, so two cards may read the same ("Customize" for the radar and
    // for the glasses) as long as each has an id of its own.
    @Test fun `no two cards in one section share a title id`() {
        val dups = SettingsSearchIndex.cards.flatMap { (key, ids) ->
            ids.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys.map { "$key: $it" }
        }
        assertTrue("Cards share a title id: $dups", dups.isEmpty())
    }

    @Test fun `the radar and the glasses each have a Customize card of their own`() {
        val integration = SettingsSearchIndex.cards.getValue("integration").map { it.key }
        assertTrue("radar_customize" in integration)
        assertTrue("engo_pages_title" in integration)
    }

    // Across sections a title id may repeat (one card drawn by a composable two
    // sections share): each section hands its cards only its own counts.
    @Test fun `card counts are scoped to their section`() {
        val src = src.map { File(it, "SettingsScreen.kt") }.first { it.exists() }.readText()
        assertTrue(src.contains("counts = sec.foldCounts(query)"))
    }
}
