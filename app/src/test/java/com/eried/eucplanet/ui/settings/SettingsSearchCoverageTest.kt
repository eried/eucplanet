package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.R
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Toggle rows are searchable, and a card's title tells it apart from the others. */
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

    /** Every locale's text for [names], keyed by folder (values, values-de, ...). */
    private fun texts(names: Set<String>): Map<String, Map<String, String>> =
        File("src/main/res").listFiles().orEmpty()
            .filter { it.name.startsWith("values") && File(it, "strings.xml").exists() }
            .associate { dir ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(File(dir, "strings.xml"))
                val nodes = doc.getElementsByTagName("string")
                val map = HashMap<String, String>()
                for (i in 0 until nodes.length) {
                    val n = nodes.item(i)
                    val name = n.attributes.getNamedItem("name")?.nodeValue ?: continue
                    if (name in names) map[name] = n.textContent.trim().lowercase()
                }
                dir.name to map
            }

    private fun duplicates(cards: Set<String>): List<String> =
        texts(cards).flatMap { (locale, map) ->
            map.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
                .map { (text, ids) -> "$locale '$text': ${ids.sorted()}" }
        }.sorted()

    private fun cardsOf(entries: List<SettingsSearchIndex.Entry>) =
        entries.flatMap { it.folds.toList() }.mapNotNull { nameOf[it] }.toSet()

    @Test fun `no two cards in one section share a title in any locale`() {
        val dups = SettingsSearchIndex.sections.flatMap { (key, list) ->
            duplicates(cardsOf(list)).map { "$key: $it" }
        }
        assertTrue("Cards share a title: $dups", dups.isEmpty())
    }

    // Across sections a title may repeat (Voice and Watch both have
    // Advanced): each section hands its cards only its own counts.
    @Test fun `card counts are scoped to their section`() {
        val src = java.io.File("src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt").readText()
        assertTrue(src.contains("counts = sec.foldCounts(query)"))
    }
}
