package com.eried.eucplanet.ui.studio

/**
 * Maps a replay cursor position to the video timestamp to show, real-time and
 * offset-aligned: [offsetMs] is the ride-time at which the clip's first frame
 * shows. Returns null when the cursor is outside the clip's covered span (before
 * the offset, or past offset + duration) - the caller renders that as nothing.
 */
fun videoTimeUsFor(cursorMs: Long, offsetMs: Long, videoDurationMs: Long): Long? {
    val into = cursorMs - offsetMs
    if (into < 0L || into > videoDurationMs) return null
    return into * 1000L
}

/**
 * Like [videoTimeUsFor] but applies the face's out-of-range [edge] behavior when
 * the cursor falls outside the clip window: "FREEZE" holds the nearest first/last
 * frame, "LOOP" wraps around within the clip, anything else (e.g. "BLACK") returns
 * null so the caller fills black or transparent. Returns null when there is no
 * clip length to seek into.
 */
fun edgeVideoTimeUs(cursorMs: Long, offsetMs: Long, videoDurationMs: Long, edge: String): Long? {
    if (videoDurationMs <= 0L) return null
    val into = cursorMs - offsetMs
    if (into in 0L..videoDurationMs) return into * 1000L
    return when (edge) {
        "FREEZE" -> into.coerceIn(0L, videoDurationMs) * 1000L
        "LOOP" -> (((into % videoDurationMs) + videoDurationMs) % videoDurationMs) * 1000L
        else -> null
    }
}
