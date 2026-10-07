package com.eried.eucplanet.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * The Dropbox endpoints the repository calls, pinned by name.
 *
 * This exists because of a bug that cost real data consistency: a batch started
 * with files/move_batch_v2 was polled with files/move_batch/check, the v1
 * endpoint, which answers internal_error for a v2 job. The app read that as a
 * failed move and kept the rider's trips - while Dropbox had already archived
 * them. The two sides disagreed, and nothing in the app could tell.
 *
 * A version mismatch between a launch and its poll is invisible in review and
 * only shows up against the live API, so the pairing is asserted here rather
 * than trusted.
 */
class DropboxEndpointTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/eried/eucplanet/data/repository/DropboxRepository.kt")
        require(f.exists()) { "DropboxRepository.kt not found at ${f.absolutePath}" }
        f.readText()
    }

    @Test fun `a v2 batch launch is polled with the v2 check`() {
        assertTrue("move_batch_v2 is not being used", source.contains("files/move_batch_v2"))
        assertTrue("a v2 batch must be polled with check_v2",
            source.contains("files/move_batch/check_v2"))
    }

    @Test fun `the v1 batch check is never called`() {
        // Substring-safe: check_v2 contains "check", so look for the quoted v1
        // endpoint exactly as it would be written.
        assertFalse("files/move_batch/check (v1) polls a v2 job and lies about it",
            source.contains("\"files/move_batch/check\""))
    }

    @Test fun `single moves use move_v2`() {
        assertTrue(source.contains("files/move_v2"))
    }

    @Test fun `listing walks pages through list_folder and its continue`() {
        assertTrue(source.contains("files/list_folder"))
        assertTrue(source.contains("files/list_folder/continue"))
    }

    @Test fun `a batch stays inside Dropbox's per-call entry limit`() {
        // move_batch_v2 accepts at most 1000 entries; the constant is what
        // keeps a 2000-trip archive from being rejected outright.
        val limit = Regex("BATCH_MAX = (\\d+)").find(source)?.groupValues?.get(1)?.toInt()
        assertTrue("BATCH_MAX not found", limit != null)
        assertTrue("a batch of $limit exceeds Dropbox's limit of 1000", limit!! <= 1000)
    }
}
