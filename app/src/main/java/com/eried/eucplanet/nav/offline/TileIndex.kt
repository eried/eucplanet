package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.OfflineRoutingSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A tile on the server: its name, size, build date and, from a mirror,
 *  its own download link and the square's offline map. */
data class RemoteTile(
    val name: String,
    val bytes: Long,
    val dateMs: Long,
    val url: String? = null,
    val map: RemoteMap? = null,
) {
    /** What a download of the square fetches: routing plus its map. */
    val totalBytes: Long get() = bytes + (map?.bytes ?: 0L)
}

/** A square's offline map on the mirror. */
data class RemoteMap(val url: String, val bytes: Long, val dateMs: Long)

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

        /** The [server] first, then each mirror, then brouter.de, each once. */
        fun fallbacks(server: String): List<TileSource> =
            listOf(server, OfflineRoutingSettings.MIRROR_URL, OfflineRoutingSettings.GITHUB_MIRROR_URL, OfflineRoutingSettings.BROUTER_URL)
                .map { of(it) }.distinctBy { it.base }

        /** Our mirrors, the ones with maps. */
        val mirrors: List<TileSource>
            get() = listOf(of(OfflineRoutingSettings.MIRROR_URL), of(OfflineRoutingSettings.GITHUB_MIRROR_URL))
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
            val map = t.optJSONObject("map")?.let { m ->
                val mu = m.optString("url")
                if (mu.startsWith("https://")) RemoteMap(mu, m.getLong("bytes"), m.getLong("date")) else null
            }
            RemoteTile(name, t.getLong("bytes"), t.getLong("date"), url, map)
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

    /** True when a downloaded square's map is missing or older than the
     *  mirror's. A routing-only square from before maps gets its map this way. */
    fun mapNeedsUpdate(route: File, map: File, remote: RemoteTile): Boolean =
        route.isFile && mapWanted(map, remote)

    /** True when the mirror has a map for the square and ours is missing or
     *  older, so a download or update fetches it; a current map stays. */
    fun mapWanted(map: File, remote: RemoteTile): Boolean {
        val m = remote.map ?: return false
        return !map.isFile || m.dateMs > map.lastModified() + DAY_MS
    }

    /** What a download of this square fetches: its routing when missing or
     *  older, plus its map when [mapWanted]. */
    fun fetchBytes(route: File, map: File, remote: RemoteTile): Long =
        (if (!route.isFile || needsUpdate(route, remote)) remote.bytes else 0L) +
            (if (mapWanted(map, remote)) remote.map?.bytes ?: 0L else 0L)

    /** A BRouter server's [tiles] with the maps the [mirror] has for the
     *  same squares. Routing still comes from the server; only our mirror
     *  has maps. */
    fun withMaps(tiles: List<RemoteTile>, mirror: List<RemoteTile>): List<RemoteTile> {
        val maps = mirror.mapNotNull { t -> t.map?.let { t.name to it } }.toMap()
        return tiles.map { t -> maps[t.name]?.let { t.copy(map = it) } ?: t }
    }

    private const val DAY_MS = 86_400_000L
}
