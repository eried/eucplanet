package com.eried.eucplanet.data

import com.eried.eucplanet.data.sync.SyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dropbox cutting the app off, as opposed to asking it to slow down.
 *
 * Their edge answers 403 with a `.tag` of "other" and refuses every endpoint at
 * once, the account included. It carries no retry_after and is not a 429, so
 * the sync read it as an ordinary failure and came back a minute later, over
 * and over: observed doing exactly that once a minute for an hour, while the
 * rider was told nothing. Knocking that often is also how a client earns a
 * longer refusal.
 */
class DropboxRefusalTest {

    private val repo = File("src/main/java/com/eried/eucplanet/data/repository/DropboxRepository.kt").readText()
    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/DropboxSyncWorker.kt").readText()
    private val sync = File("src/main/java/com/eried/eucplanet/data/sync/SyncManager.kt").readText()

    @Test fun `a refusal is told apart from a rate limit`() {
        assertTrue("the app cannot see a refusal at all", repo.contains("var refused: Boolean"))
        assertTrue("403 is not what marks it", repo.contains("resp.code == 403"))
    }

    @Test fun `every API call can raise it, not just the ones remembered`() {
        // Checked in the HTTP client itself rather than in each response
        // handler. There are a dozen of those, and the one that gets missed is
        // the one that loops.
        val client = repo.substringAfter("private val http = OkHttpClient").take(600)
        assertTrue("the client does not watch for refusals", client.contains("addInterceptor"))
        assertTrue("403 is not what it watches for", client.contains("resp.code == 403"))
        assertTrue("it does not raise the flag", client.contains("refused = true"))
    }

    @Test fun `starting a sync clears it, so the flag describes this run`() {
        assertTrue(repo.contains("fun clearRateLimited() { rateLimited = false; refused = false }"))
    }

    @Test fun `the wait is long enough to be a real backoff`() {
        // Half an hour. The ordinary backoff starts at a minute, which is the
        // cadence that caused the trouble.
        assertEquals(30L * 60, SyncManager.REFUSED_RETRY_SECONDS)
        assertTrue(SyncManager.REFUSED_RETRY_SECONDS > SyncManager.delayForAttempt(1) * 10)
    }

    @Test fun `the worker waits instead of retrying, at the call that fails first`() {
        // A refusal fails on the pass's first call, so the early return is the
        // one that ran all night. WorkManager's own retry must not take it.
        val early = worker.substringAfter("if (remoteTrips == null) {").take(900)
        assertTrue("the first failure still goes to WorkManager's retry",
            early.contains("dropboxRepository.refused"))
        assertTrue("no long backoff is scheduled there",
            early.contains("REFUSED_RETRY_SECONDS"))
        assertTrue("it still reports a retry, which reschedules on its own terms",
            early.indexOf("return Result.success()") < early.indexOf("return Result.retry()"))
    }

    @Test fun `the foreground sync backs off too, and still tells the rider`() {
        val branch = sync.substringAfter("dropboxRepository.refused").take(700)
        assertTrue("the rider is told nothing", branch.contains("SyncResult.RateLimited"))
        assertTrue("their trips are not marked done", branch.contains("dropboxSyncPending = true"))
        assertTrue("it comes straight back", branch.contains("REFUSED_RETRY_SECONDS"))
    }
}
