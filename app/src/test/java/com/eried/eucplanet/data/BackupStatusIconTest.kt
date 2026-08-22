package com.eried.eucplanet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The trip row's one status icon, and the path that feeds it.
 *
 * History, because both halves were rebuilt on rider feedback. Renaming used
 * to post a toast and the row said nothing: the cloud appeared only for a trip
 * already in the backup folder, hardcoded green, a failed backup drew no icon
 * at all, and Dropbox had no per-trip state. The first fix added a second
 * cloud beside the leaderboard's, which was wrong the other way - a control
 * per destination instead of an answer. One icon now, and its message carries
 * the detail: backup time and leaderboard verdict together.
 */
class BackupStatusIconTest {

    private val screen = File("src/main/java/com/eried/eucplanet/ui/recording/RecordingScreen.kt").readText()
    private val vm = File("src/main/java/com/eried/eucplanet/ui/recording/RecordingViewModel.kt").readText()
    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/DropboxSyncWorker.kt").readText()
    private val repo = File("src/main/java/com/eried/eucplanet/data/repository/TripRepository.kt").readText()
    private val sync = File("src/main/java/com/eried/eucplanet/data/sync/SyncManager.kt").readText()

    @Test fun `renaming no longer announces itself with a toast`() {
        val rename = vm.substringAfter("fun renameTrip(").substringBefore("fun ")
        assertTrue("the rename toast is back", !rename.contains("_toasts.send"))
    }

    @Test fun `a renamed trip shows as backing up straight away`() {
        val resync = repo.substringAfter("private suspend fun resyncEditedTrip").take(1400)
        assertTrue(resync.contains("setDropboxStatus(tripId, 1)"))
        assertTrue(resync.contains("markPendingFolderUpload(tripId)"))
    }

    @Test fun `an edit uploads now, with the workers as the retry net`() {
        // WorkManager sat on a rename for minutes with the network up while
        // the row said "Backing up" - seen on a real Pixel, JobScheduler
        // holding the job on an unsatisfied network bit. The rider watching
        // the row gets an in-process upload; the workers keep the failures.
        val resync = repo.substringAfter("private suspend fun resyncEditedTrip").take(1400)
        assertTrue("edits are handed back to WorkManager alone",
            resync.contains("pushEditedTripNow"))
        val push = sync.substringAfter("fun pushEditedTripNow").take(2400)
        assertTrue("a failed direct Dropbox push is not handed to the retry worker",
            push.contains("scheduleDropboxSyncAttempt(1)"))
        assertTrue("a failed direct folder push is not handed to the retry worker",
            push.contains("scheduleTripUploadAttempt(1)"))
        assertTrue("the direct push skips the folder pass lock",
            push.contains("withUploadPass"))
    }

    @Test fun `the worker records how each upload went`() {
        assertTrue("nothing is marked in flight", worker.contains("setDropboxStatusByName(name, 1, null)"))
        assertTrue("success is not recorded", worker.contains("setDropboxStatusByName(name, 2,"))
        assertTrue("failure is not recorded", worker.contains("setDropboxStatusByName(name, 3, null)"))
    }

    @Test fun `one icon, not one per destination`() {
        val row = screen.substringAfter("isRecording -> {}").substringBefore("// Tools.")
        assertTrue(row.contains("TripStatusIcon"))
        assertTrue("a second icon slot crept back in",
            !screen.contains("fun OnlineStatusIcon") && !screen.contains("fun BackupStatusIcon"))
    }

    @Test fun `the icon reads both backup destinations`() {
        val icon = screen.substringAfter("private fun TripStatusIcon").substringBefore("IconButton")
        assertTrue("the folder is ignored", icon.contains("trip.uploadStatus == 3"))
        assertTrue("Dropbox is ignored", icon.contains("trip.dropboxStatus == 3"))
        assertTrue("waiting is not shown", icon.contains("trip.dropboxStatus == 1"))
    }

    @Test fun `only the backups pick the color`() {
        // A months-old leaderboard "held for review" tinted whole pages of
        // properly backed-up trips orange. The cloud answers "is this ride
        // safe": red and orange belong to the backups alone, and the
        // leaderboard speaks only in the message and the tap.
        val body = screen.substringAfter("private fun TripStatusIcon")
        assertTrue("there is no failed icon", body.contains("backupFailed -> Icons.Default.CloudOff"))
        assertTrue("failure is not coloured as a problem",
            body.contains("backupFailed -> MaterialTheme.appColors.statusDanger"))
        val icons = body.substringAfter("val icon = when {").substringBefore("}")
        val tints = body.substringAfter("val tint = when {").substringBefore("}")
        assertTrue("the leaderboard state still drives the icon",
            !icons.contains("flagged") && !icons.contains("online") &&
            !tints.contains("flagged") && !tints.contains("online"))
    }

    @Test fun `a trip restored from Dropbox reads as backed up, not as waiting`() {
        // uploadStatus 4 means it CAME from a backup. The folder mirror
        // catching up quietly is not something to warn the rider about.
        val body = screen.substringAfter("private fun TripStatusIcon")
        val waiting = body.substringAfter("val backupWaiting =").substringBefore("val backupAt")
        assertTrue("status 4 counts as an upload in flight", !waiting.contains("== 4"))
        val held = body.substringAfter("val backupHeld =").substringBefore("// The leaderboard")
        assertTrue("status 4 does not count as held by a backup", held.contains("trip.uploadStatus == 4"))
    }

    @Test fun `the tap still acts on what the rider can fix`() {
        val body = screen.substringAfter("private fun TripStatusIcon")
        assertTrue(body.contains("backupFailed || backupWaiting -> onRetryBackup()"))
        assertTrue("a held trip can no longer be rechecked",
            body.contains("flagged -> { onRecheckOnline(); showSnackbarLocal(snackbar, scope, msg) }"))
    }

    @Test fun `the message tells the whole story, parts joined`() {
        val body = screen.substringAfter("private fun TripStatusIcon").substringBefore("IconButton")
        assertTrue(body.contains("parts.joinToString"))
        assertTrue("the leaderboard verdict is missing", body.contains("online_status_shared"))
        assertTrue("the backup time is missing", body.contains("cloud_uploaded_on"))
    }

    @Test fun `nothing is claimed about a destination the rider never set up`() {
        val body = screen.substringAfter("private fun TripStatusIcon").substringBefore("IconButton")
        assertTrue(body.contains("folderConfigured && "))
        assertTrue(body.contains("dropboxLinked && "))
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

    @Test fun `green is only shown when a backup holds the trip`() {
        // The first cut fell through to green whenever nothing was failing or
        // uploading, which put a green cloud on trips whose own tap message
        // said "Not backed up yet".
        val body = screen.substringAfter("private fun TripStatusIcon")
        assertTrue(body.contains("backupHeld -> Icons.Default.CloudDone"))
        assertTrue("an unbacked trip still reads green",
            body.substringAfter("val icon = when {").substringBefore("}")
                .lines().any { it.trim() == "else -> Icons.Default.Cloud" })
        assertTrue("tapping an unbacked trip does not start the backup",
            body.contains("!backupHeld && (folderConfigured || dropboxLinked) -> onRetryBackup()"))
    }

    @Test fun `the worker records the backups it verifies, with Dropbox's date`() {
        // Trips synced before per-trip Dropbox state existed said "not backed
        // up yet" while the worker proved the opposite on every pass and threw
        // the answer away. The skip branch records what it verified - stamped
        // with Dropbox's own date, not the time of the pass that noticed.
        assertTrue(worker.contains("val known = knownStatus[name.lowercase()]"))
        assertTrue("the mark does not use Dropbox's date",
            worker.contains("name, 2, remote.serverModifiedSec * 1000L)"))
        assertTrue("already-marked rows are rewritten every pass",
            worker.contains("known != null && known != 2"))
    }
}
