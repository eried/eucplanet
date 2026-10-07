package com.eried.eucplanet.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules that decide whether a rider's horn reaches the wheel.
 *
 * Pinned to a real session: a V8S diagnostics log with 4199 writes over about
 * twenty minutes, 2403 of them refused by the stack as busy and 390 given up
 * on entirely. Eight of the writes were the rider pressing the light, and the
 * log shows two of those eight thrown away after four attempts while telemetry
 * polls kept being queued behind them at four a second.
 */
class BleWriteQueueTest {

    private fun poll(n: Int) = byteArrayOf(0x13, n.toByte())
    private fun cmd(n: Int) = byteArrayOf(0x0D, n.toByte())

    @Test
    fun `a command goes before polls that were already waiting`() {
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.POLL, poll(1))
        q.offer(BleWriteQueue.Kind.COMMAND, cmd(1))

        val first = q.take()!!
        assertEquals(BleWriteQueue.Kind.COMMAND, first.kind)
        assertArrayEquals(cmd(1), first.data)
    }

    @Test
    fun `a repeated poll replaces itself instead of piling up`() {
        // The poll loop keeps writing on its timer whatever the link is doing.
        // Holding all of them is what filled the queue and started refusing
        // commands; a repeat asks for telemetry the newest already asks for.
        //
        // This used to offer fifty DIFFERENT polls and expect one survivor,
        // which is the bug rather than the rule: a wheel with a slow poll and a
        // fast one lost the slow one every time. On the P6 that was the 0x84
        // detailed frame, the only carrier of motor temperature. Same poll,
        // repeated, is the case the rule was always about.
        val q = BleWriteQueue()
        repeat(50) { q.offer(BleWriteQueue.Kind.POLL, poll(7)) }

        val only = q.take()!!
        assertArrayEquals("the newest poll is the one worth sending", poll(7), only.data)
        assertNull(q.take())
        assertEquals(49, q.supersededPolls)
    }

    @Test
    fun `distinct polls are not interchangeable`() {
        // The regression this guards: a wheel that asks for more than one thing
        // must get an answer to each.
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.POLL, poll(1))
        repeat(30) { q.offer(BleWriteQueue.Kind.POLL, poll(2)) }
        val sent = generateSequence { q.take() }.map { it.data }.toList()
        assertEquals(2, sent.size)
        assertArrayEquals("the slow poll was starved by the fast one", poll(1), sent[0])
    }

    @Test
    fun `a burst of polls cannot crowd out a rider's taps`() {
        // What the log shows happening: light, then polls four a second while
        // the stack is busy. Every tap has to survive it.
        val q = BleWriteQueue()
        repeat(8) { tap ->
            q.offer(BleWriteQueue.Kind.COMMAND, cmd(tap))
            repeat(30) { q.offer(BleWriteQueue.Kind.POLL, poll(it)) }
        }
        assertEquals(0, q.droppedCommands)

        val taken = generateSequence { q.take() }.toList()
        val commands = taken.filter { it.kind == BleWriteQueue.Kind.COMMAND }
        assertEquals(8, commands.size)
        commands.forEachIndexed { i, e -> assertArrayEquals(cmd(i), e.data) }
    }

    @Test
    fun `commands keep their order`() {
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.COMMAND, cmd(1))
        q.offer(BleWriteQueue.Kind.COMMAND, cmd(2))
        assertArrayEquals(cmd(1), q.take()!!.data)
        assertArrayEquals(cmd(2), q.take()!!.data)
    }

    @Test
    fun `a full command queue keeps the newest, and says so`() {
        // Only reachable on a link that has been stuck for a long time. The
        // newest tap is the one that still describes what the rider wants.
        val q = BleWriteQueue(commandCapacity = 4)
        repeat(6) { assertEquals(it < 4, q.offer(BleWriteQueue.Kind.COMMAND, cmd(it))) }

        assertEquals(2, q.droppedCommands)
        val left = generateSequence { q.take() }.map { it.data[1].toInt() }.toList()
        assertEquals(listOf(2, 3, 4, 5), left)
    }

    @Test
    fun `an idle queue hands back nothing`() {
        val q = BleWriteQueue()
        assertNull(q.take())
        assertTrue(q.isEmpty)
    }

    @Test
    fun `a command is worth more retries than a poll`() {
        assertTrue(
            BleWriteQueue.maxAttempts(BleWriteQueue.Kind.COMMAND) >
                BleWriteQueue.maxAttempts(BleWriteQueue.Kind.POLL)
        )
    }

    @Test
    fun `the backoff outlasts the stack staying busy`() {
        // Measured off the rider's log: the stack refuses writes for up to
        // about 300 ms while it streams a reply. Four tries 80 ms apart cover
        // 240 ms and give up just short of it, which is why commands died.
        val budget = (1..BleWriteQueue.maxAttempts(BleWriteQueue.Kind.COMMAND))
            .sumOf { BleWriteQueue.retryDelayMs(it) }
        assertTrue("a command only gets $budget ms of trying", budget > 800L)

        // And it escalates rather than hammering a stack that said it is busy.
        assertTrue(BleWriteQueue.retryDelayMs(3) > BleWriteQueue.retryDelayMs(1))
    }

    @Test
    fun `clearing drops everything`() {
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.COMMAND, cmd(1))
        q.offer(BleWriteQueue.Kind.POLL, poll(1))
        q.clear()
        assertTrue(q.isEmpty)
        assertNull(q.take())
        assertFalse(q.commandsWaiting > 0)
    }
}
