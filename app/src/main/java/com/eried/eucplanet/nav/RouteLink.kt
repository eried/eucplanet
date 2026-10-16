package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A route as it travels inside a QR code or a link: stops, mode, and a track only when there is no router to redraw it. */
data class SharedRoute(
    val name: String?,
    val mode: TravelMode,
    val stops: List<Waypoint>,
    /** Only for a route with no routed path (a track loaded from GPX); a routed one is redrawn by the receiver. */
    val track: List<GeoPoint> = emptyList(),
)

/**
 * A route packed into a link, so it can be shared as a QR code or a message
 * with no account and no storage behind it.
 *
 * `https://eucplanet.ried.no/route#1.<data>`: the data is compact JSON,
 * deflated and base64url'd. Stops and track are Google-polyline encoded at
 * 1e-5 degrees (about a metre). It stays in the fragment, so a browser never
 * sends it to the server; the web page decodes it in place.
 */
object RouteLinks {
    const val BASE = "https://eucplanet.ried.no/route"
    private const val VERSION = "1"

    /** What still scans comfortably off a phone screen at error correction M (a QR caps at 2331 bytes there). */
    const val MAX_LINK = 1200

    private val MODES = mapOf(
        TravelMode.CYCLING to "c", TravelMode.DRIVING to "d",
        TravelMode.WALKING to "w", TravelMode.STRAIGHT to "s",
    )

    /** The link, or null when even the simplest form of the route does not fit. */
    fun format(route: SharedRoute): String? {
        if (route.stops.isEmpty() && route.track.size < 2) return null
        if (route.track.size < 2) return link(route, emptyList()).takeIf { it.length <= MAX_LINK }
        // A track shrinks until it fits: at map scale a few metres of tolerance are invisible.
        var tolerance = 2.0
        while (tolerance <= 2000.0) {
            val candidate = link(route, simplify(route.track, tolerance))
            if (candidate.length <= MAX_LINK) return candidate
            tolerance *= 1.6
        }
        return null
    }

    fun parse(url: String): SharedRoute? {
        val text = url.trim()
        if (!text.startsWith(BASE)) return null
        // The shared link keeps the route in the fragment; the web page's
        // "Open in EUC Planet" hands it over as a path segment instead.
        val payload = when {
            '#' in text -> text.substringAfter('#')
            text.startsWith("$BASE/") -> text.removePrefix("$BASE/").substringBefore('?')
            else -> return null
        }.trimEnd('/')
        val version = payload.substringBefore('.', "")
        if (version != VERSION) return null
        return runCatching { decode(payload.substringAfter('.')) }.getOrNull()
    }

    private fun link(route: SharedRoute, track: List<GeoPoint>): String {
        val json = JSONObject()
        route.name?.takeIf { it.isNotBlank() }?.let { json.put("n", it) }
        json.put("m", MODES.getValue(route.mode))
        json.put("p", encodePolyline(route.stops.map { GeoPoint(it.lat, it.lng) }))
        val extras = JSONArray()
        route.stops.forEach { s ->
            extras.put(JSONArray().apply {
                put(s.name)
                s.radiusM?.let { put(it) }
            })
        }
        if (route.stops.any { it.name.isNotBlank() || it.radiusM != null }) json.put("s", extras)
        if (track.size >= 2) json.put("t", encodePolyline(track))
        return "$BASE#$VERSION.${b64(deflate(json.toString().toByteArray(Charsets.UTF_8)))}"
    }

    private fun decode(data: String): SharedRoute? {
        val json = JSONObject(String(inflate(unb64(data)), Charsets.UTF_8))
        val mode = MODES.entries.firstOrNull { it.value == json.optString("m") }?.key ?: TravelMode.CYCLING
        val points = decodePolyline(json.optString("p"))
        val extras = json.optJSONArray("s")
        val stops = points.mapIndexed { i, p ->
            val e = extras?.optJSONArray(i)
            Waypoint(
                lat = p.lat, lng = p.lng,
                name = e?.optString(0).orEmpty(),
                radiusM = e?.takeIf { it.length() > 1 }?.optDouble(1),
            )
        }
        val track = json.optString("t").takeIf { it.isNotEmpty() }?.let { decodePolyline(it) }.orEmpty()
        if (stops.isEmpty() && track.size < 2) return null
        return SharedRoute(json.optString("n").takeIf { it.isNotEmpty() }, mode, stops, track)
    }

    // --- Google polyline, precision 5 ---

    internal fun encodePolyline(points: List<GeoPoint>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        for (p in points) {
            val lat = Math.round(p.lat * 1e5)
            val lng = Math.round(p.lng * 1e5)
            enc(lat - lastLat, out); enc(lng - lastLng, out)
            lastLat = lat; lastLng = lng
        }
        return out.toString()
    }

    private fun enc(v: Long, out: StringBuilder) {
        var s = if (v < 0) (v shl 1).inv() else v shl 1
        while (s >= 0x20) {
            out.append(((0x20 or (s and 0x1f).toInt()) + 63).toChar())
            s = s shr 5
        }
        out.append((s.toInt() + 63).toChar())
    }

    internal fun decodePolyline(s: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var i = 0
        var lat = 0L
        var lng = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < s.length) {
            lat += next(); lng += next()
            out += GeoPoint(lat / 1e5, lng / 1e5)
        }
        return out
    }

    // --- Douglas-Peucker in metres, on a local flat projection ---

    internal fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val cosLat = Math.cos(Math.toRadians(points[0].lat))
        fun x(p: GeoPoint) = p.lng * 111_320.0 * cosLat
        fun y(p: GeoPoint) = p.lat * 110_574.0
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = x(points[a]); val ay = y(points[a]); val bx = x(points[b]); val by = y(points[b])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val px = x(points[i]) - ax; val py = y(points[i]) - ay
                val d = if (len2 == 0.0) Math.hypot(px, py) else Math.abs(px * dy - py * dx) / Math.sqrt(len2)
                if (d > worst) { worst = d; at = i }
            }
            if (worst > toleranceM) {
                keep[at] = true
                stack.addLast(a to at); stack.addLast(at to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(bytes); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inf = Inflater(true)
        inf.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 512 * 1024) break
        }
        inf.end()
        return out.toByteArray()
    }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)
}
