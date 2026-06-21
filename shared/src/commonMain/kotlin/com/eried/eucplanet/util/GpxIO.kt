package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.Waypoint
import kotlin.math.abs
import kotlin.math.roundToLong

/** Parsed contents of a GPX file relevant to the Navigator. */
data class GpxData(
    val name: String,
    /** Route/waypoint pins (`<rtept>` or standalone `<wpt>`). */
    val waypoints: List<Waypoint>,
    /** Recorded track geometry (`<trkpt>`), if any. */
    val track: List<GeoPoint>,
)

/**
 * Minimal GPX 1.1 reader/writer for saving and loading Navigator routes.
 * Faithful port of Android's GpxIO — the platform XmlPullParser + streams are
 * replaced with String I/O (the shared FileStore reads/writes text) and a small
 * hand-rolled GPX scanner, since commonMain has no XML parser.
 *
 * On write we store the user's pins as `<rtept>` and the resolved geometry as a
 * `<trk>`, so re-loading can either re-route from the pins or fall back to the
 * fixed track when only a track is present.
 */
object GpxIO {

    fun parse(xml: String): GpxData {
        val waypoints = ArrayList<Waypoint>()
        val track = ArrayList<GeoPoint>()

        // <wpt|rtept ...> ... </wpt|rtept>  (paired form, may hold name/radius).
        val pairRe = Regex("<(wpt|rtept)\\b([^>]*)>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        for (m in pairRe.findAll(xml)) {
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            val lat = attr(attrs, "lat") ?: continue
            val lng = attr(attrs, "lon") ?: continue
            val name = innerTag(inner, "name")?.let { unesc(it.trim()) } ?: ""
            val radius = innerTag(inner, "radius")?.trim()?.toDoubleOrNull()
            waypoints.add(Waypoint(lat, lng, name, radius))
        }
        // Self-closing <wpt|rtept .../> (no children).
        val selfRe = Regex("<(wpt|rtept)\\b([^>]*?)/>", RegexOption.IGNORE_CASE)
        for (m in selfRe.findAll(xml)) {
            val attrs = m.groupValues[2]
            val lat = attr(attrs, "lat") ?: continue
            val lng = attr(attrs, "lon") ?: continue
            waypoints.add(Waypoint(lat, lng, "", null))
        }
        // <trkpt ...> (self-closing or paired; we only need the coordinates).
        val trkRe = Regex("<trkpt\\b([^>]*?)/?>", RegexOption.IGNORE_CASE)
        for (m in trkRe.findAll(xml)) {
            val attrs = m.groupValues[1]
            val lat = attr(attrs, "lat") ?: continue
            val lng = attr(attrs, "lon") ?: continue
            track.add(GeoPoint(lat, lng))
        }

        // Document name = first <name> that isn't inside a point element. Strip the
        // point blocks, then take the first remaining <name>.
        val stripped = xml
            .replace(pairRe, "")
            .replace(Regex("<trkpt\\b[\\s\\S]*?</trkpt>", RegexOption.IGNORE_CASE), "")
        val docName = innerTag(stripped, "name")?.let { unesc(it.trim()) }.orEmpty()

        return GpxData(docName.ifBlank { "Route" }, waypoints, track)
    }

    /** Serialises a [NavRoute] to a GPX 1.1 document string. */
    fun write(route: NavRoute): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"EUC Planet\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>${esc(route.name)}</name></metadata>\n")
        if (route.waypoints.isNotEmpty()) {
            append("  <rte>\n")
            append("    <name>${esc(route.name)}</name>\n")
            route.waypoints.forEach { p ->
                append("    <rtept lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\">")
                if (p.name.isNotBlank()) append("<name>${esc(p.name)}</name>")
                p.radiusM?.let { append("<extensions><radius>${fmt(it)}</radius></extensions>") }
                append("</rtept>\n")
            }
            append("  </rte>\n")
        }
        if (route.geometry.isNotEmpty()) {
            append("  <trk>\n    <name>${esc(route.name)}</name>\n    <trkseg>\n")
            route.geometry.forEach { p ->
                append("      <trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\"/>\n")
            }
            append("    </trkseg>\n  </trk>\n")
        }
        append("</gpx>\n")
    }

    private fun attr(attrs: String, key: String): Double? =
        Regex("$key\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun innerTag(xml: String, tag: String): String? =
        Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)

    /** Six-decimal fixed format without java's String.format (KMP-safe). */
    private fun fmt(v: Double): String {
        val neg = v < 0
        val scaled = (abs(v) * 1_000_000.0).roundToLong()
        val intPart = scaled / 1_000_000
        val frac = (scaled % 1_000_000).toString().padStart(6, '0')
        return (if (neg) "-" else "") + "$intPart.$frac"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unesc(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
}
