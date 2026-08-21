package com.eried.eucplanet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the trip row says about a trip's backups.
 *
 * It used to say almost nothing: a green tick, drawn only once the backup
 * FOLDER had the trip, hardcoded green, and tapping it produced a message. A
 * failed backup drew no icon at all, so the one state a rider can act on was
 * the one state they could not see. Dropbox had no per-trip state whatsoever,
 * so a rider with only Dropbox linked never saw a thing.
 *
 * These pin the wiring; the states themselves are Compose and are checked on a
 * device.
 */
class BackupStatusIconTest {

    private val screen = File("src/main/java/com/eried/eucplanet/ui/recording/RecordingScreen.kt").readText()
    private val vm = File("src/main/java/com/eried/eucplanet/ui/recording/RecordingViewModel.kt").readText()
    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/DropboxSyncWorker.kt").readText()
    private val repo = File("src/main/java/com/eried/eucplanet/data/repository/TripRepository.kt").readText()

    @Test fun `renaming no longer announces itself with a toast`() {
        val rename = vm.substringAfter("fun renameTrip(").substringBefore("fun ")
        assertTrue("the rename toast is back", !rename.contains("_toasts.send"))
    }

    @Test fun `a renamed trip shows as backing up straight away`() {
        // Marked before the sync is even queued, so the row reacts to the
        // rename instead of going quiet until some worker gets to it.
        val resync = repo.substringAfter("private suspend fun resyncEditedTrip").take(900)
        assertTrue(resync.contains("setDropboxStatus(tripId, 1)"))
    }

    @Test fun `the worker records how each upload went`() {
        assertTrue("nothing is marked in flight", worker.contains("setDropboxStatusByName(name, 1, null)"))
        assertTrue("success is not recorded", worker.contains("setDropboxStatusByName(name, 2,"))
        assertTrue("failure is not recorded", worker.contains("setDropboxStatusByName(name, 3, null)"))
    }

    @Test fun `the icon reads both destinations`() {
        val icon = screen.substringAfter("private fun BackupStatusIcon").substringBefore("IconButton")
        assertTrue("the folder is ignored", icon.contains("trip.uploadStatus == 3"))
        assertTrue("Dropbox is ignored", icon.contains("trip.dropboxStatus == 3"))
        assertTrue("waiting is not shown", icon.contains("trip.dropboxStatus == 1"))
    }

    @Test fun `a failure can be tapped to try again`() {
        val body = screen.substringAfter("private fun BackupStatusIcon").substringBefore("\n}")
        assertTrue("tapping a failure only explains it", body.contains("if (failed || waiting) onRetry()"))
        assertTrue("there is no failed icon", body.contains("failed -> Icons.Default.CloudOff"))
        assertTrue("failure is not coloured as a problem",
            body.contains("failed -> MaterialTheme.appColors.statusDanger"))
    }

    @Test fun `the leaderboard no longer hides the backup state`() {
        // A trip on the leaderboard is exactly the kind a rider renames, and
        // the online icon used to win the only status slot.
        val row = screen.substringAfter("isRecording -> {}").substringBefore("// Tools.")
        assertTrue(row.contains("BackupStatusIcon(trip, folderConfigured, dropboxLinked)"))
    }

    @Test fun `nothing is claimed about a backup the rider never set up`() {
        val row = screen.substringAfter("isRecording -> {}").substringBefore("// Tools.")
        assertTrue(row.contains("(folderConfigured || dropboxLinked)"))
    }

    @Test fun `both messages exist in every locale`() {
        val res = File("src/main/res")
        val locales = res.listFiles()!!.filter {
            it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) &&
                File(it, "strings.xml").exists()
        }
        assertTrue("no locale folders found", locales.size > 5)
        val missing = locales.filter {
            val t = File(it, "strings.xml").readText()
            !t.contains("backup_status_pending") || !t.contains("backup_status_failed")
        }.map { it.name }
        assertEquals("locales missing the backup strings: $missing", emptyList<String>(), missing)
    }
}
