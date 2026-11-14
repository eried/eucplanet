package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.platform.Locale
import com.eried.eucplanet.platform.format

/**
 * The Navigator's GPX reader and writer for a phone without Android's
 * XmlPullParser (iOS). Reads and writes what Android's GpxIO does, rule for
 * rule: the same elements (`wpt`, `rtept`, `trkpt` by their bare names, any
 * case, `metadata` / `rte` / `trk` names for the route, `radius` anywhere
 * inside a stop), the same "Route" fallback name, and the same document out.
 * Android keeps GpxIO; [GpxTextTest] pins this one to its behaviour.
 *
 * A broken document (an unclosed or mismatched tag, an unknown entity, an
 * element inside a name) throws, as the pull parser does.
 */
object GpxText {

    fun parse(bytes: ByteArray): GpxData = parse(decode(bytes))

    fun parse(text: String): GpxData {
        val routePoints = ArrayList<Waypoint>()
        val places = ArrayList<Waypoint>()
        val track = ArrayList<GeoPoint>()
        var docName = ""

        var pendingLat = 0.0
        var pendingLng = 0.0
        var pendingName = ""
        var pendingRadius: Double? = null
        var nameTarget = ""

        val reader = Reader(text)
        while (true) {
            when (val ev = reader.next()) {
                null -> break
                is Event.Start -> when (ev.name.lowercase()) {
                    "wpt", "rtept", "trkpt" -> {
                        pendingLat = ev.attrs["lat"]?.toDoubleOrNull() ?: 0.0
                        pendingLng = ev.attrs["lon"]?.toDoubleOrNull() ?: 0.0
                        pendingName = ""
                        pendingRadius = null
                        nameTarget = ev.name.lowercase()
                    }
                    "metadata", "rte", "trk" -> nameTarget = ev.name.lowercase()
                    "name" -> {
                        val t = reader.textOf(ev).trim()
                        when (nameTarget) {
                            "metadata", "rte", "trk" -> if (docName.isEmpty()) docName = t
                            "wpt", "rtept", "trkpt" -> pendingName = t
                        }
                    }
                    "radius" -> {
                        val r = reader.textOf(ev).trim().toDoubleOrNull()
                        if (r != null && (nameTarget == "wpt" || nameTarget == "rtept")) pendingRadius = r
                    }
                }
                is Event.End -> when (ev.name.lowercase()) {
                    "wpt", "rtept" -> {
                        val w = Waypoint(pendingLat, pendingLng, pendingName, pendingRadius)
                        if (ev.name.equals("rtept", ignoreCase = true)) routePoints.add(w) else places.add(w)
                    }
                    "trkpt" -> track.add(GeoPoint(pendingLat, pendingLng))
                }
            }
        }
        return GpxData(docName.ifBlank { "Route" }, routePoints, places, track)
    }

    /** [route] as the GPX 1.1 document GpxIO.write puts out. */
    fun write(route: NavRoute): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"EUC Planet\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>${esc(route.name)}</name></metadata>\n")
        if (route.isLockedTrack) {
            route.waypoints.drop(1).dropLast(1).forEach { p ->
                append("  <wpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\">")
                if (p.name.isNotBlank()) append("<name>${esc(p.name)}</name>")
                append("</wpt>\n")
            }
            append("  <trk>\n    <name>${esc(route.name)}</name>\n    <trkseg>\n")
            route.track.forEach { p -> append("      <trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\"/>\n") }
            append("    </trkseg>\n  </trk>\n</gpx>\n")
            return@buildString
        }
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
            route.geometry.forEach { p -> append("      <trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lng)}\"/>\n") }
            append("    </trkseg>\n  </trk>\n")
        }
        append("</gpx>\n")
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * The file's text, in the encoding its byte order mark or XML declaration
     * names, as the pull parser picks it: UTF-8 unless told otherwise, UTF-16
     * by its mark, and the Latin ones byte for byte.
     */
    internal fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) {
            return bytes.decodeToString(3, bytes.size)
        }
        if (bytes.size >= 2 && ((b(0) == 0xFF && b(1) == 0xFE) || (b(0) == 0xFE && b(1) == 0xFF))) {
            val little = b(0) == 0xFF
            val sb = StringBuilder((bytes.size - 2) / 2)
            var i = 2
            while (i + 1 < bytes.size) {
                val unit = if (little) b(i) or (b(i + 1) shl 8) else (b(i) shl 8) or b(i + 1)
                sb.append(unit.toChar())
                i += 2
            }
            return sb.toString()
        }
        // The declaration is ASCII whatever follows it.
        val head = buildString { for (i in 0 until minOf(bytes.size, 200)) append(b(i).toChar()) }
        val encoding = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""")
            .find(head)?.groupValues?.get(1)?.lowercase()
        return when (encoding) {
            "iso-8859-1", "latin1", "latin-1", "us-ascii", "ascii", "windows-1252", "cp1252" ->
                buildString(bytes.size) { for (i in bytes.indices) append(b(i).toChar()) }
            else -> bytes.decodeToString()
        }
    }

    private sealed interface Event {
        val name: String
        class Start(override val name: String, val attrs: Map<String, String>, val empty: Boolean) : Event
        class End(override val name: String) : Event
    }

    /** A small XML tokenizer: elements, attributes, text, CDATA, comments, entities. */
    private class Reader(private val s: String) {
        private var i = 0
        private val open = ArrayList<String>()
        // A self-closing tag's end, handed out right after its start.
        private var pendingEnd: String? = null

        fun next(): Event? {
            pendingEnd?.let { pendingEnd = null; return Event.End(it) }
            while (i < s.length) {
                val lt = s.indexOf('<', i)
                if (lt < 0) {
                    i = s.length
                    break
                }
                i = lt
                when {
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<![CDATA[", i) -> skipPast("]]>")
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<!", i) -> skipDoctype()
                    s.startsWith("</", i) -> return endTag()
                    else -> return startTag()
                }
            }
            if (open.isNotEmpty()) throw IllegalArgumentException("unexpected end of document in <${open.last()}>")
            return null
        }

        /**
         * The text inside [start] up to its end tag, as XmlPullParser.nextText:
         * entities and CDATA read, an element inside refused. Consumes the end tag.
         */
        fun textOf(start: Event.Start): String {
            if (start.empty) {
                // Its end is the text's end, as nextText consumes it.
                pendingEnd = null
                return ""
            }
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unexpected end of document in <${start.name}>")
                when {
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i + 9)
                        if (end < 0) throw IllegalArgumentException("unterminated CDATA")
                        out.appendRange(s, i + 9, end)
                        i = end + 3
                    }
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("</", i) -> {
                        endTag()
                        return out.toString()
                    }
                    s[i] == '<' -> throw IllegalArgumentException("element inside <${start.name}>")
                    else -> {
                        val lt = s.indexOf('<', i).let { if (it < 0) s.length else it }
                        out.append(unescape(s.substring(i, lt)))
                        i = lt
                    }
                }
            }
        }

        private fun skipPast(marker: String) {
            val end = s.indexOf(marker, i + 2)
            if (end < 0) throw IllegalArgumentException("unterminated $marker")
            i = end + marker.length
        }

        /** `<!DOCTYPE ...>`, with an internal subset in brackets. */
        private fun skipDoctype() {
            var depth = 0
            var j = i + 2
            while (j < s.length) {
                when (s[j]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) {
                        i = j + 1
                        return
                    }
                }
                j++
            }
            throw IllegalArgumentException("unterminated DOCTYPE")
        }

        private fun endTag(): Event.End {
            val close = s.indexOf('>', i)
            if (close < 0) throw IllegalArgumentException("unterminated end tag")
            val name = s.substring(i + 2, close).trim()
            i = close + 1
            val expected = open.removeLastOrNull() ?: throw IllegalArgumentException("unexpected </$name>")
            if (expected != name) throw IllegalArgumentException("expected </$expected>, read </$name>")
            return Event.End(name)
        }

        private fun startTag(): Event.Start {
            var j = i + 1
            val nameStart = j
            while (j < s.length && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
            val name = s.substring(nameStart, j)
            if (name.isEmpty()) throw IllegalArgumentException("empty element name")
            val attrs = LinkedHashMap<String, String>()
            while (true) {
                while (j < s.length && s[j].isWhitespace()) j++
                if (j >= s.length) throw IllegalArgumentException("unterminated <$name>")
                if (s[j] == '>') {
                    j++
                    i = j
                    open.add(name)
                    return Event.Start(name, attrs, empty = false)
                }
                if (s.startsWith("/>", j)) {
                    // A self-closing element: its end comes next, never pushed.
                    i = j + 2
                    pendingEnd = name
                    return Event.Start(name, attrs, empty = true)
                }
                val keyStart = j
                while (j < s.length && s[j] != '=' && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                val key = s.substring(keyStart, j)
                while (j < s.length && s[j].isWhitespace()) j++
                if (j >= s.length || s[j] != '=') throw IllegalArgumentException("attribute $key without a value")
                j++
                while (j < s.length && s[j].isWhitespace()) j++
                val quote = s.getOrNull(j)
                if (quote != '"' && quote != '\'') throw IllegalArgumentException("unquoted attribute $key")
                val end = s.indexOf(quote, j + 1)
                if (end < 0) throw IllegalArgumentException("unterminated attribute $key")
                attrs[key] = unescape(s.substring(j + 1, end))
                j = end + 1
            }
        }

        private fun unescape(t: String): String {
            if ('&' !in t) return t
            val out = StringBuilder(t.length)
            var k = 0
            while (k < t.length) {
                val c = t[k]
                if (c != '&') {
                    out.append(c)
                    k++
                    continue
                }
                val semi = t.indexOf(';', k)
                if (semi < 0) throw IllegalArgumentException("unterminated entity")
                val ent = t.substring(k + 1, semi)
                when {
                    ent == "amp" -> out.append('&')
                    ent == "lt" -> out.append('<')
                    ent == "gt" -> out.append('>')
                    ent == "quot" -> out.append('"')
                    ent == "apos" -> out.append('\'')
                    ent.startsWith("#x") || ent.startsWith("#X") -> appendCode(out, ent.substring(2).toInt(16))
                    ent.startsWith("#") -> appendCode(out, ent.substring(1).toInt())
                    else -> throw IllegalArgumentException("unresolved entity &$ent;")
                }
                k = semi + 1
            }
            return out.toString()
        }

        private fun appendCode(out: StringBuilder, code: Int) {
            if (code < 0x10000) out.append(code.toChar())
            else {
                val v = code - 0x10000
                out.append((0xD800 + (v shr 10)).toChar())
                out.append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
    }
}
