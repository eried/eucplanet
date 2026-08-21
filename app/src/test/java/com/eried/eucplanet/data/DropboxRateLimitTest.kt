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
        val body = repo.substringAfter("suspend fun uploadFile").substringBefore("suspend fun ")
        assertTrue("uploadFile does not go through the retry helper",
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

    @Test fun `a trip pulled from Dropbox is queued for the backup folder`() {
        // Downloads used to insert with uploadStatus 0, which the folder worker
        // skips, so the two backups disagreed until the rider pressed Sync all.
        val download = sync.substringAfter("for (name in toDownload)").substringBefore("// Refresh settings.json")
        assertTrue("a downloaded trip is not marked pending for the folder",
            download.contains("uploadStatus = if (settings.syncFolderUri != null) 1 else 0"))
        assertFalse("still inserting as nothing-to-do", download.contains("uploadStatus = 0,"))
    }
}
