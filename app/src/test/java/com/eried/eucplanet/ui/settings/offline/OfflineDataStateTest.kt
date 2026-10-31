package com.eried.eucplanet.ui.settings.offline

import com.eried.eucplanet.nav.offline.RemoteTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each square on the offline data map shows one of four states. */
class OfflineDataStateTest {

    private val remote = listOf(
        RemoteTile("E10_N55", 64_806_390, 1000),
        RemoteTile("E10_N60", 26_000_000, 1000),
        RemoteTile("E5_N60", 30_000_000, 1000),
    )

    @Test fun eachSquare_getsItsState() {
        val t = OfflineDataState.tiles(
            downloaded = mapOf("E10_N55" to 64_806_390L, "E10_N60" to 25_000_000L),
            remote = remote,
            progress = mapOf("E5_N60" to 0.4f),
            outdated = setOf("E10_N60"),
        )
        assertEquals(TileStatus.DOWNLOADED, t.getValue("E10_N55").status)
        assertEquals(TileStatus.UPDATE, t.getValue("E10_N60").status)
        assertEquals(TileStatus.DOWNLOADING, t.getValue("E5_N60").status)
        assertEquals(0.4f, t.getValue("E5_N60").progress)
        assertEquals(30_000_000L, t.getValue("E5_N60").bytes)
    }

    @Test fun aServerTileNotHere_isAvailable() {
        val t = OfflineDataState.tiles(emptyMap(), remote, emptyMap(), emptySet())
        assertTrue(t.values.all { it.status == TileStatus.AVAILABLE })
        assertEquals(64_806_390L, t.getValue("E10_N55").bytes)
    }

    @Test fun noServerList_stillShowsWhatIsDownloaded() {
        val t = OfflineDataState.tiles(mapOf("E10_N55" to 10L), remote = null, emptyMap(), emptySet())
        assertEquals(setOf("E10_N55"), t.keys)
        assertEquals(TileStatus.DOWNLOADED, t.getValue("E10_N55").status)
        assertEquals(10L, t.getValue("E10_N55").bytes)
    }

    @Test fun aDownloadingUpdate_showsDownloading() {
        val t = OfflineDataState.tiles(
            mapOf("E10_N60" to 1L), remote, mapOf("E10_N60" to 0.1f), setOf("E10_N60"),
        )
        assertEquals(TileStatus.DOWNLOADING, t.getValue("E10_N60").status)
    }

    @Test fun bigDownload_onMobileData_asksFirst() {
        assertTrue(OfflineDataState.warnSize(250_000_000L, onWifi = false))
        assertEquals(false, OfflineDataState.warnSize(250_000_000L, onWifi = true))
        assertEquals(false, OfflineDataState.warnSize(150_000_000L, onWifi = false))
    }
}
