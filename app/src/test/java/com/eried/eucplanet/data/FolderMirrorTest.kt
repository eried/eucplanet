package com.eried.eucplanet.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mirroring a whole library into the backup folder.
 *
 * Restoring 2000 trips from Dropbox turned the backup folder into a crawl: the
 * copy asked the folder "do you have this one?" per trip, and a document
 * provider answers that by listing the entire directory. The cost climbed as
 * the folder filled - eight files a minute once it held 1600 - so the rider
 * saw the download finish quickly and the backup apparently hang forever.
 *
 * These pin the shape of the fix rather than the timing, which no unit test can
 * see: one listing per pass, and only one pass at a time.
 */
class FolderMirrorTest {

    private val sync = File("src/main/java/com/eried/eucplanet/data/sync/SyncManager.kt").readText()
    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/TripUploadWorker.kt").readText()

    @Test fun `the copy can be told what the folder already holds`() {
        assertTrue("uploadCsv no longer accepts a known-name set",
            sync.contains("knownNames: Map<String, String>? = null"))
    }

    @Test fun `a name the folder is known not to have costs no lookup`() {
        val body = sync.substringAfter("knownNames: Map<String, String>? = null").take(1200)
        assertTrue("uploadCsv looks the name up even when it knows the answer",
            body.contains("knownHas"))
        assertTrue("the lookup is not guarded by what the caller knows",
            body.contains("knownHas == false -> null"))
    }

    @Test fun `the worker lists the folder once, before the loop`() {
        val listedAt = worker.indexOf("listFolderTripSizes")
        val loopAt = worker.indexOf("for (trip in pending")
        assertTrue("the worker never lists the folder up front", listedAt > 0)
        assertTrue("the listing happens inside the loop, which is the bug",
            listedAt < loopAt)
    }

    @Test fun `two upload passes cannot run at once`() {
        // The one-time and periodic workers are separate unique work names, so
        // WorkManager will happily run both over the same pending list. Two
        // passes creating one file leaves a renamed duplicate behind, because
        // the document provider renames rather than refuses.
        assertTrue("the pass lock is gone from SyncManager",
            sync.contains("uploadPassLock") && sync.contains("fun <T> withUploadPass"))
        assertTrue("the upload worker does not take the pass lock",
            worker.contains("withUploadPass"))
    }

    @Test fun `a restored trip is never written over a backup that exists`() {
        // Status 4 means "came down from Dropbox": mirror it in, but the
        // folder's own copy always wins.
        assertTrue(worker.contains("skipIfPresent = trip.uploadStatus == 4"))
        assertTrue(sync.contains("if (existing != null && skipIfPresent) return true"))
    }

    @Test fun `a trip the folder already has is skipped without asking the folder`() {
        // Most of a restored library is already backed up, so this is the
        // common path, and it was the expensive one: skipping still cost a
        // full directory listing to confirm what the caller had just listed.
        val body = sync.substringAfter("knownNames: Map<String, String>? = null").take(900)
        assertTrue("the skip goes to the folder anyway",
            body.contains("if (knownHas == true && skipIfPresent) return true"))
    }

    @Test fun `the foreground sync takes the pass lock as well`() {
        // It is not enough to serialise the workers. The app runs a folder sync
        // on launch, which is precisely when a worker mirroring a restored
        // library is running, and both create files in the same folder.
        assertTrue("runSync no longer holds the pass lock",
            sync.contains("fun runSync() = withUploadPass { runSyncPass() }"))
    }

    @Test fun `the worker fills the folder's gaps, not only its queue`() {
        // The Dropbox worker compares every local file against the remote
        // listing on every pass; this one walked only its queue, so Dropbox
        // was a mirror and the folder was a mailbox. Trips older than the
        // folder, or files deleted from it behind the app's back, stayed
        // missing forever - surfacing only as the warning in Backups.
        assertTrue("the reconcile sweep is gone",
            worker.contains("t.fileName.lowercase() !in knownNames"))
        assertTrue("the sweep does not ride the same upload loop",
            worker.contains("for (trip in pending + reconcile"))
        // Skip-if-present: a reconciled trip is pushed as status 4, the
        // mirror-if-absent mode, so a copy the folder holds is never touched.
        assertTrue(worker.contains("reconcile.map { it.copy(uploadStatus = 4) }"))
        assertTrue("the empty-queue early return ignores the sweep",
            worker.contains("if (pending.isEmpty() && reconcile.isEmpty())"))
    }

    @Test fun `names match the folder whatever their case`() {
        // Shared storage ignores case. A trip the app spells "inmotion_v14_..."
        // beside the folder's "Inmotion_V14_..." looked missing on every pass,
        // so each pass wrote it again and the provider renamed the copy, "(1)"
        // up to "(32)", after which every write failed and the backup never
        // finished.
        assertTrue("the worker's listing is not keyed by the lowercased name",
            worker.contains("associateBy { it.lowercase() }"))
        assertTrue("the conflict count looks sizes up by the exact name",
            worker.contains("sizeByLower[t.fileName.lowercase()]"))
        assertTrue("uploadCsv looks the name up by its exact spelling",
            sync.contains("knownNames?.get(localFile.name.lowercase())"))
        assertTrue("the unguided lookup still matches by exact case",
            sync.contains("it.name.equals(localFile.name, ignoreCase = true)"))
    }
}
