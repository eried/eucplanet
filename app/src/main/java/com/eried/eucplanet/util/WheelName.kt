package com.eried.eucplanet.util

/**
 * Turns a raw BLE advertised name into something presentable for the UI.
 *
 * Wheels advertise as `<name>-<serial>` (e.g. "adventure-49271"); the numeric
 * tail is a per-unit serial and only adds noise. This drops a trailing
 * separator + 3-or-more-digit run and capitalises an all-lowercase result.
 * Model identifiers such as V14, EX30 or KS-16X are left intact — their digits
 * have no separator in front, so the pattern never matches them.
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
    val stripped = core.replace(Regex("""[\s_-]+\d{3,}$"""), "").trim()
    val base = stripped.ifEmpty { core }
    val pretty = if (base == base.lowercase()) {
        base.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase() }
        }
    } else {
        base
    }
    return if (isVirtual) "$pretty$virtualTag" else pretty
}
