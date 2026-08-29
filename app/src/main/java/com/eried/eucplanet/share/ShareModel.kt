package com.eried.eucplanet.share

import org.json.JSONObject

enum class IdentityMode { ANON, SESSION, PROFILE }

data class ShareStats(val speedKmh: Float, val batteryPct: Int, val tempC: Float)

/** The decrypted per-rider message. v:1. Field names are the wire contract shared with the web viewer. */
data class SharePayload(
    val id: String, val name: String, val mode: IdentityMode, val color: String,
    val icon: String?, val avatarUrl: String?, val flag: String?,
    val lat: Double, val lng: Double, val heading: Float?, val t: Long, val stats: ShareStats?,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("id", id); put("name", name); put("mode", mode.name); put("color", color)
        icon?.let { put("icon", it) }; avatarUrl?.let { put("avatarUrl", it) }; flag?.let { put("flag", it) }
        put("lat", lat); put("lng", lng); heading?.let { put("heading", it.toDouble()) }; put("t", t)
        stats?.let { s -> put("stats", JSONObject().apply {
            put("speedKmh", s.speedKmh.toDouble()); put("batteryPct", s.batteryPct); put("tempC", s.tempC.toDouble()) }) }
    }.toString()

    companion object {
        fun fromJson(s: String): SharePayload? = runCatching {
            val j = JSONObject(s)
            if (j.optInt("v", -1) != 1) return null
            val st = j.optJSONObject("stats")?.let {
                ShareStats(it.getDouble("speedKmh").toFloat(), it.getInt("batteryPct"), it.getDouble("tempC").toFloat()) }
            SharePayload(
                id = j.getString("id"), name = j.getString("name"),
                mode = IdentityMode.valueOf(j.getString("mode")), color = j.getString("color"),
                icon = j.optString("icon").takeIf { j.has("icon") },
                avatarUrl = j.optString("avatarUrl").takeIf { j.has("avatarUrl") },
                flag = j.optString("flag").takeIf { j.has("flag") },
                lat = j.getDouble("lat"), lng = j.getDouble("lng"),
                heading = if (j.has("heading")) j.getDouble("heading").toFloat() else null,
                t = j.getLong("t"), stats = st,
            )
        }.getOrNull()
    }
}

/** Fixed, shared with the web viewer byte for byte. Assigned by join order. */
object PeerPalette {
    val COLORS = listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00", "#8E24AA", "#00ACC1",
        "#F4511E", "#3949AB", "#7CB342", "#D81B60", "#00897B", "#FDD835")
    fun colorFor(joinIndex: Int): String = COLORS[Math.floorMod(joinIndex, COLORS.size)]
}

enum class Freshness { FRESH, STALE, LOST }

/** Same thresholds in the app and the web viewer. Constants, not settings. */
object Staleness {
    const val FRESH_MS = 15_000L
    const val STALE_MS = 120_000L
    fun of(ageMs: Long): Freshness = when {
        ageMs < FRESH_MS -> Freshness.FRESH
        ageMs < STALE_MS -> Freshness.STALE
        else -> Freshness.LOST
    }
}

data class TrailPoint(val lat: Double, val lng: Double, val t: Long, val alpha: Float)

/**
 * Ring of recent positions; alpha fades 1.0 (newest) to 0.15 (oldest kept).
 *
 * Threading contract: add() and points() may both be called from ANY thread,
 * and in practice are called from two at once - the relay's WebSocket reader
 * appends as frames land, while the map redraw prunes and reads once a second.
 * ArrayDeque is not thread-safe, and an interleaved addLast / removeFirst does
 * not merely throw: it can leave the internal indices inconsistent for every
 * later call. Both operations therefore hold the same lock, and points()
 * returns a fresh list so no caller ever iterates the deque itself.
 */
class Trail(private val maxAgeMs: Long) {
    private val pts = ArrayDeque<Triple<Double, Double, Long>>()
    private val lock = Any()
    fun add(lat: Double, lng: Double, t: Long) {
        synchronized(lock) { pts.addLast(Triple(lat, lng, t)) }
    }
    fun points(now: Long): List<TrailPoint> = synchronized(lock) {
        while (pts.isNotEmpty() && now - pts.first().third > maxAgeMs) pts.removeFirst()
        val n = pts.size
        pts.mapIndexed { i, (lat, lng, t) ->
            val frac = if (n <= 1) 1f else i.toFloat() / (n - 1)      // 0 oldest .. 1 newest
            TrailPoint(lat, lng, t, 0.15f + 0.85f * frac)
        }
    }
}
