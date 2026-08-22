package com.eried.eucplanet.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory stream of wearable debug events for the Service Mode
 * "Wearables" tab. Watches only report input events (button presses, screen
 * taps with coordinates) while Service Mode is recording - the phone stamps
 * a diag flag into every state frame, and the watch sends nothing extra
 * when it is off. So this feed is empty for every rider who never opens
 * Service Mode, and costs them nothing.
 *
 * Ring-capped: diagnostics sessions are short and the tab is a live view,
 * not an archive - the full trail also lands in [DiagnosticsLogger] and
 * ships inside the diag.txt.
 */
object WearableDebugFeed {

    /** One reported wearable event. [source] is "garmin" or "wearos". */
    data class Entry(val atMs: Long, val source: String, val text: String)

    private const val MAX_ENTRIES = 250

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun push(source: String, text: String) {
        val e = Entry(System.currentTimeMillis(), source, text)
        _entries.value = (_entries.value + e).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
