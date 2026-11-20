package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.R
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Toggle rows are searchable, and a card's title id tells it apart from the others. */
class SettingsSearchCoverageTest {

    private val src = File("src/main/java/com/eried/eucplanet/ui/settings")

    private fun resId(name: String) = R.string::class.java.getField(name).getInt(null)

    private val nameOf: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    @Test fun `every toggle row label is in the index`() {
        val labels = src.walk().filter { it.extension == "kt" }
            .flatMap {
                Regex(
                    """\b(SwitchSetting|SwitchSettingWithDesc|AnnounceSwitchSetting|SwitchRow|ToggleRow)\(\s*""" +
                        """(?:label\s*=\s*)?stringResource\(\s*R\.string\.(\w+)"""
                ).findAll(it.readText())
            }
            .map { it.groupValues[2] }.toSet()
        assertTrue("found no toggle rows at all", labels.size > 30)
        val indexed = SettingsSearchIndex.sections.values.flatten().map { it.res }.toSet()
        val missing = labels.filter { resId(it) !in indexed }.sorted()
        assertTrue("These toggles are not searchable: $missing", missing.isEmpty())
    }

    // A card finds its search hits by its title's string id, not by the words
    // it shows, so two cards may read the same ("Customize" for the radar and
    // for the glasses) as long as each has an id of its own.
    @Test fun `no two cards in one section share a title id`() {
        val dups = SettingsSearchIndex.cards.flatMap { (key, ids) ->
            ids.toList().groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { "$key: ${nameOf[it]}" }
        }
        assertTrue("Cards share a title id: $dups", dups.isEmpty())
    }

    @Test fun `the radar and the glasses each have a Customize card of their own`() {
        val integration = SettingsSearchIndex.cards.getValue("integration").toList()
        assertTrue(R.string.radar_customize in integration)
        assertTrue(R.string.engo_pages_title in integration)
    }

    // Across sections a title id may repeat (one card drawn by a composable two
    // sections share): each section hands its cards only its own counts.
    @Test fun `card counts are scoped to their section`() {
        val src = java.io.File("src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt").readText()
        assertTrue(src.contains("counts = sec.foldCounts(query)"))
    }
}
