package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.eucstats.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EucStatsUploadWorkerLogicTest {

    @Test
    fun `empty outcomes — no retry`() {
        assertFalse(workerResultRetry(emptyList()))
    }

    @Test
    fun `all UPLOADED — no retry`() {
        assertFalse(workerResultRetry(listOf(Outcome.UPLOADED, Outcome.UPLOADED)))
    }

    @Test
    fun `contains NEEDS_RETRY — retry`() {
        assertTrue(
            workerResultRetry(
                listOf(Outcome.UPLOADED, Outcome.NEEDS_RETRY, Outcome.FAILED_PERMANENT)
            )
        )
    }

    @Test
    fun `FAILED_PERMANENT only — no retry`() {
        assertFalse(workerResultRetry(listOf(Outcome.FAILED_PERMANENT, Outcome.FAILED_PERMANENT)))
    }

    @Test
    fun `single NEEDS_RETRY — retry`() {
        assertTrue(workerResultRetry(listOf(Outcome.NEEDS_RETRY)))
    }

    @Test
    fun `UPLOADED and FAILED_PERMANENT mixed — no retry`() {
        assertFalse(
            workerResultRetry(listOf(Outcome.UPLOADED, Outcome.FAILED_PERMANENT, Outcome.UPLOADED))
        )
    }
}
