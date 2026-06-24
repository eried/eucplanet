package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the KS-16X stream-unlock nudge.
 *
 * The failure mode is a wheel that streams ZERO frames after connect (the
 * firmware drops the one-shot init writes during its post-subscribe window).
 * The fix re-sends the benign name query (0x9B) from pollRealtime
 * FRAME-INDEPENDENTLY -- if this ever regresses back to being gated on
 * "we already saw a frame", it becomes dead code in exactly the case it
 * must handle, and the wheel never wakes.
 */
class KingsongAdapterNudgeTest {

    private fun opcode(frame: ByteArray): Int? =
        if (frame.size >= 20) frame[16].toInt() and 0xFF else null

    /** 20-byte inbound frame with the given type byte at offset 16. */
    private fun inbound(type: Int): ByteArray {
        val f = ByteArray(20)
        f[0] = 0xAA.toByte(); f[1] = 0x55.toByte()
        f[16] = type.toByte()
        f[18] = 0x5A; f[19] = 0x5A
        return f
    }

    @Test fun nameNudge_firesWithoutAnyFrame_andIsCapped() {
        val a = KingsongAdapter()
        val emitted = (1..40).mapNotNull { opcode(a.pollRealtime()) }
        val nameQueries = emitted.count { it == 0x9B }
        val limitQueries = emitted.count { it == 0x98 }

        // Frame-independent: the wheel sent nothing, yet we still nudge 0x9B.
        assertTrue("expected 0x9B name nudges with zero frames seen", nameQueries > 0)
        // Capped so a permanently-silent wheel doesn't spam the BLE stack.
        assertEquals("name nudge must be capped", 6, nameQueries)
        // 0x98 must NOT fire at a silent wheel (chirp-prone): gated on a frame.
        assertEquals("0x98 must stay gated until a frame arrives", 0, limitQueries)
    }

    @Test fun nameNudge_stopsOnceNameReplyArrives() {
        val a = KingsongAdapter()
        // A couple of ticks of nudging...
        repeat(4) { a.pollRealtime() }
        // Wheel answers the name query (0xBB).
        a.onRawNotification(inbound(0xBB))
        // No more 0x9B name nudges after the reply.
        val after = (1..20).mapNotNull { opcode(a.pollRealtime()) }
        assertEquals("name nudge must stop after the 0xBB reply", 0, after.count { it == 0x9B })
    }

    @Test fun limitsRetry_firesOnlyAfterAFrameArrives() {
        val a = KingsongAdapter()
        // Feed a telemetry frame so the stream is demonstrably alive, but
        // never answer the limits query -> the 0x98 retry should now run.
        a.onRawNotification(inbound(0xA9))
        val emitted = (1..40).mapNotNull { opcode(a.pollRealtime()) }
        assertTrue("0x98 limits retry should fire once a frame has arrived",
            emitted.any { it == 0x98 })
    }
}
