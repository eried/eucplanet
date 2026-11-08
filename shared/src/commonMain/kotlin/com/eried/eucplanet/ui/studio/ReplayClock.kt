package com.eried.eucplanet.ui.studio

import androidx.compose.ui.graphics.Path
import com.eried.eucplanet.platform.format

/** mm:ss for a millisecond offset, used by the replay timeline labels. */
fun formatReplayClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Parse a typed clock back into milliseconds, the inverse of [formatReplayClock]
 * for the trim editor. Accepts "SS", "M:SS" / "MM:SS" (the displayed form, where
 * minutes may exceed 59) and "H:MM:SS". Returns null if it isn't a valid clock.
 */
fun parseReplayClock(text: String): Long? {
    val parts = text.trim().split(":")
    if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() }) return null
    val nums = parts.map { it.trim().toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    val seconds = when (nums.size) {
        1 -> nums[0]
        2 -> nums[0] * 60 + nums[1]
        else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
    }
    return seconds * 1000
}

/**
 * A timeline handle: a 5-sided "home plate" with a square body and a triangular
 * point. The point sits on the track at [x]; [down] true points it downward.
 */
fun handlePentagon(x: Float, trackY: Float, down: Boolean): Path {
    val hw = 16f          // half width, visual only; the drag hitbox is the
                          // whole top/bottom half of the track, not this shape
    val point = 18f       // height of the pointed part
    val total = 42f       // full height (point + square body)
    val s = if (down) -1f else 1f
    return Path().apply {
        moveTo(x, trackY)
        lineTo(x + hw, trackY + s * point)
        lineTo(x + hw, trackY + s * total)
        lineTo(x - hw, trackY + s * total)
        lineTo(x - hw, trackY + s * point)
        close()
    }
}
