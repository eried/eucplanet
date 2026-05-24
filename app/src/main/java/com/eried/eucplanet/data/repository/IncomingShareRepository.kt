package com.eried.eucplanet.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a pending "add this place as a stop" request handed off from
 * MainActivity (which received it via an Android Share / VIEW intent) to
 * the Navigator screen. The Navigator observes it on open, consumes it
 * (which clears the slot), and either drops a pin or surfaces a snackbar
 * if a navigation session is already running.
 *
 * Singleton scope so the request survives the trip from MainActivity's
 * onNewIntent through Compose Navigation into the RouteBuilderViewModel.
 */
@Singleton
class IncomingShareRepository @Inject constructor() {

    /**
     * A place hint extracted from the shared payload. Coordinates are
     * preferred when present; otherwise [query] gets fed into the
     * existing geocoder search flow.
     */
    data class Pending(
        val lat: Double? = null,
        val lng: Double? = null,
        val query: String? = null,
        val label: String? = null
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun offer(p: Pending) {
        _pending.value = p
    }

    /** Atomically read + clear so observers process exactly once. */
    fun take(): Pending? {
        val v = _pending.value
        _pending.value = null
        return v
    }

    companion object {
        /**
         * Best-effort parser for whatever Android handed us on a SEND /
         * VIEW intent. Looks for, in order:
         *   * a `geo:lat,lng[?q=...]` URI
         *   * an embedded `lat,lng` decimal pair (Google Maps URLs put
         *     these in `@lat,lng,zoom` or `q=lat,lng`)
         *   * a non-URL line of text usable as a search query (the first
         *     line of a Google Maps share is typically the place name)
         * Returns null if nothing usable was found.
         */
        fun parse(raw: String): Pending? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            // 1. Standard Android geo: URI -- geo:LAT,LNG or geo:0,0?q=LAT,LNG
            //    (or ?q=NAME if the source has no coords).
            if (text.startsWith("geo:", ignoreCase = true)) {
                // geo:0,0?q=lat,lng or geo:lat,lng[?q=...]
                val afterScheme = text.substring(4)
                // Try the q= query first since it's authoritative.
                val q = Regex("""[?&]q=([^&]+)""", RegexOption.IGNORE_CASE)
                    .find(afterScheme)?.groupValues?.get(1)
                if (q != null) {
                    val decoded = java.net.URLDecoder.decode(q, "UTF-8")
                    val coords = parseCoords(decoded)
                    if (coords != null) return Pending(lat = coords.first, lng = coords.second)
                    return Pending(query = decoded)
                }
                val coords = parseCoords(afterScheme.substringBefore('?'))
                if (coords != null) return Pending(lat = coords.first, lng = coords.second)
            }

            // 2. Any URL or text containing lat,lng. The pattern matches
            //    "@lat,lng", "q=lat,lng", or just "lat,lng" anywhere.
            val coordRegex = Regex(
                """(-?\d+(?:\.\d+)?)\s*[,/]\s*(-?\d+(?:\.\d+)?)"""
            )
            for (m in coordRegex.findAll(text)) {
                val a = m.groupValues[1].toDoubleOrNull()
                val b = m.groupValues[2].toDoubleOrNull()
                if (a != null && b != null &&
                    a in -90.0..90.0 && b in -180.0..180.0
                ) {
                    // Heuristic: skip obvious "0,0" placeholders that Google
                    // Maps drops into geo:0,0?q=... URIs.
                    if (a == 0.0 && b == 0.0) continue
                    return Pending(lat = a, lng = b)
                }
            }

            // 3. Fall back to the first non-URL line as a search query --
            //    Google Maps shares typically put the place name first,
            //    then address, then a goo.gl link.
            val nameLine = text.lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) }
            return if (!nameLine.isNullOrBlank()) Pending(query = nameLine) else null
        }

        private fun parseCoords(s: String): Pair<Double, Double>? {
            val m = Regex("""(-?\d+(?:\.\d+)?)\s*[,/]\s*(-?\d+(?:\.\d+)?)""").find(s) ?: return null
            val a = m.groupValues[1].toDoubleOrNull() ?: return null
            val b = m.groupValues[2].toDoubleOrNull() ?: return null
            if (a !in -90.0..90.0 || b !in -180.0..180.0) return null
            if (a == 0.0 && b == 0.0) return null
            return a to b
        }
    }
}
