package com.eried.eucplanet.util

import android.util.Xml
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.Waypoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** Parsed contents of a GPX file relevant to the Navigator. */
data class GpxData(
    val name: String,
    /** Route/waypoint pins (`<rtept>` or standalone `<wpt>`). */
    val waypoints: List<Waypoint>,
    /** Recorded track geometry (`<trkpt>`), if any. */
    val track: List<GeoPoint>
)

/**
 * Minimal GPX 1.1 reader/writer for saving and loading Navigator routes.
 * Uses the platform XML pull parser — no third-party dependency.
 *
 * On write we store the user's pins as `<rtept>` and the resolved geometry as
 * a `<trk>`, so re-loading can either re-route from the pins or fall back to
 * the fixed track when only a track is present (e.g. a GPX exported elsewhere).
 */
object GpxIO {

    fun parse(input: InputStream): GpxData {
        val waypoints = ArrayList<Waypoint>()
        val track = ArrayList<GeoPoint>()
        var docName = ""

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        // Pending point being assembled (lat/lng known, name may follow).
        var pendingLat = 0.0
        var pendingLng = 0.0
        var pendingName = ""
        var pendingKind = ""   // "wpt", "rtept", "trkpt"
        var nameTarget = ""    // which element a following <name> belongs to

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.US)) {
                    "wpt", "rtept", "trkpt" -> {
                        pendingKind = parser.name.lowercase(Locale.US)
                        pendingLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        pendingLng = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        pendingName = ""
                        nameTarget = pendingKind
                    }
                    "metadata", "rte", "trk" -> nameTarget = parser.name.lowercase(Locale.US)
                    "name" -> {
                        val text = parser.nextText().trim()
                        when (nameTarget) {
                            "metadata", "rte", "trk" -> if (docName.isEmpty()) docName = text
                            "wpt", "rtept", "trkpt" -> pendingName = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.US)) {
                    "wpt", "rtept" -> {
                        waypoints.add(Waypoint(pendingLat, pendingLng, pendingName))
                        pendingKind = ""
                    }
                    "trkpt" -> {
                        track.add(GeoPoint(pendingLat, pendingLng))
                        pendingKind = ""
                    }
                }
            }
            event = parser.next()
        }
        return GpxData(docName.ifBlank { "Route" }, waypoints, track)
    }

    /** Serialises a [NavRoute] to a GPX 1.1 document. */
    fun write(route: NavRoute, output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write(
                "<gpx version=\"1.1\" creator=\"EUC Planet\" " +
                    "xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
            )
            w.write("  <metadata><name>${esc(route.name)}</name></metadata>\n")
            if (route.waypoints.isNotEmpty()) {
                w.write("  <rte>\n")
                w.write("    <name>${esc(route.name)}</name>\n")
                route.waypoints.forEach { p ->
                    w.write("    <rtept lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\">")
                    if (p.name.isNotBlank()) w.write("<name>${esc(p.name)}</name>")
                    w.write("</rtept>\n")
                }
                w.write("  </rte>\n")
            }
            if (route.geometry.isNotEmpty()) {
                w.write("  <trk>\n    <name>${esc(route.name)}</name>\n    <trkseg>\n")
                route.geometry.forEach { p ->
                    w.write("      <trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\"/>\n")
                }
                w.write("    </trkseg>\n  </trk>\n")
            }
            w.write("</gpx>\n")
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
