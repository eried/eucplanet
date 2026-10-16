package com.eried.eucplanet.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.ADVANCED_SPECS
import com.eried.eucplanet.data.model.ActionCatalog
import com.eried.eucplanet.data.model.AdvGroup
import com.eried.eucplanet.data.model.MetricCatalog

/** One text a settings section shows, in the rider's language. */
internal class SearchEntry(
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
internal class SearchFolds(val counts: Map<String, Int>, val open: Set<String>) {
    companion object {
        val None = SearchFolds(emptyMap(), emptySet())
    }
}

internal val LocalSearchFolds = androidx.compose.runtime.compositionLocalOf { SearchFolds.None }

/**
 * How much one hit counts toward opening its section. A description mentions
 * many things in passing, so it counts least: "speed" should open the section
 * with speed rows, not the one whose captions say speed most often.
 */
internal val SettingsSearchIndex.Kind.weight: Int
    get() = when (this) {
        SettingsSearchIndex.Kind.TITLE -> 3
        SettingsSearchIndex.Kind.BUTTON, SettingsSearchIndex.Kind.LABEL -> 2
        SettingsSearchIndex.Kind.DESCRIPTION -> 1
    }

/**
 * Names a section shows that come from a registry at runtime, so the code
 * holds no text for the index to find: the dashboard's metric and action
 * tiles, and every Advanced row.
 */
private fun registryNames(): Map<String, List<Pair<Int, Int>>> = mapOf(
    "dashboard" to (MetricCatalog.all.map { it.labelRes } + ActionCatalog.all.map { it.labelRes }).map { it to 0 },
    // Each Advanced row sits in its group's card.
    "advanced" to AdvGroup.entries.map { it.titleRes to 0 } + ADVANCED_SPECS.map { it.label to it.group.titleRes },
    // Words the HUD is known by that its rows never spell out.
    "integration" to listOf(R.string.hud_search_corpus to 0),
)

/**
 * Every section's searchable texts, read once per language. They used to be
 * rebuilt from hundreds of string lookups on every keystroke.
 */
@Composable
internal fun rememberSearchCorpora(): Map<String, List<SearchEntry>> {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        val extras = registryNames()
        (SettingsSearchIndex.sections.keys + extras.keys).associateWith { key ->
            val found = SettingsSearchIndex.sections[key].orEmpty().map { e ->
                SearchEntry(
                    resources.getString(e.res),
                    e.kind,
                    e.under.takeIf { it != 0 }?.let { resources.getString(it) },
                    e.folds.map { resources.getString(it) },
                )
            }
            found + extras[key].orEmpty().map { (res, fold) ->
                SearchEntry(
                    resources.getString(res),
                    SettingsSearchIndex.Kind.LABEL,
                    null,
                    if (fold == 0) emptyList() else listOf(resources.getString(fold)),
                )
            }
        }
    }
}
