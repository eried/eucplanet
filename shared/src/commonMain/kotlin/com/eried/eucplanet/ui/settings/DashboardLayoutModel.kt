package com.eried.eucplanet.ui.settings

// The dashboard layout model the settings editor writes and the dashboard
// reads: tile kinds, slot keys and per-slot stats.

/**
 * Allowed rolling-window durations (seconds). Stored as a list so the dropdown
 * UI and the snap-to-nearest validation share a single source of truth.
 * Range covers a quick burst (30 s) up to a full commute hour.
 */
val ROLLING_WINDOW_PRESETS_SECONDS: List<Int> = listOf(
    30, 60, 120, 180, 300, 600, 900, 1800, 3600
)

const val ROLLING_WINDOW_DEFAULT_SECONDS: Int = 300

/**
 * Layouts a composite metric tile can take. The cell count drives how many
 * sub-metrics the rider picks in the edit sheet; the orientation drives how
 * those values stack inside a single grid tile.
 */
enum class CompositeLayout(val cellCount: Int) {
    /** Two cells stacked top/bottom, best for long values with units. */
    ROW2(2),
    /** Two cells side-by-side. */
    COL2(2),
    /** Three cells side-by-side. */
    COL3(3)
}

/**
 * Definition of a single composite metric instance. Stored in
 * [AppSettings.dashboardCompositeMetrics] as `{ id: { layout, cells } }`.
 * Sub-metric stats (min/max/avg) are intentionally NOT supported here, a
 * composite always shows current values for each sub-metric.
 */
data class MetricComposite(
    val layout: CompositeLayout = CompositeLayout.ROW2,
    val cells: List<String> = listOf("SPEED", "BATTERY"),
    /**
     * Per-cell stat selector, what each cell displays. Parallel to
     * [cells]. Defaults to [DashboardStat.CURRENT] (live value) so
     * existing composites and freshly-spawned ones look the same as
     * before. The rider can change it per cell in the composite edit
     * sheet (Now / Min / Max / Avg / Sustained peak / Median / P75 /
     * P95 / P99).
     */
    val cellStats: List<DashboardStat> = listOf(DashboardStat.CURRENT, DashboardStat.CURRENT, DashboardStat.CURRENT)
)

/**
 * Tap behaviour for a custom tile. NONE renders as a display-only label;
 * OPEN_URL launches the rider's URL in the default browser; SHOW_QR opens
 * a QR-code popup so other riders can scan and visit the same URL (the
 * Instagram-share use case).
 */
enum class CustomTileAction { NONE, OPEN_URL, SHOW_QR }

/**
 * Rider-defined dashboard tile: a chosen icon + text label, optionally
 * paired with a tap action (open URL in browser, or show QR code popup).
 * Lets the rider drop a contact card / social handle / club page onto the
 * dashboard alongside their telemetry. Stored in
 * [AppSettings.dashboardCustomTiles] keyed by `C:<uuid>`.
 */
data class CustomTile(
    val text: String = "",
    val icon: String = CUSTOM_TILE_DEFAULT_ICON,
    val action: CustomTileAction = CustomTileAction.NONE,
    val url: String = ""
)

/** Default icon for newly-spawned custom tiles. INFO (a filled "i") reads
 *  as "informational placeholder, configure me" -- friendlier than the
 *  outlined checkbox the old default used; "i" is a near-universal
 *  affordance for "this slot holds info, tap to configure". The rider
 *  picks a real icon when they edit the tile. */
const val CUSTOM_TILE_DEFAULT_ICON = "INFO"

/**
 * Definition of a single action group instance. Stored in
 * [AppSettings.dashboardActionGroups] as `{ id: { name, icon, actions } }`.
 * Up to 4 sub-actions; the rider can intentionally duplicate an action
 * (e.g. two `RECORD_TOGGLE` entries if they want it twice in the popover).
 * [icon] is a stable key from a curated set rendered by `groupIconFor`, * not a raw image vector, so the storage stays JSON-stable across icon-set
 * upgrades.
 */
data class ActionGroup(
    val name: String = "",
    val icon: String = GROUP_DEFAULT_ICON,
    val actions: List<String> = emptyList()
)

/** Default icon key for new action groups. Matches the "+ GROUP" template
 *  pill so the rider immediately recognises the freshly-spawned tile. */
const val GROUP_DEFAULT_ICON = "FOLDER"

/** Curated icon keys the rider can pick from in the group edit sheet and
 *  the custom-tile edit sheet. The list lives here (not in the screen) so
 *  the icon picker and the tile renderer share a single source of truth, *  adding a new entry shows up everywhere automatically. */
val GROUP_ICON_CHOICES: List<String> = listOf(
    "FOLDER", "STAR", "BOLT", "FAVORITE", "DASHBOARD",
    "EXTENSION", "TUNE", "WIDGETS", "APPS", "BUILD",
    "HOME", "PERSON", "SETTINGS", "SHIELD", "MAP",
    "PHOTO_CAMERA", "MUSIC_NOTE", "PHONE", "WIFI", "SEARCH",
    "SAVE", "SEND", "SHARE", "EDIT", "REFRESH",
    "DONE", "INFO", "WARNING", "NOTIFICATIONS", "LINK"
)

/** Number of active tiles in each dashboard grid (metrics + actions). The
 *  rider always picks 6 active items regardless of column count (2×3 vs 3×2
 *  is just a visual layout choice). VM logic uses this to decide whether a
 *  given order-list index lands in the active grid or in the pool. */
const val ACTIVE_DASHBOARD_TILE_COUNT = 6

/** Prefix on order-list entries that identifies a composite metric instance. */
const val COMPOSITE_METRIC_PREFIX = "M:"
/** Prefix on order-list entries that identifies an action group instance. */
const val ACTION_GROUP_PREFIX = "G:"
/** Pool template key for the "+ Stack" composite-metric source. */
const val COMPOSITE_TEMPLATE_KEY = "+M"
/** Pool template key for the "+ Group" action-group source. */
const val ACTION_GROUP_TEMPLATE_KEY = "+G"
/** Pool template key for the "+ BLE" custom-command source. */
const val CUSTOM_BLE_TEMPLATE_KEY = "+B"
/** Default icon key for a freshly-created custom BLE command. */
const val CUSTOM_BLE_DEFAULT_ICON = "BOLT"
/**
 * Prefix marking a composite cell value as rider-typed text rather than a
 * metric key. The string after the prefix is the literal text the cell
 * renders on the grid; blank content reads as "(empty)" faint placeholder.
 * Lets the rider drop a label like "Pack 1" into a stack tile next to the
 * actual battery reading.
 *
 * Examples:
 *  - "TEXT:" → empty text cell, shows "(empty)" placeholder
 *  - "TEXT:Right pack" → cell renders "Right pack"
 */
const val COMPOSITE_TEXT_PREFIX = "TEXT:"

/** Pseudo-metric for an unbound composite cell. Stored as the TEXT prefix
 *  with no content. Picker labels this as "(none)"; cell renders as the
 *  "–" dash glyph. When the rider picks this, the layout COLLAPSES (other
 *  populated cells share the freed space). */
const val COMPOSITE_CELL_EMPTY = COMPOSITE_TEXT_PREFIX

/** Pseudo-metric that reserves a composite cell slot without showing any
 *  glyph. Picker labels as "(empty)", cell renders as a blank space. The
 *  rider picks this when they want to keep the composite's column / row
 *  count visually intact (e.g. a COL3 tile with two reads + a deliberate
 *  blank stays a 3-column tile instead of collapsing into 2 halves). */
const val COMPOSITE_CELL_BLANK = "BLANK:"

/** Sentinel inserted into picker option lists to render a visual divider
 *  between groups (placeholders vs real options). Dropdowns recognise this
 *  and render a non-clickable HorizontalDivider in its place. */
const val PICKER_DIVIDER_SENTINEL = "__DIVIDER__"

/**
 * Sentinel key for an intentionally-blank top-level grid slot. Different from
 * COMPOSITE_CELL_EMPTY (which is the sub-cell placeholder inside a MULTI tile)
 *, this one occupies a row in dashboardMetricOrder / dashboardActionOrder
 * so positions are preserved when the rider drags a tile out of a slot with
 * "move + leave source empty" semantics. Renderers map this key to a blank
 * Box; the pool catalog is the source-of-truth for re-adding the metric.
 */
const val EMPTY_SLOT_KEY = "__EMPTY__"

/** Prefix on order-list entries that identifies a custom tile (rider-typed
 *  text + icon + optional tap action like launching a URL or showing a QR
 *  code). Lives in [AppSettings.dashboardCustomTiles] keyed by `C:<uuid>`. */
const val CUSTOM_TILE_PREFIX = "C:"
/** Pool template key for the "+ Custom" custom-tile source. */
const val CUSTOM_TILE_TEMPLATE_KEY = "+C"

fun isCompositeMetricKey(key: String): Boolean = key.startsWith(COMPOSITE_METRIC_PREFIX)
fun isActionGroupKey(key: String): Boolean = key.startsWith(ACTION_GROUP_PREFIX)
fun isCustomBleKey(key: String): Boolean =
    key.startsWith(com.eried.eucplanet.data.model.CustomBleCommand.ID_PREFIX)
fun isCustomTileKey(key: String): Boolean = key.startsWith(CUSTOM_TILE_PREFIX)
fun isTextCell(key: String): Boolean = key.startsWith(COMPOSITE_TEXT_PREFIX) || key == "EMPTY"
fun textCellContent(key: String): String = when {
    key == "EMPTY" -> ""
    key.startsWith(COMPOSITE_TEXT_PREFIX) -> key.removePrefix(COMPOSITE_TEXT_PREFIX)
    else -> ""
}
fun wrapAsTextCell(content: String): String = COMPOSITE_TEXT_PREFIX + content

// Stats are listed in dropdown order. SUSTAINED_PEAK sits next to MAX because
// it's a softer "Peak ignoring spikes shorter than 2s" companion, the same
// reading Inmotion shows as "Sustained peak". Percentiles ascend so the
// picker reads: None / Now / Min / Max / Sustained peak / Avg / Median (P50)
// / P75 / P95 / P99.
enum class DashboardStat {
    NONE, CURRENT, MIN, MAX, SUSTAINED_PEAK, AVG, MEDIAN, P75, P95, P99,
    /**
     * Visible "(empty)" placeholder. Different from NONE:
     *   NONE  -> "no stat here, collapse the tile's layout"
     *           (e.g. a single side badge re-flows to two halves)
     *   EMPTY -> "reserve this slot but show nothing"
     *           (keeps the tile in 3-zone layout with a blank corner)
     * Lets the rider preserve a tile's visual width when they want one
     * side stat without the auto-2-column re-flow.
     */
    EMPTY
}

enum class SlotCorner(val jsonKey: String) {
    LEFT("l"), CENTER("c"), RIGHT("r")
}

data class MetricSlotStats(
    val left: DashboardStat = DashboardStat.NONE,
    val center: DashboardStat = DashboardStat.CURRENT,
    val right: DashboardStat = DashboardStat.NONE,
    val sparkline: Boolean = true
) {
    fun stat(corner: SlotCorner): DashboardStat = when (corner) {
        SlotCorner.LEFT -> left
        SlotCorner.CENTER -> center
        SlotCorner.RIGHT -> right
    }

    /** True only when the rider hasn't customized any side reading. */
    val isDefault: Boolean
        get() = left == DashboardStat.NONE &&
            center == DashboardStat.CURRENT &&
            right == DashboardStat.NONE
}

sealed interface CloudEvent {
    data object FolderSet : CloudEvent
    data object FolderFailed : CloudEvent
    data object BackupSuccess : CloudEvent
    data class BackupNamedSuccess(val name: String) : CloudEvent
    data object BackupFailed : CloudEvent
    data class BackupExists(val name: String) : CloudEvent
    data object RestoreSuccess : CloudEvent
    data object RestoreFailed : CloudEvent
    data object UploadEnqueued : CloudEvent
    data object SyncNoFolder : CloudEvent
    data class SyncFinished(val count: Int) : CloudEvent
    /** A sync ran but nothing needed transferring (everything already backed up). */
    data object SyncUpToDate : CloudEvent

    /** Dropbox asked the account to slow down and kept asking, so the sync
     *  stopped partway. The rest is queued for the retry worker. */
    data class SyncRateLimited(val done: Int, val total: Int) : CloudEvent
    data object EucstatsNothingToSync : CloudEvent
    data class EucstatsSyncFinished(val count: Int) : CloudEvent
    /** A sync ran with trips to upload but every attempt failed (network down,
     *  server unreachable, etc.). The trip rows stay in pending / failed state
     *  so the icon and background worker keep their normal retry behaviour. */
    data object EucstatsSyncFailed : CloudEvent
    /** A DIFFERENT rider's recovery id is already in the sync folder; we left it
     *  untouched instead of overwriting it. */
    data object RiderIdConflict : CloudEvent
}
