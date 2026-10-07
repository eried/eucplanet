package com.eried.eucplanet.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The upload worker asks the policy, and says so when it steps back.
 *
 * The decision itself is tested with numbers in UploadPolicyTest. This only
 * pins the wiring: an earlier version of this file described the rule in prose
 * and asserted the source contained it, which passed happily while the rule
 * compared seconds against milliseconds and never fired.
 */
class RemoteEditWinsTest {

    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/DropboxSyncWorker.kt").readText()

    @Test fun `the worker decides through the policy`() {
        val decision = worker.substringAfter("fun needsUpload(f: File)").substringBefore("val needUpload")
        assertTrue("the rule is inlined again, where it cannot be tested",
            decision.contains("UploadPolicy.needsUpload("))
        assertTrue("the file's own timestamp is not passed in",
            decision.contains("localModifiedMs = f.lastModified()"))
        assertTrue("Dropbox's timestamp is not passed in",
            decision.contains("remoteModifiedSec = remote?.serverModifiedSec"))
    }

    @Test fun `stepping back is logged, because it is invisible otherwise`() {
        assertTrue(worker.contains("changed on Dropbox since this phone wrote it"))
    }
}
