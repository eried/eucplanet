package com.eried.eucplanet.amazfit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class AmazfitInboxTest {

    @Test
    fun `drain returns events oldest first and empties the queue`() {
        val inbox = AmazfitInbox()
        inbox.enqueue(mapOf("k" to "vibe", "ms" to 200))
        inbox.enqueue(mapOf("k" to "quit"))
        val drained = inbox.drainEvents()
        assertEquals(listOf("vibe", "quit"), drained.map { it["k"] })
        assertTrue(inbox.drainEvents().isEmpty())
        assertEquals(0, inbox.pendingCount)
    }

    @Test
    fun `presence window follows the last poll`() {
        val inbox = AmazfitInbox()
        assertFalse("never polled", inbox.hasPolledWithin(15_000, 100_000))
        inbox.notePoll(100_000)
        assertTrue(inbox.hasPolledWithin(15_000, 110_000))
        assertFalse(inbox.hasPolledWithin(15_000, 120_001))
        assertEquals(100_000L, inbox.lastPollAtMs)
    }

    @Test
    fun `poll counter resets on take`() {
        val inbox = AmazfitInbox()
        repeat(3) { inbox.notePoll(1_000L * it) }
        assertEquals(3, inbox.takePollCount())
        assertEquals(0, inbox.takePollCount())
    }

    @Test
    fun `awaitDrained returns once another thread drains the queue`() {
        val inbox = AmazfitInbox()
        inbox.enqueue(mapOf("k" to "quit"))
        thread {
            Thread.sleep(150)
            inbox.drainEvents()
        }
        assertTrue(inbox.awaitDrained(2_000))
    }

    @Test
    fun `awaitDrained gives up at the timeout when nobody polls`() {
        val inbox = AmazfitInbox()
        inbox.enqueue(mapOf("k" to "quit"))
        val started = System.currentTimeMillis()
        assertFalse(inbox.awaitDrained(200))
        assertTrue(System.currentTimeMillis() - started >= 150)
    }
}
