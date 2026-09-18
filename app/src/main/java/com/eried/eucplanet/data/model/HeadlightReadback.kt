package com.eried.eucplanet.data.model

/** A measured level. Null level means supported but unknown; null readback means unsupported. */
data class HeadlightReadback(val level: Level? = null, val receivedAtNanos: Long = 0L) {
    enum class Level { OFF, LOW, MEDIUM, HIGH }

    fun freshLevel(nowNanos: Long, maxAgeMs: Int): Level? =
        level.takeIf { nowNanos - receivedAtNanos in 0..maxAgeMs.toLong() * 1_000_000L }
}
