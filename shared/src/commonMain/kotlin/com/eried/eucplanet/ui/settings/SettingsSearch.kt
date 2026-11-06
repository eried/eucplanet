package com.eried.eucplanet.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.eried.eucplanet.data.model.ADVANCED_SPECS
import com.eried.eucplanet.platform.rememberStringReader
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.hud_search_corpus
import com.eried.eucplanet.data.model.ActionCatalog
import com.eried.eucplanet.data.model.AdvGroup
import com.eried.eucplanet.data.model.MetricCatalog
import org.jetbrains.compose.resources.StringResource

/** One text a settings section shows, in the rider's language. */
class SearchEntry(
    val text: String,
    val kind: SettingsSearchIndex.Kind,
    /** The sub-heading it sits under, where the search lands when it is not on screen. */
    val under: String?,
    /** Titles of the cards it is folded into, outermost first. */
    val folds: List<String> = emptyList(),
)

/**
 * What the query found inside the collapsible cards, keyed by card title:
 * how many hits each one hides, and which ones the search opens. The cards
 * read this themselves, so no call site passes anything.
 */
class SearchFolds(val counts: Map<String, Int>, val open: Set<String>) {
    companion object {
        val None = SearchFolds(emptyMap(), emptySet())
    }
}

val LocalSearchFolds = androidx.compose.runtime.compositionLocalOf { SearchFolds.None }

/**
 * How much one hit counts toward opening its section. A description mentions
 * many things in passing, so it counts least: "speed" should open the section
 * with speed rows, not the one whose captions say speed most often.
 */
val SettingsSearchIndex.Kind.weight: Int
    get() = when (this) {
        SettingsSearchIndex.Kind.TITLE -> 3
        SettingsSearchIndex.Kind.BUTTON, SettingsSearchIndex.Kind.LABEL -> 2
        SettingsSearchIndex.Kind.DESCRIPTION -> 1
    }

/**
 * How well one section answers [query], for picking the one that opens.
 *
 * A title hit outranks everything. Next, a row named exactly what was typed:
 * "units" is the Units row in Display, not the Toggle units tile Dashboard
 * also offers, and "distance" is the Distance row, not the four Advanced rows
 * that merely mention a distance. Then every hit counts by its [weight].
 */
fun sectionScore(title: String, hits: List<SearchEntry>, query: String, titleWeight: Int): Int {
    if (query.isEmpty()) return 0
    val titleHit = if (title.contains(query, ignoreCase = true)) titleWeight else 0
    val named = if (hits.any { it.kind != SettingsSearchIndex.Kind.DESCRIPTION && it.text.trim().equals(query.trim(), ignoreCase = true) }) EXACT_ROW_WEIGHT else 0
    return titleHit + named + hits.sumOf { it.kind.weight }
}

/** Below a title, above any number of passing mentions. */
private const val EXACT_ROW_WEIGHT = 100

/**
 * Names a section shows that come from a registry at runtime, so the code
 * holds no text for the index to find: the dashboard's metric and action
 * tiles, and every Advanced row.
 */
private fun registryNames(): Map<String, List<Pair<StringResource, StringResource?>>> = mapOf(
    "dashboard" to (MetricCatalog.all.map { it.labelRes } + ActionCatalog.all.map { it.labelRes }).map { it to null },
    // Each Advanced row sits in its group's card.
    "advanced" to AdvGroup.entries.map { it.titleRes to null } +
        ADVANCED_SPECS.map { it.label to it.group.titleRes },
    // Words the HUD is known by that its rows never spell out.
    "integration" to listOf(Res.string.hud_search_corpus to null),
)

/**
 * Every section's searchable texts, read once per language. They used to be
 * rebuilt from hundreds of string lookups on every keystroke.
 */
@Composable
fun rememberSearchCorpora(): Map<String, List<SearchEntry>> {
    val text = rememberStringReader()
    return remember(text) {
        val extras = registryNames()
        (SettingsSearchIndex.sections.keys + extras.keys).associateWith { key ->
            val found = SettingsSearchIndex.sections[key].orEmpty().map { e ->
                SearchEntry(
                    text(e.res),
                    e.kind,
                    e.under?.let { text(it) },
                    e.folds.map { text(it) },
                )
            }
            found + extras[key].orEmpty().map { (res, fold) ->
                SearchEntry(
                    text(res),
                    SettingsSearchIndex.Kind.LABEL,
                    null,
                    if (fold == null) emptyList() else listOf(text(fold)),
                )
            }
        }
    }
}
