package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A slow poll must survive a fast one.
 *
 * The queue started with a single pending-poll slot, on the reasoning that a
 * poll waiting behind another poll is asking for telemetry the newer one
 * already asks for. That holds for a wheel with one poll and fails for every
 * wheel with two.
 *
 * The P6 is the case that broke. It polls realtime several times a second and
 * polls the 0x84 detailed frame on a slow cadence, and the adapter calls that
 * frame "the ONLY place the motor temperature actually arrives". One slot
 * meant the next realtime poll overwrote it before the link drained, so the
 * temperature never arrived at all. The V14's four BMS pack queries lost the
 * same race against each other.
 */
class BleWriteQueuePollStarvationTest {

    private val realtime = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x14, 0x01, 0x04, 0x11)
    private val stats = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x14, 0x02, 0x20, 0x20, 0x16)
    private val settings = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x14, 0x01, 0x20, 0x35)

    @Test fun `a slow poll still gets sent after a burst of fast ones`() {
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.POLL, stats)
        repeat(20) { q.offer(BleWriteQueue.Kind.POLL, realtime) }

        val sent = generateSequence { q.take() }.map { it.data }.toList()
        assertTrue(
            "the detailed-frame poll was thrown away, which is how the P6 lost its temperature",
            sent.any { it.contentEquals(stats) },
        )
        assertTrue(sent.any { it.contentEquals(realtime) })
    }

    @Test fun `the same poll repeated still replaces itself`() {
        // The original rule, kept: twenty identical realtime polls are one
        // realtime poll, not twenty.
        val q = BleWriteQueue()
        repeat(20) { q.offer(BleWriteQueue.Kind.POLL, realtime) }
        assertNotNull(q.take())
        assertEquals(null, q.take())
    }

    @Test fun `three different polls all survive, which is what a P6 asks for`() {
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.POLL, realtime)
        q.offer(BleWriteQueue.Kind.POLL, settings)
        q.offer(BleWriteQueue.Kind.POLL, stats)
        val sent = generateSequence { q.take() }.map { it.data }.toList()
        assertEquals(3, sent.size)
    }

    @Test fun `the oldest waiting poll goes first, so nothing is starved`() {
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.POLL, stats)
        q.offer(BleWriteQueue.Kind.POLL, realtime)
        assertTrue(q.take()!!.data.contentEquals(stats))
    }

    @Test fun `commands still go before any poll`() {
        val q = BleWriteQueue()
        val horn = byteArrayOf(0x01, 0x02, 0x03)
        q.offer(BleWriteQueue.Kind.POLL, realtime)
        q.offer(BleWriteQueue.Kind.POLL, stats)
        q.offer(BleWriteQueue.Kind.COMMAND, horn)
        assertTrue("a rider's horn queued behind telemetry", q.take()!!.data.contentEquals(horn))
    }

    @Test fun `a stuck link cannot grow the poll map without bound`() {
        val q = BleWriteQueue()
        repeat(200) { i ->
            q.offer(BleWriteQueue.Kind.POLL, byteArrayOf(i.toByte(), (i shr 8).toByte()))
        }
        val sent = generateSequence { q.take() }.count()
        assertTrue("poll backlog grew unbounded: $sent", sent <= BleWriteQueue.POLL_KINDS_CAPACITY)
    }

    @Test fun `clearing drops the polls too`() {
        val q = BleWriteQueue()
        q.offer(BleWriteQueue.Kind.POLL, realtime)
        q.offer(BleWriteQueue.Kind.POLL, stats)
        q.clear()
        assertTrue(q.isEmpty)
        assertEquals(null, q.take())
    }
}
