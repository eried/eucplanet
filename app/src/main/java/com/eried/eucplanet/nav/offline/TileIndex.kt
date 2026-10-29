package com.eried.eucplanet.nav.offline

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A tile on the server: its name, size, build date and, from a mirror,
 *  its own download link. */
data class RemoteTile(val name: String, val bytes: Long, val dateMs: Long, val url: String? = null)

/** Where tiles come from. An address ending in `.json` is a mirror's index
 *  (eucplanet-maps), which links every file; anything else is a BRouter
 *  server with its segments4 and profiles2 folders. */
class TileSource private constructor(val base: String, val isMirror: Boolean) {
    val listUrl: String get() = if (isMirror) base else "$base/segments4/"
    val lookupsUrl: String get() = "$base/profiles2/lookups.dat"
    fun tileUrl(name: String): String = "$base/segments4/$name.rd5"

    companion object {
        fun of(server: String): TileSource {
            val s = server.trim().trimEnd('/')
            return TileSource(s, s.endsWith(".json", ignoreCase = true))
        }
    }
}

/** A mirror's tiles and its tag table link. */
data class MirrorIndex(val tiles: List<RemoteTile>, val lookupsUrl: String)

/** Reads the server's tile listing (an nginx or Apache folder page). */
object TileIndex {

    /** A mirror's index.json, or null when it is not one or lists nothing. */
    fun parseMirror(json: String): MirrorIndex? = runCatching {
        val o = org.json.JSONObject(json)
        val lookups = o.getJSONObject("lookups").getString("url")
        val arr = o.getJSONArray("tiles")
        val tiles = (0 until arr.length()).mapNotNull { i ->
            val t = arr.getJSONObject(i)
            val name = t.optString("name")
            val url = t.optString("url")
            if (!RoutingTiles.isTileName(name) || !url.startsWith("https://")) return@mapNotNull null
            RemoteTile(name, t.getLong("bytes"), t.getLong("date"), url)
        }
        if (tiles.isEmpty() || !lookups.startsWith("https://")) null else MirrorIndex(tiles, lookups)
    }.getOrNull()

    private val ROW = Regex(
        """<a href="([EW]\d{1,3}_[NS]\d{1,2})\.rd5">[^<]*</a>\s+(\d{2}-\w{3}-\d{4} \d{2}:\d{2})\s+(\d+)"""
    )

    /** Every tile in [html]; empty for a page that is not a listing. */
    fun parseListing(html: String): List<RemoteTile> {
        // brouter.de notes its times are CET.
        val fmt = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Berlin")
        }
        return ROW.findAll(html).mapNotNull { m ->
            val date = runCatching { fmt.parse(m.groupValues[2])?.time }.getOrNull() ?: return@mapNotNull null
            RemoteTile(m.groupValues[1], m.groupValues[3].toLong(), date)
        }.toList()
    }

    /** True when the server has a newer build of a tile we have. A day of
     *  slack: the listing and the file dates can be a time zone apart. */
    fun needsUpdate(local: File, remote: RemoteTile): Boolean =
        local.isFile && remote.dateMs > local.lastModified() + DAY_MS

    private const val DAY_MS = 86_400_000L
}
