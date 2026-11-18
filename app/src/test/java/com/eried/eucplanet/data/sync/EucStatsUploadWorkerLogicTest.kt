package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.eucstats.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EucStatsUploadWorkerLogicTest {

    @Test
    fun `empty outcomes, no retry`() {
        assertFalse(workerResultRetry(emptyList()))
    }

    @Test
    fun `all UPLOADED, no retry`() {
        assertFalse(workerResultRetry(listOf(Outcome.UPLOADED, Outcome.UPLOADED)))
    }

    @Test
    fun `contains NEEDS_RETRY, retry`() {
        assertTrue(
            workerResultRetry(
                listOf(Outcome.UPLOADED, Outcome.NEEDS_RETRY, Outcome.FAILED_PERMANENT)
            )
        )
    }

    @Test
    fun `FAILED_PERMANENT only, no retry`() {
        assertFalse(workerResultRetry(listOf(Outcome.FAILED_PERMANENT, Outcome.FAILED_PERMANENT)))
    }

    @Test
    fun `single NEEDS_RETRY, retry`() {
        assertTrue(workerResultRetry(listOf(Outcome.NEEDS_RETRY)))
    }

    @Test
    fun `UPLOADED and FAILED_PERMANENT mixed, no retry`() {
        assertFalse(
            workerResultRetry(listOf(Outcome.UPLOADED, Outcome.FAILED_PERMANENT, Outcome.UPLOADED))
        )
    }

    @Test
    fun `a rate limit is retried, an unknown rider is not`() {
        assertTrue(workerResultRetry(listOf(Outcome.UPLOADED, Outcome.BACK_OFF)))
        assertFalse(workerResultRetry(listOf(Outcome.RIDER_UNKNOWN)))
    }

    @Test
    fun `only a rate limit and an unknown rider end the pass`() {
        assertTrue(Outcome.BACK_OFF.endsPass)
        assertTrue(Outcome.RIDER_UNKNOWN.endsPass)
        assertFalse(Outcome.UPLOADED.endsPass)
        assertFalse(Outcome.FAILED_PERMANENT.endsPass)
        assertFalse(Outcome.NEEDS_RETRY.endsPass)
    }
}
