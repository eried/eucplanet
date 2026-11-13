package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Restore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.eried.eucplanet.platform.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.QrCode2
import com.eried.eucplanet.ui.theme.AccentBlue

/**
 * Inner layout switcher for a composite tile. Each layout renders its cells
 * with a per-cell accent + uppercase short label; no stats, no sparkline.
 * Public-internal so the live dashboard renderer can reuse the exact same
 * body, the editor passes placeholder values, the live dashboard passes
 * real telemetry, both go through this composable.
 */
@Composable
fun CompositeMetricBody(
    composite: MetricComposite,
    // (cellKey, cellStat) -> formatted value. The stat MUST come from the cell
    // being rendered, not be re-derived from the key: a composite can hold the
    // same metric in two cells (e.g. AVG SPEED + MAX SPEED), and keying only by
    // metric collapses both onto the first cell's stat.
    valueOf: (String, DashboardStat) -> String
) {
    val cells = composite.cells.take(composite.layout.cellCount)
    val rawStats = composite.cellStats.take(composite.layout.cellCount)
    // Pair cells with their stats first so filtering keeps them aligned,
    // THEN drop any empty / "–" cells. Empty cells used to render an
    // en-dash placeholder that took up its slot's full share of width;
    // when the rider configured (say) a COL3 tile with only 2 cells
    // populated they got "value | value | –", which read like two
    // off-center reads with a permanent dash hole. Filtering here makes
    // the populated cells share the FULL tile width / height; the
    // rider's layout choice still controls orientation (row vs column)
    // and gets the divider treatment, just over the real cell count.
    val populated = cells.mapIndexedNotNull { i, key ->
        val k = key.trim()
        // COMPOSITE_CELL_EMPTY ("(none)") collapses the slot -- drop it.
        // COMPOSITE_CELL_BLANK ("(empty)") reserves the slot -- keep it
        // so the layout count stays put; CompositeCell renders an empty Box.
        if (k.isEmpty() || k == COMPOSITE_CELL_EMPTY) null
        else k to (rawStats.getOrNull(i) ?: DashboardStat.CURRENT)
    }
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    // Layout collapses by populated count: anything with <=1 populated cell
    // renders as a single full-tile read; 2 cells render as the rider's
    // chosen orientation (ROW2 stacks, COL2/COL3 side-by-side); only the
    // full 3 cells render as COL3.
    when {
        populated.isEmpty() -> {
            // Nothing to draw -- keep the tile blank rather than rendering
            // three dashes. This is the deliberate "empty composite" state.
        }
        populated.size == 1 -> {
            val (key, stat) = populated[0]
            // ROW-style layout for a single cell when the rider picked ROW2
            // (matches the visual mass they expected); column for everything
            // else. Either way it's a single cell filling the tile.
            if (composite.layout == CompositeLayout.ROW2) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    CompositeCellRow(key, stat, valueOf, Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    CompositeCell(key, stat, valueOf, Modifier.weight(1f))
                }
            }
        }
        populated.size == 2 -> {
            if (composite.layout == CompositeLayout.ROW2) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    CompositeCellRow(populated[0].first, populated[0].second, valueOf, Modifier.fillMaxWidth().weight(1f))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(divider))
                    CompositeCellRow(populated[1].first, populated[1].second, valueOf, Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    CompositeCell(populated[0].first, populated[0].second, valueOf, Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(divider))
                    CompositeCell(populated[1].first, populated[1].second, valueOf, Modifier.weight(1f))
                }
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            CompositeCell(populated[0].first, populated[0].second, valueOf, Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(divider))
            CompositeCell(populated[1].first, populated[1].second, valueOf, Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(divider))
            CompositeCell(populated[2].first, populated[2].second, valueOf, Modifier.weight(1f))
        }
    }
}

/**
 * Inner body of a custom tile (icon + text, with domain fallback for blank
 * text). Public-internal so the live dashboard renders the same content; the
 * editor adds the dashed-border drag-source wrapper, the live dashboard adds
 * the actual tap-action handler.
 */
@Composable
fun CustomTileBody(tile: CustomTile) {
    val labelOrFallback = tile.text.ifBlank { extractDomainHint(tile.url) }
    val labelColor = if (tile.text.isBlank() && tile.url.isBlank())
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = if (tile.text.isBlank() && tile.url.isBlank())
        androidx.compose.ui.text.font.FontStyle.Italic
        else androidx.compose.ui.text.font.FontStyle.Normal
    val displayText = labelOrFallback.ifBlank { stringResource(Res.string.dashboard_composite_empty_label) }
    // For OPEN_URL and SHOW_QR tiles, replace the rider's chosen group
    // icon with the action's own glyph (link / qr) so the tile itself
    // tells you what tapping it will do. NONE-action tiles keep the
    // Truly-empty tiles (no text, no url, no action) fall back to the
    // current default-icon constant so the dashboard reads "blank slot,
    // configure me" with the same glyph the rider sees on fresh tiles.
    // Previously this was hardcoded to "EMPTY" (outlined checkbox);
    // tracking CUSTOM_TILE_DEFAULT_ICON keeps the unconfigured visual
    // aligned with whatever default the codebase has chosen.
    val isTrulyEmpty = tile.text.isBlank() && tile.url.isBlank() &&
        tile.action == CustomTileAction.NONE
    val mainIcon = when {
        isTrulyEmpty -> groupIconFor(CUSTOM_TILE_DEFAULT_ICON)
        else -> actionBadgeIcon(tile.action) ?: groupIconFor(tile.icon)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            mainIcon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            displayText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontStyle = labelStyle,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Resolves a curated [GROUP_ICON_CHOICES] key to a Material icon. Used by
 * both [ActionGroupTile] and the icon picker in [ActionGroupSheet] so the
 * tile preview and the picker stay in sync, change one key here, both
 * surfaces update.
 */
fun groupIconFor(key: String): ImageVector = when (key) {
    "FOLDER" -> Icons.Filled.Folder
    "STAR" -> Icons.Filled.Star
    "BOLT" -> Icons.Filled.Bolt
    "FAVORITE" -> Icons.Filled.Favorite
    "DASHBOARD" -> Icons.Filled.Dashboard
    "EXTENSION" -> Icons.Filled.Extension
    "TUNE" -> Icons.Filled.Tune
    "WIDGETS" -> Icons.Filled.Widgets
    "APPS" -> Icons.Filled.Apps
    "BUILD" -> Icons.Filled.Build
    "HOME" -> Icons.Filled.Home
    "PERSON" -> Icons.Filled.Person
    "SETTINGS" -> Icons.Filled.Settings
    "SHIELD" -> Icons.Filled.Shield
    "MAP" -> Icons.Filled.Map
    "PHOTO_CAMERA" -> Icons.Filled.PhotoCamera
    "MUSIC_NOTE" -> Icons.Filled.MusicNote
    "PHONE" -> Icons.Filled.Phone
    "WIFI" -> Icons.Filled.Wifi
    "SEARCH" -> Icons.Filled.Search
    "SAVE" -> Icons.Filled.Save
    "SEND" -> Icons.Filled.Send
    "SHARE" -> Icons.Filled.Share
    "EDIT" -> Icons.Filled.Edit
    "REFRESH" -> Icons.Filled.Refresh
    "DONE" -> Icons.Filled.Done
    "INFO" -> Icons.Filled.Info
    "WARNING" -> Icons.Filled.Warning
    "NOTIFICATIONS" -> Icons.Filled.Notifications
    "LINK" -> Icons.Filled.Link
    // Outlined square placeholder for freshly-spawned / never-configured
    // custom tiles, so the dashboard doesn't pretend an unconfigured tile
    // is a link/qr. The rider can still pick this from the icon grid as
    // an explicit "blank slot" visual.
    "EMPTY" -> Icons.Filled.CheckBoxOutlineBlank
    else -> Icons.Filled.Folder
}

/**
 * Display label for a metric key. Reads from [com.eried.eucplanet.data.model.MetricCatalog]
 * so adding a new metric is one entry in `MetricCatalog.all`, this
 * function never needs to grow another branch. The composite-empty
 * sentinel and unknown keys fall back to bespoke strings.
 *
 * When [short] is true, prefers the `metric_chip_<key>_tile` resource
 * if it exists, falling back to the full label. Tile / pool / grid-
 * preview render sites pass true so what the rider sees in the editor
 * matches what they'll see on the actual dashboard tile face; picker
 * dropdowns and the slot-edit sheet header keep `short = false` so
 * the rider always sees the FULL descriptive name when choosing.
 */
@Composable
fun metricChipLabel(key: String, short: Boolean = false): String {
    if (key == COMPOSITE_CELL_EMPTY) return stringResource(Res.string.dashboard_composite_empty_label)
    if (key == COMPOSITE_CELL_BLANK) return stringResource(Res.string.dashboard_composite_blank_label)
    val spec = com.eried.eucplanet.data.model.MetricCatalog.byKey(key) ?: return key
    if (short) {
        // Looked up by name, as Android's getIdentifier did: most metrics
        // have no short tile form.
        val tileRes = remember(key) { Res.allStringResources["metric_chip_${key.lowercase()}_tile"] }
        if (tileRes != null) return stringResource(tileRes)
    }
    return stringResource(spec.labelRes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestorePickerDialog(
    onDismiss: () -> Unit,
    loadEntries: suspend () -> List<com.eried.eucplanet.data.sync.BackupEntry>,
    onPicked: (com.eried.eucplanet.data.sync.BackupEntry) -> Unit
) {
    var entries by remember { mutableStateOf<List<com.eried.eucplanet.data.sync.BackupEntry>?>(null) }
    var selected by remember { mutableStateOf<com.eried.eucplanet.data.sync.BackupEntry?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = loadEntries()
        // "(factory)" is a synthetic, always-available entry: it resets to
        // built-in defaults instead of reading a file. It sits right under
        // "(default)", with a divider separating it from the rider's named
        // backups below. Never pre-selected when real backups exist, so a
        // stray Restore tap can't wipe settings.
        val factory = com.eried.eucplanet.data.sync.BackupEntry(
            fileName = "", label = null, isFactory = true
        )
        val def = loaded.filter { it.label == null }
        val named = loaded.filter { it.label != null }
        entries = def + factory + named
        selected = loaded.firstOrNull() ?: factory
    }
    val defaultLabel = stringResource(Res.string.cloud_restore_picker_default)
    val factoryLabel = stringResource(Res.string.cloud_restore_picker_factory)
    fun entryLabel(e: com.eried.eucplanet.data.sync.BackupEntry): String =
        if (e.isFactory) factoryLabel else e.label ?: defaultLabel
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(Res.string.cloud_restore_confirm_title)) },
        text = {
            val list = entries
            when {
                list == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                list.isEmpty() -> Text(stringResource(Res.string.cloud_restore_picker_empty))
                else -> ExposedDropdownMenuBox(
                    expanded = dropdownOpen,
                    onExpandedChange = { dropdownOpen = !dropdownOpen }
                ) {
                    OutlinedTextField(
                        value = selected?.let { entryLabel(it) } ?: defaultLabel,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        colors = themedFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false },
                        containerColor = MaterialTheme.appColors.menuBackground
                    ) {
                        list.forEachIndexed { index, entry ->
                            DropdownMenuItem(
                                text = { Text(entryLabel(entry)) },
                                onClick = {
                                    selected = entry
                                    dropdownOpen = false
                                }
                            )
                            // Divider after "(factory)" splits the built-in
                            // resets from the rider's named backups below.
                            if (entry.isFactory && index < list.lastIndex) {
                                androidx.compose.material3.HorizontalDivider(
                                    color = MaterialTheme.appColors.divider
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selected != null,
                onClick = { selected?.let(onPicked) },
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(Res.string.action_restore)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}

@Composable
fun CompositeCell(
    key: String,
    stat: DashboardStat,
    valueOf: (String, DashboardStat) -> String,
    modifier: Modifier = Modifier
) {
    val isNone = key.isEmpty() || key == COMPOSITE_CELL_EMPTY
    val isBlank = key == COMPOSITE_CELL_BLANK
    val isEmpty = isNone || isBlank
    val accent = if (!isEmpty) metricAccentColor(key) else MaterialTheme.colorScheme.onSurfaceVariant
    if (isBlank) {
        // "(empty)" placeholder: reserves the slot but draws nothing,
        // so the surrounding composite layout stays the size the rider
        // designed even though this cell intentionally has no content.
        Box(modifier = modifier)
        return
    }
    if (isNone) {
        // "(none)": the slot ITSELF is meant to read as "deliberately
        // blank, prefer to collapse". The CompositeMetricBody filter
        // drops these from the layout entirely; the dash here is for
        // any code path that still renders the cell (the editor list
        // before the filter, for instance).
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                "–",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
        return
    }
    val label = metricChipLabel(key, short = true).uppercase()
    val value = valueOf(key, stat)
    // Stat indicator on top, only shown when the rider picked a non-
    // default stat (Min / Max / Avg / Median / P75 / etc.). Tinted with
    // the metric's accent so the cell reads as "MAX of SPEED" at a
    // glance rather than the value alone being ambiguous.
    val showStat = stat != DashboardStat.CURRENT && stat != DashboardStat.NONE
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showStat) {
                Text(
                    statShortLabel(stat),
                    fontSize = 7.sp,
                    color = accent.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
            Text(
                label,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * ROW2 layout cell, label LEFT, value RIGHT. The 2-row composite is wide
 * and short, so stacking label-above-value (the column form) wastes the
 * horizontal space. Putting them side-by-side reads more like a row in a
 * spec sheet ("SPEED   42 km/h"), which is what the rider asked for.
 */
@Composable
fun CompositeCellRow(
    key: String,
    stat: DashboardStat,
    valueOf: (String, DashboardStat) -> String,
    modifier: Modifier = Modifier
) {
    val isNone = key.isEmpty() || key == COMPOSITE_CELL_EMPTY
    val isBlank = key == COMPOSITE_CELL_BLANK
    if (isBlank) {
        Box(modifier = modifier)
        return
    }
    if (isNone) {
        Box(
            modifier = modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                stringResource(Res.string.dashboard_composite_empty_label),
                fontSize = 10.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    val accent = metricAccentColor(key)
    val label = metricChipLabel(key, short = true).uppercase()
    val value = valueOf(key, stat)
    val showStat = stat != DashboardStat.CURRENT && stat != DashboardStat.NONE
    // Label gets the squeezable slot (weight 1f, fill = true) so it expands to
    // hold the leftover space after the value's natural width, that pins the
    // value flush right while still ellipsising the label if it grows too long.
    // Stat indicator (when present) sits as a small label INLINE with the
    // metric label, separated by a · so the row reads as "MAX · SPEED  42".
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showStat) {
            Text(
                statShortLabel(stat),
                fontSize = 8.sp,
                color = accent.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Picks the small bottom-right badge icon based on the rider's tap-action
 *  choice. None = no badge (tile is display-only). */
fun actionBadgeIcon(action: CustomTileAction): ImageVector? = when (action) {
    CustomTileAction.NONE -> null
    CustomTileAction.OPEN_URL -> Icons.Filled.Link
    CustomTileAction.SHOW_QR -> Icons.Filled.QrCode2
}

/**
 * Strips a URL down to a short domain hint suitable as a fallback tile
 * label. Drops scheme, "www.", and any path so "https://www.instagram.com/foo"
 * reads as "instagram.com". Returns the input verbatim when it doesn't look
 * like a URL the rider can recognise.
 */
fun extractDomainHint(url: String): String {
    if (url.isBlank()) return ""
    val noScheme = url
        .substringAfter("://", missingDelimiterValue = url)
        .substringAfter("@", missingDelimiterValue = url.substringAfter("://", url))
    val host = noScheme.substringBefore("/").substringBefore("?").substringBefore("#")
    val trimmed = host.removePrefix("www.").trim()
    return trimmed.ifBlank { url }
}

fun statShortLabel(stat: DashboardStat): String = when (stat) {
    DashboardStat.NONE, DashboardStat.EMPTY -> ""
    DashboardStat.CURRENT -> "NOW"
    DashboardStat.MIN -> "MIN"
    DashboardStat.MAX -> "MAX"
    DashboardStat.SUSTAINED_PEAK -> "S.PK"
    DashboardStat.AVG -> "AVG"
    DashboardStat.MEDIAN -> "MED"
    DashboardStat.P75 -> "P75"
    DashboardStat.P95 -> "P95"
    DashboardStat.P99 -> "P99"
}

/**
 * Accent palette for a metric, drives the value text + sparkline tint.
 * Reads from [com.eried.eucplanet.data.model.MetricCatalog]; unknown keys
 * (including the COMPOSITE_CELL_EMPTY sentinel) fall through to AccentBlue.
 */
fun metricAccentColor(key: String): androidx.compose.ui.graphics.Color =
    com.eried.eucplanet.data.model.MetricCatalog.byKey(key)?.accent ?: AccentBlue
