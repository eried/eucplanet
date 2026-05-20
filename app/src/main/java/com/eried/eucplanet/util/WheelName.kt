package com.eried.eucplanet.util

/**
 * Turns a raw BLE advertised name into something presentable for the UI.
 *
 * Wheels advertise as `<name>-<serial>` (e.g. "Adventure-E0000298",
 * "adventure-49271"); the serial tail is a per-unit identifier and only adds
 * noise. This drops the token after the last separator when it looks like a
 * serial — at least 4 characters and mostly digits — and capitalises an
 * all-lowercase result. Short model suffixes (V14, 50S, 16X) are too short or
 * have too few digits to match, so they're kept.
 *
 * The " (virtual)" tag that BleConnectionManager appends for simulator wheels
 * is preserved. Returns null for a null/blank input.
 *
 * This is only a fallback: prefer the adapter-detected model name when one is
 * available, and fall back to this cleaned BLE name otherwise.
 */
fun prettyWheelName(raw: String?): String? {
    val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val virtualTag = " (virtual)"
    val isVirtual = name.endsWith(virtualTag)
    val core = if (isVirtual) name.removeSuffix(virtualTag).trim() else name

    // Drop a trailing per-unit serial: the token after the last separator,
    // when it is at least 4 chars and has 3+ digits (e.g. "-E0000298",
    // "-49271", "-1234567"). The digit floor keeps real name words ("Master",
    // "Sherman") and short model suffixes ("V14", "50S", "16X").
    val sep = core.lastIndexOfAny(charArrayOf('-', '_', ' '))
    val base = if (sep > 0) {
        val tail = core.substring(sep + 1)
        if (tail.length >= 4 && tail.count { it.isDigit() } >= 3) {
            core.substring(0, sep).trim()
        } else {
            core
        }
    } else {
        core
    }

    val cleaned = base.ifEmpty { core }
    val pretty = if (cleaned == cleaned.lowercase()) {
        cleaned.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase() }
        }
    } else {
        cleaned
    }
    return if (isVirtual) "$pretty$virtualTag" else pretty
}
