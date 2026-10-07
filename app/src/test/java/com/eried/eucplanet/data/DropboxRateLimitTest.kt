package com.eried.eucplanet.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * That a rate-limited request is waited out rather than counted as a lost trip.
 *
 * Dropbox limits per account, so a rider pulling a big library hits it partway
 * through - and a 429 used to look exactly like a missing file: download
 * returned null, the sync counted a failure, and the rider was told a number
 * with no reason. Two thousand trips is where this shows up, which is a size
 * nobody tests by hand.
 *
 * The handling is inside network code that a unit test cannot call, so what is
 * pinned here is that it exists and is wired to both directions - the way the
 * endpoint pairing is pinned in DropboxEndpointTest.
 */
class DropboxRateLimitTest {

    private val repo: String by lazy {
        File("src/main/java/com/eried/eucplanet/data/repository/DropboxRepository.kt").readText()
    }
    private val sync: String by lazy {
        File("src/main/java/com/eried/eucplanet/data/sync/SyncManager.kt").readText()
    }

    @Test fun `downloads wait out a rate limit instead of returning null`() {
        val body = repo.substringAfter("suspend fun downloadFile").substringBefore("suspend fun listFolder")
        assertTrue("downloadFile does not go through the retry helper",
            body.contains("withRateLimitRetry"))
    }

    @Test fun `uploads wait it out too`() {
        // uploadFile is a one-line delegate now; the work is in the variant
        // that also reports the timestamp Dropbox stored the file under.
        val body = repo.substringAfter("suspend fun uploadFileStamped").substringBefore("suspend fun ")
        assertTrue("the upload does not go through the retry helper",
            body.contains("withRateLimitRetry"))
    }

    @Test fun `the wait comes from what Dropbox asked for`() {
        // Honouring retry_after is the difference between pausing a second and
        // dropping the file: the API says exactly when it will answer again.
        assertTrue(repo.contains("Retry-After"))
        assertTrue(repo.contains("retry_after"))
    }

    @Test fun `the wait is bounded, so a sync cannot hang forever`() {
        assertTrue("no ceiling on the wait", repo.contains("coerceIn(500L, 30_000L)"))
        assertTrue("no limit on the retries", repo.contains("RATE_LIMIT_TRIES"))
    }

    @Test fun `giving up sets a flag the sync can report`() {
        assertTrue(repo.contains("rateLimited = true"))
        assertTrue("the sync never reads it", sync.contains("dropboxRepository.rateLimited"))
    }

    @Test fun `the flag describes this run, not the last one`() {
        assertTrue("nothing clears it at the start of a sync",
            sync.contains("clearRateLimited()"))
    }

    @Test fun `a rate-limited sync tells the rider, rather than a bare count`() {
        assertTrue(sync.contains("SyncResult.RateLimited"))
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue("no message for it", strings.contains("sync_rate_limited"))
    }

    @Test fun `the background worker downloads, not just uploads`() {
        // For a long time it only uploaded, so a rider setting up a new phone
        // could only pull their library through the foreground sync - which
        // means sitting and watching it for the better part of an hour, and
        // losing it the moment Android reclaimed the app.
        val worker = File("src/main/java/com/eried/eucplanet/data/sync/DropboxSyncWorker.kt").readText()
        assertTrue("the worker never downloads", worker.contains("downloadMissingTrips"))
    }

    @Test fun `a download pass is time-bounded and asks to be run again`() {
        val worker = File("src/main/java/com/eried/eucplanet/data/sync/DropboxSyncWorker.kt").readText()
        // WorkManager gives a job about ten minutes; taking what fits and
        // rescheduling is what lets a big library arrive across several runs.
        assertTrue("no budget on the download pass", worker.contains("DOWNLOAD_BUDGET_MS"))
        assertTrue("nothing reschedules when trips remain",
            worker.contains("stillMissing > 0"))
        // Read the minutes out of "DOWNLOAD_BUDGET_MS = 6 * 60_000L" without
        // a regex, so the assertion cannot be broken by escaping.
        val minutes = worker.substringAfter("DOWNLOAD_BUDGET_MS = ", "")
            .substringBefore(" *", "")
            .trim().toIntOrNull()
        assertTrue("budget missing or too close to the 10 minute limit",
            minutes != null && minutes <= 8)
    }

    @Test fun `the download pass stops when the rider cancels`() {
        assertTrue(sync.contains("isStopped: () -> Boolean"))
    }

    @Test fun `nothing downloads unless the rider asked for it`() {
        // Downloading is a lot of data and a lot of battery, and someone who
        // links Dropbox to back trips *up* should not find a library arriving.
        // The worker only ever finishes a pull that was requested.
        val pass = sync.substringAfter("suspend fun downloadMissingTrips").substringBefore("private fun parseCsvMeta")
        assertTrue("the download pass does not check for a request",
            pass.contains("if (!settings.dropboxPullRequested) return 0"))
    }

    @Test fun `pressing sync, or linking, is what asks for it`() {
        assertTrue("a foreground sync does not record the request",
            sync.contains("dropboxPullRequested = true"))
        assertTrue("linking has no way to ask", sync.contains("fun requestDropboxPull"))
        val main = File("src/main/java/com/eried/eucplanet/MainActivity.kt").readText()
        assertTrue("linking does not ask for the trips", main.contains("requestDropboxPull()"))
    }

    @Test fun `the request survives the app being killed`() {
        // The whole point: a big library outlasts the app's time in memory.
        val settings = File("src/main/java/com/eried/eucplanet/data/model/AppSettings.kt").readText()
        assertTrue("the flag is not persisted", settings.contains("val dropboxPullRequested"))
        val json = File("src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt").readText()
        assertTrue("the flag is not in the settings json", json.contains("dropboxPullRequested"))
    }

    @Test fun `cancelling stops it coming back`() {
        // Cancel used to leave the queued watchdog alive, which would have
        // resumed the download minutes later and undone the rider's decision.
        val cancel = sync.substringAfter("fun cancelActiveSync").take(900)
        assertTrue("cancel leaves the queued work running",
            cancel.contains("cancelUniqueWork(DROPBOX_SYNC_WORK_NAME)"))
        assertTrue("cancel leaves the request standing",
            cancel.contains("dropboxPullRequested = false"))
    }

    @Test fun `finishing the pull clears the request`() {
        assertTrue(sync.contains("dropboxPullRequested = left > 0"))
    }

    @Test fun `a trip pulled from Dropbox is queued for the backup folder`() {
        // Downloads used to insert with uploadStatus 0, which the folder worker
        // skips, so the two backups disagreed until the rider pressed Sync all.
        val download = sync.substringAfter("for (name in toDownload)").substringBefore("// Refresh settings.json")
        assertTrue("a downloaded trip is not queued for the folder",
            download.contains("uploadStatus = if (settings.syncFolderUri != null) 4 else 0"))
        assertFalse("still inserting as nothing-to-do", download.contains("uploadStatus = 0,"))
    }

    @Test fun `mirroring a download never overwrites what the folder already has`() {
        // Status 4 rather than 1: a locally recorded trip is the authority on
        // its own file and may replace the folder's copy, but a download is
        // not - the folder's version could be different and is not ours to
        // discard without asking.
        val worker = File("src/main/java/com/eried/eucplanet/data/sync/TripUploadWorker.kt").readText()
        assertTrue("the mirror does not distinguish a downloaded trip",
            worker.contains("skipIfPresent = trip.uploadStatus == 4"))
        assertTrue("uploadCsv cannot skip", sync.contains("skipIfPresent: Boolean = false"))
        val dao = File("src/main/java/com/eried/eucplanet/data/db/TripDao.kt").readText()
        assertTrue("status 4 is not picked up by the folder worker",
            dao.contains("uploadStatus IN (1, 3, 4)"))
    }
}
