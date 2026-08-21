package com.eried.eucplanet.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An edit made somewhere else must survive the next sync.
 *
 * Renaming a trip in eucviewer writes `trip.name=` into the CSV, exactly as
 * this app does, and that changes the file's size. The upload rule was "if the
 * sizes differ, ours goes up", so the next background pass replaced the renamed
 * copy on Dropbox with this phone's nameless one. The rider's rename came back
 * "normal" days later and nothing in the app had done anything visibly wrong.
 *
 * The phone still wins for its own trips. It only steps back when Dropbox's
 * copy changed after the last upload, which is something only another tool can
 * have done.
 */
class RemoteEditWinsTest {

    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/DropboxSyncWorker.kt").readText()
    private val decision = worker.substringAfter("fun needsUpload(f: File)").substringBefore("val needUpload")

    @Test fun `a trip Dropbox does not have is still uploaded`() {
        assertTrue("a missing remote no longer uploads",
            decision.contains("remoteTrips[f.name] ?: return true"))
    }

    @Test fun `a matching size is still left alone`() {
        // Trip CSVs are append-only, so equal length means equal content.
        assertTrue(decision.contains("if (remote.size == f.length()) return false"))
    }

    @Test fun `a copy edited on Dropbox is not overwritten`() {
        assertTrue("the upload no longer checks who changed it last",
            decision.contains("remote.serverModified > ours + EDIT_GRACE_MS"))
        assertTrue("it overwrites anyway", decision.contains("return false"))
    }

    @Test fun `a trip never uploaded is not mistaken for a remote edit`() {
        // uploadedAt is 0 for a trip this phone never sent. Reading that as
        // "they edited it" would mean never uploading it at all.
        assertTrue(decision.contains("ours > 0L &&"))
    }

    @Test fun `the grace window is minutes, not days`() {
        // It exists for clock skew between the phone and Dropbox, nothing more.
        // Days of slack would let a real edit be overwritten.
        assertTrue(worker.contains("EDIT_GRACE_MS = 5L * 60 * 1000"))
    }

    @Test fun `the decision is logged, because it is invisible otherwise`() {
        assertTrue(decision.contains("changed on Dropbox since we uploaded it"))
    }
}
