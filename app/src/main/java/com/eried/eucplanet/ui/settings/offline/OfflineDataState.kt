package com.eried.eucplanet.ui.settings.offline

import com.eried.eucplanet.nav.offline.RemoteTile

enum class TileStatus { DOWNLOADED, UPDATE, DOWNLOADING, AVAILABLE }

/** One square on the offline data map. [bytes] is the file on the phone
 *  when downloaded, else the server's size. */
data class TileRow(val name: String, val status: TileStatus, val bytes: Long, val progress: Float = 0f)

data class OfflineDataState(
    val tiles: Map<String, TileRow> = emptyMap(),
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    /** The server's list could not be fetched; only downloaded squares show. */
    val listError: Boolean = false,
    val loading: Boolean = true,
) {
    val outdatedCount: Int get() = tiles.values.count { it.status == TileStatus.UPDATE }

    companion object {
        /** Above this, a download on mobile data asks first. */
        const val WARN_BYTES = 200_000_000L

        fun warnSize(bytes: Long, onWifi: Boolean): Boolean = !onWifi && bytes > WARN_BYTES

        fun tiles(
            downloaded: Map<String, Long>,
            remote: List<RemoteTile>?,
            progress: Map<String, Float>,
            outdated: Set<String>,
        ): Map<String, TileRow> {
            val sizes = remote?.associate { it.name to it.bytes }.orEmpty()
            val names = downloaded.keys + sizes.keys + progress.keys
            return names.associateWith { name ->
                val status = when {
                    name in progress -> TileStatus.DOWNLOADING
                    name in outdated -> TileStatus.UPDATE
                    name in downloaded -> TileStatus.DOWNLOADED
                    else -> TileStatus.AVAILABLE
                }
                val bytes = if (status == TileStatus.DOWNLOADED || status == TileStatus.UPDATE)
                    downloaded.getValue(name) else sizes[name] ?: downloaded[name] ?: 0L
                TileRow(name, status, bytes, progress[name] ?: 0f)
            }
        }
    }
}
