package com.eried.eucplanet.data

import com.eried.eucplanet.data.sync.UploadPolicy
import com.eried.eucplanet.data.sync.UploadPolicy.EDIT_GRACE_SEC
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which copy of a trip wins.
 *
 * These run the decision with real numbers, because the version of this that
 * only read the source passed while the code compared seconds against
 * milliseconds and therefore never fired at all. The units are the point here,
 * not the prose.
 */
class UploadPolicyTest {

    private val nowSec = 1_787_000_000L
    private val nowMs = nowSec * 1000

    private fun decide(
        remoteSize: Long? = 100L,
        remoteSec: Long = nowSec,
        localSize: Long = 100L,
        localMs: Long = nowMs,
    ) = UploadPolicy.needsUpload(remoteSize, remoteSec, localSize, localMs)

    @Test fun `a trip Dropbox does not have goes up`() {
        assertTrue(decide(remoteSize = null))
    }

    @Test fun `a matching length is already synced`() {
        assertTrue("sizes match, so nothing to do", !decide(remoteSize = 100L, localSize = 100L))
    }

    @Test fun `a rename made on Dropbox days ago is not overwritten`() {
        // The case riders reported: renamed in another tool, which changes the
        // file's length, then this phone put its own copy back over it.
        assertFalse(decide(
            remoteSize = 1926L, remoteSec = nowSec,
            localSize = 1931L, localMs = nowMs - 3L * 24 * 3600 * 1000,
        ))
    }

    @Test fun `a rename made on this phone still goes up`() {
        assertTrue(decide(
            remoteSize = 1926L, remoteSec = nowSec - 3L * 24 * 3600,
            localSize = 1931L, localMs = nowMs,
        ))
    }

    @Test fun `seconds are not compared against milliseconds`() {
        // The bug: local ms is ~1000x the remote's seconds, so "remote is
        // newer" was arithmetically impossible and the guard never fired.
        // A remote edit one hour after the local write must be seen.
        assertFalse("a remote edit an hour later reads as older", decide(
            remoteSize = 1926L, remoteSec = nowSec + 3600,
            localSize = 1931L, localMs = nowMs,
        ))
    }

    @Test fun `clock skew inside the grace window does not stop an upload`() {
        // Dropbox's clock a minute ahead of the phone's is skew, not an edit.
        assertTrue(decide(
            remoteSize = 1926L, remoteSec = nowSec + EDIT_GRACE_SEC - 1,
            localSize = 1931L, localMs = nowMs,
        ))
    }

    @Test fun `just past the grace window counts as someone else's edit`() {
        assertFalse(decide(
            remoteSize = 1926L, remoteSec = nowSec + EDIT_GRACE_SEC + 1,
            localSize = 1931L, localMs = nowMs,
        ))
    }

    @Test fun `the grace is minutes, not days`() {
        assertTrue("a day of slack would let a real edit be overwritten",
            EDIT_GRACE_SEC in 1..(60 * 60))
    }
}
