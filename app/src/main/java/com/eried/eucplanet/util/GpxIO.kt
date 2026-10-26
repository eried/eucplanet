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
    /** Route points (`<rtept>`): stops in riding order, for a router to join. */
    val routePoints: List<Waypoint>,
    /** Standalone `<wpt>` places. Our own older files used them as stops;
     *  other apps export bookmarks here, often many and far from the ride. */
    val places: List<Waypoint>,
    /** Track geometry (`<trkpt>`): the exact line, if any. */
    val track: List<GeoPoint>
) {
    /** What the file is to the Navigator. */
    sealed interface Contents {
        /** Stops for the router, as our own saves and plain stop lists are. */
        data class Stops(val stops: List<Waypoint>) : Contents
        /** A line to ride as drawn, locked, with the file's places. */
        data class Track(val track: List<GeoPoint>, val places: List<Waypoint>) : Contents
        data object Nothing : Contents
    }

    /**
     * Route points win: a file that names its stops wants them routed (our
     * own saves carry the solved line too, as a track, and stay editable).
     * Otherwise a track is the ride, whatever places come with it. Only a
     * file with neither treats its places as stops.
     */
    fun contents(): Contents = when {
        routePoints.isNotEmpty() -> Contents.Stops(routePoints)
        track.size >= 2 -> Contents.Track(track, places)
        places.isNotEmpty() -> Contents.Stops(places)
        else -> Contents.Nothing
    }
}

/**
 * Minimal GPX 1.1 reader/writer for saving and loading Navigator routes.
 * Uses the platform XML pull parser, no third-party dependency.
 *
 * On write we store the user's pins as `<rtept>` and the resolved geometry as
 * a `<trk>`, so re-loading re-routes from the pins. A locked track is written
 * as its `<trk>` with its places as `<wpt>`, so it reopens locked.
 */
object GpxIO {

    fun parse(input: InputStream): GpxData {
        val routePoints = ArrayList<Waypoint>()
        val places = ArrayList<Waypoint>()
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
        var pendingRadius: Double? = null
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
                        pendingRadius = null
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
                    "radius" -> {
                        val r = parser.nextText().trim().toDoubleOrNull()
                        if (r != null && (nameTarget == "wpt" || nameTarget == "rtept")) {
                            pendingRadius = r
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.US)) {
                    "wpt", "rtept" -> {
                        val w = Waypoint(pendingLat, pendingLng, pendingName, pendingRadius)
                        if (parser.name.equals("rtept", ignoreCase = true)) routePoints.add(w) else places.add(w)
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
        return GpxData(docName.ifBlank { "Route" }, routePoints, places, track)
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
            if (route.isLockedTrack) {
                // A locked track stays one: its line, and the places on it as
                // <wpt>. No <rte>, which would reopen it as editable stops.
                route.waypoints.drop(1).dropLast(1).forEach { p ->
                    w.write("  <wpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\">")
                    if (p.name.isNotBlank()) w.write("<name>${esc(p.name)}</name>")
                    w.write("</wpt>\n")
                }
                w.write("  <trk>\n    <name>${esc(route.name)}</name>\n    <trkseg>\n")
                route.track.forEach { p ->
                    w.write("      <trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\"/>\n")
                }
                w.write("    </trkseg>\n  </trk>\n</gpx>\n")
                return
            }
            if (route.waypoints.isNotEmpty()) {
                w.write("  <rte>\n")
                w.write("    <name>${esc(route.name)}</name>\n")
                route.waypoints.forEach { p ->
                    w.write("    <rtept lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\">")
                    if (p.name.isNotBlank()) w.write("<name>${esc(p.name)}</name>")
                    p.radiusM?.let {
                        w.write("<extensions><radius>${fmt(it)}</radius></extensions>")
                    }
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
