package com.eried.eucplanet.nav.offline

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A tile on the server: its name, size and build date. */
data class RemoteTile(val name: String, val bytes: Long, val dateMs: Long)

/** Reads the server's tile listing (an nginx or Apache folder page). */
object TileIndex {

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
