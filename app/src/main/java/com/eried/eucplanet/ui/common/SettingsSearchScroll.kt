package com.eried.eucplanet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * Ranks for search auto-scroll. When a search query matches more than one place,
 * the highest rank wins and gets scrolled to the top; ties break to the topmost.
 * A section title outranks a named sub-block inside it, so "moto" lands on the
 * "Motor sound" section while "motoe" lands on the "Motoeye" sub-block (the only
 * thing that still matches once the section title no longer does).
 */
object SearchAnchorRank {
    const val SECTION = 100
    const val SUBBLOCK = 50
    /** A row or button label: below its sub-block, so a broader match lands higher up. */
    const val LABEL = 25
}

/**
 * Collects the on-screen position of every searchable anchor (section titles and
 * named sub-blocks) so the settings search can smooth-scroll the best match to
 * the top as the rider types. Anchors report through [Modifier.settingsSearchAnchor];
 * the screen reads [best] once layout settles and animates to it.
 */
class SettingsSearchScroller {
    /** Current trimmed query, pushed in by the screen each recomposition. */
    var query: String by mutableStateOf("")

    data class Anchor(val rank: Int, val text: String, val windowY: Float)

    // Keyed so a section/sub-block overwrites its own previous position rather
    // than piling up stale entries as layout shifts.
    private val anchors = mutableStateMapOf<String, Anchor>()

    fun report(key: String, rank: Int, text: String, windowY: Float) {
        anchors[key] = Anchor(rank, text, windowY)
    }

    fun forget(key: String) {
        anchors.remove(key)
    }

    /**
     * Where to land, in order, when no anchor on screen holds the query: the
     * sub-headings of what the open section matched, then its title. A button
     * that only shows in some states is still found that way, at the heading
     * it lives under. Set by the screen each time the query changes.
     */
    var landing: List<String> = emptyList()

    /** Best match for the current query: highest rank first, then topmost. */
    fun best(): Anchor? {
        val q = query.trim()
        if (q.isEmpty()) return null
        anchors.values
            .filter { it.text.contains(q, ignoreCase = true) }
            .minWithOrNull(compareByDescending<Anchor> { it.rank }.thenBy { it.windowY })
            ?.let { return it }
        return landing.firstNotNullOfOrNull { text ->
            anchors.values.filter { it.text == text }.minByOrNull { it.windowY }
        }
    }

    /** The set of reported window-Ys, used to detect when layout has settled. */
    fun positionsSignature(): Int =
        anchors.values.fold(0) { acc, a -> acc * 31 + a.windowY.toInt() }
}

val LocalSettingsSearchScroller = compositionLocalOf<SettingsSearchScroller?> { null }

/**
 * Marks a composable as a search anchor. While a scroller is provided, it reports
 * its window position (and forgets it when it leaves composition, e.g. filtered
 * out by the query) so the screen can scroll the best match to the top.
 */
fun Modifier.settingsSearchAnchor(key: String, rank: Int, text: String): Modifier = composed {
    val scroller = LocalSettingsSearchScroller.current
    DisposableEffect(key, scroller) {
        onDispose { scroller?.forget(key) }
    }
    if (scroller == null) Modifier
    else Modifier.onGloballyPositioned { scroller.report(key, rank, text, it.positionInWindow().y) }
}

/**
 * Marks a row or button label as a place the search can land, while the query
 * is in it. Labels that do not match never report, so a long open section
 * costs nothing while the rider types.
 */
fun Modifier.settingsSearchLabel(text: String): Modifier = composed {
    val q = LocalSettingsSearchQuery.current.trim()
    if (q.isEmpty() || !text.contains(q, ignoreCase = true)) Modifier
    else Modifier.settingsSearchAnchor("label:$text", SearchAnchorRank.LABEL, text)
}

/** A button label that the search marks and can scroll to. */
@Composable
fun SearchableText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        highlightMatches(text, LocalSettingsSearchQuery.current, onFill = true),
        modifier = modifier.settingsSearchLabel(text),
        style = style,
    )
}
