package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicUploadsTest {

    @Test
    fun `a rider with nothing set up gets no periodic pass at all`() {
        val plan = PeriodicUploads.of(AppSettings())
        assertFalse(plan.folder)
        assertFalse(plan.leaderboard)
        assertFalse(plan.dropbox)
    }

    @Test
    fun `each destination is scheduled once it is set up, and only that one`() {
        val folder = PeriodicUploads.of(AppSettings(syncFolderUri = "content://tree/backup"))
        assertTrue(folder.folder)
        assertFalse(folder.leaderboard)
        assertFalse(folder.dropbox)

        val board = PeriodicUploads.of(AppSettings(onlineUploadEnabled = true))
        assertTrue(board.leaderboard)
        assertFalse(board.folder)

        val dropbox = PeriodicUploads.of(AppSettings(dropboxAccessToken = "token"))
        assertTrue(dropbox.dropbox)
        assertFalse(dropbox.folder)
    }

    @Test
    fun `a blank Dropbox token is not a link`() {
        assertFalse(PeriodicUploads.of(AppSettings(dropboxAccessToken = "   ")).dropbox)
    }

    @Test
    fun `the interval follows the setting`() {
        assertEquals(AppSettings().pendingUploadIntervalMin, PeriodicUploads.of(AppSettings()).intervalMin)
    }
}
