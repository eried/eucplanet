package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.CustomBleCommand

// Pure "Restore slot" logic for the dashboard grids. The ViewModel's reset
// functions write what these return, and the slot sheets ask the matching
// isDefault check so the Restore button is enabled exactly when pressing it
// would change something. Both share one definition of "default" here.

/** Shipped action per slot: the default grid order leads, then the rest of the catalog. */
internal val DEFAULT_DASHBOARD_ACTIONS: List<String> by lazy {
    (AppSettings().dashboardActionOrder.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
        knownDashboardActionKeys()).distinct()
}

internal fun knownDashboardActionKeys(): List<String> =
    com.eried.eucplanet.data.model.ActionCatalog.keysFor(
        com.eried.eucplanet.data.model.ActionSurface.DASHBOARD
    )

internal fun parseDashboardJsonRoot(s: String): org.json.JSONObject =
    try { org.json.JSONObject(s.ifBlank { "{}" }) } catch (_: Exception) { org.json.JSONObject() }

/** Keys of a dashboard definition map whose value is an object (what the list* readers accept). */
private fun dashboardDefinitionIds(json: String): Set<String> {
    val root = parseDashboardJsonRoot(json)
    val out = LinkedHashSet<String>()
    for (id in root.keys()) if (root.optJSONObject(id) != null) out.add(id)
    return out
}

internal fun sanitizeDashboardOrder(
    saved: String,
    known: List<String>,
    dynamic: Set<String> = emptySet()
): List<String> {
    // EMPTY_SLOT_KEY entries are kept verbatim so "intentionally blank"
    // grid slots from a move-and-empty drop survive a round-trip through
    // settings persistence. Renderers map the sentinel to a blank Box.
    val s = saved.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() && (it == EMPTY_SLOT_KEY || it in known || it in dynamic) }
    // Append known keys that aren't already in the saved order so the pool
    // surfaces new defaults after an app upgrade. Dynamic IDs are NOT
    // auto-added, they only exist while the rider has them on the grid
    // or until they delete the underlying composite/group definition.
    return s + known.filter { it !in s }
}

internal fun parseDashboardSlotStats(s: String, key: String): MetricSlotStats {
    val root = parseDashboardJsonRoot(s)
    val n = root.optJSONObject(key) ?: return MetricSlotStats()
    fun stat(k: String, default: DashboardStat) =
        runCatching { DashboardStat.valueOf(n.optString(k, default.name)) }.getOrDefault(default)
    return MetricSlotStats(
        left = stat("l", DashboardStat.NONE),
        center = stat("c", DashboardStat.CURRENT),
        right = stat("r", DashboardStat.NONE),
        sparkline = n.optBoolean("spark", true)
    )
}

private fun metricOrderOf(s: AppSettings): List<String> = sanitizeDashboardOrder(
    s.dashboardMetricOrder,
    KNOWN_DASHBOARD_METRICS,
    dashboardDefinitionIds(s.dashboardCompositeMetrics) + dashboardDefinitionIds(s.dashboardCustomTiles)
)

private fun actionOrderOf(s: AppSettings): List<String> = sanitizeDashboardOrder(
    s.dashboardActionOrder,
    knownDashboardActionKeys(),
    dashboardDefinitionIds(s.dashboardActionGroups) + CustomBleCommand.parseAll(s.dashboardCustomBle).keys
)

/**
 * True when Restore on metric slot [slotIndex] would change nothing: the
 * shipped metric already sits there with its shipped corner stats and
 * sparkline. An index with no shipped metric has nothing to restore.
 */
internal fun isMetricSlotAtDefault(s: AppSettings, slotIndex: Int): Boolean {
    val naturalKey = KNOWN_DASHBOARD_METRICS.getOrNull(slotIndex) ?: return true
    val items = metricOrderOf(s)
    if (slotIndex !in items.indices) return true
    return items[slotIndex] == naturalKey &&
        parseDashboardSlotStats(s.dashboardMetricStats, naturalKey) == MetricSlotStats()
}

/** True when Restore on action slot [slotIndex] would change nothing. */
internal fun isActionSlotAtDefault(s: AppSettings, slotIndex: Int): Boolean {
    val naturalKey = DEFAULT_DASHBOARD_ACTIONS.getOrNull(slotIndex) ?: return true
    val items = actionOrderOf(s)
    if (slotIndex !in items.indices) return true
    return items[slotIndex] == naturalKey
}

/**
 * Restore the metric slot at [slotIndex] to the metric the app ships
 * there (`KNOWN_DASHBOARD_METRICS[slotIndex]`), regardless of what the
 * rider currently has in that slot. If the natural occupant lives in
 * a different position, swap so it returns home and the displaced
 * tile takes its place. Any dynamic tile (composite / custom-tile)
 * pushed past the active region by the swap is cleaned up the same
 * way the swap-via-drag path handles it. Null when there is no such slot.
 */
internal fun restoreDashboardMetricSlot(current: AppSettings, slotIndex: Int): AppSettings? {
    val items = metricOrderOf(current).toMutableList()
    val naturalKey = KNOWN_DASHBOARD_METRICS.getOrNull(slotIndex) ?: return null
    if (slotIndex !in items.indices) return null
    val currentOccupant = items[slotIndex]

    // Note: don't early-return when currentOccupant == naturalKey.
    // The rider may have customized corner stats (AVG / MIN / etc.)
    // on the natural occupant, and Reset still needs to wipe those
    // so the slot looks like a fresh install.

    val occupantIsDynamic = isCompositeMetricKey(currentOccupant) ||
        isCustomTileKey(currentOccupant)
    val needsReorder = currentOccupant != naturalKey

    if (needsReorder && occupantIsDynamic) {
        // Dynamic occupant (composite / custom tile) gets deleted, the
        // rider's "restore this slot" intent doesn't preserve dynamic
        // instances. Remove it from the order, then move the natural
        // metric into slotIndex (closing any gap).
        items.removeAt(slotIndex)
        val naturalIdx = items.indexOf(naturalKey)
        if (naturalIdx >= 0) items.removeAt(naturalIdx)
        items.add(slotIndex.coerceAtMost(items.size), naturalKey)
    } else if (needsReorder) {
        // Static occupant, swap with the natural metric's current
        // position so neither static metric is lost.
        val naturalIdx = items.indexOf(naturalKey)
        if (naturalIdx >= 0) {
            items[naturalIdx] = currentOccupant
            items[slotIndex] = naturalKey
        } else {
            items[slotIndex] = naturalKey
            items.add(currentOccupant)
        }
    }

    // Clean up: any dynamic tile sitting in the pool (past
    // activeCount) is implicitly orphaned, plus the one we just
    // deleted at slotIndex (if any). Drop them from the order and
    // their JSON definition map.
    val activeCount = ACTIVE_DASHBOARD_TILE_COUNT
    val orphanedComposites = items.withIndex()
        .filter { (idx, k) -> idx >= activeCount && isCompositeMetricKey(k) }
        .map { it.value }
        .toMutableSet()
    val orphanedCustomTiles = items.withIndex()
        .filter { (idx, k) -> idx >= activeCount && isCustomTileKey(k) }
        .map { it.value }
        .toMutableSet()
    if (occupantIsDynamic) {
        if (isCompositeMetricKey(currentOccupant)) orphanedComposites.add(currentOccupant)
        if (isCustomTileKey(currentOccupant)) orphanedCustomTiles.add(currentOccupant)
    }
    val toRemove = (orphanedComposites + orphanedCustomTiles)
    val cleaned = if (toRemove.isEmpty()) items else items.filter { it !in toRemove }
    val newComposites = if (orphanedComposites.isEmpty()) current.dashboardCompositeMetrics
        else parseDashboardJsonRoot(current.dashboardCompositeMetrics)
            .also { root -> orphanedComposites.forEach { root.remove(it) } }
            .toString()
    val newCustomTiles = if (orphanedCustomTiles.isEmpty()) current.dashboardCustomTiles
        else parseDashboardJsonRoot(current.dashboardCustomTiles)
            .also { root -> orphanedCustomTiles.forEach { root.remove(it) } }
            .toString()

    // Also wipe the natural key's per-slot stats so the restored
    // metric shows up with its shipped appearance (current value
    // in the center, no AVG / MIN / sparkline carry-over from the
    // slot it used to live in). Without this, BATTERY brings its
    // previously-configured corner stats along when it returns
    // home, which makes the "Default" button feel half-applied.
    val statsRoot = parseDashboardJsonRoot(current.dashboardMetricStats)
    statsRoot.remove(naturalKey)

    return current.copy(
        dashboardMetricOrder = cleaned.joinToString(","),
        dashboardCompositeMetrics = newComposites,
        dashboardCustomTiles = newCustomTiles,
        dashboardMetricStats = statsRoot.toString()
    )
}

/**
 * Restore the action slot at [slotIndex] to `DEFAULT_DASHBOARD_ACTIONS[slotIndex]`,
 * symmetric counterpart to [restoreDashboardMetricSlot]. Cleans up any
 * action-group definitions pushed past the active region.
 */
internal fun restoreDashboardActionSlot(current: AppSettings, slotIndex: Int): AppSettings? {
    val items = actionOrderOf(current).toMutableList()
    val naturalKey = DEFAULT_DASHBOARD_ACTIONS.getOrNull(slotIndex) ?: return null
    if (slotIndex !in items.indices) return null
    val currentOccupant = items[slotIndex]

    val occupantIsGroup = isActionGroupKey(currentOccupant)
    val needsReorder = currentOccupant != naturalKey

    if (needsReorder && occupantIsGroup) {
        items.removeAt(slotIndex)
        val naturalIdx = items.indexOf(naturalKey)
        if (naturalIdx >= 0) items.removeAt(naturalIdx)
        items.add(slotIndex.coerceAtMost(items.size), naturalKey)
    } else if (needsReorder) {
        val naturalIdx = items.indexOf(naturalKey)
        if (naturalIdx >= 0) {
            items[naturalIdx] = currentOccupant
            items[slotIndex] = naturalKey
        } else {
            items[slotIndex] = naturalKey
            items.add(currentOccupant)
        }
    }

    val activeCount = ACTIVE_DASHBOARD_TILE_COUNT
    val orphanedGroups = items.withIndex()
        .filter { (idx, k) -> idx >= activeCount && isActionGroupKey(k) }
        .map { it.value }
        .toMutableSet()
    if (occupantIsGroup) orphanedGroups.add(currentOccupant)

    val cleaned = if (orphanedGroups.isEmpty()) items else items.filter { it !in orphanedGroups }
    val newGroups = if (orphanedGroups.isEmpty()) current.dashboardActionGroups
        else parseDashboardJsonRoot(current.dashboardActionGroups)
            .also { root -> orphanedGroups.forEach { root.remove(it) } }
            .toString()

    return current.copy(
        dashboardActionOrder = cleaned.joinToString(","),
        dashboardActionGroups = newGroups
    )
}
