package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the KS-16X telemetry keep-alive.
 *
 * The KS-16X new-revision firmware stops pushing 0xA9/0xB9 telemetry unless
 * the app keeps writing to it (~1 Hz), plus a one-shot 0x5E stream-start kick
 * shortly after connect -- behaviour mirrored (reference only) from the
 * official KingSong app. These must be FRAME-INDEPENDENT: the failure mode is
 * zero inbound frames, so anything gated on "we already saw a frame" would be
 * dead code in exactly the case it must handle. The chirp-prone 0x98 limits
 * retry, by contrast, must stay gated on a live stream.
 */
class KingsongAdapterStreamTest {

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

    @Test fun keepAliveAndKick_fireWithoutAnyFrame() {
        val a = KingsongAdapter()
        val emitted = (1..30).mapNotNull { opcode(a.pollRealtime()) }

        // ~1 Hz keep-alive (0x00) sustains the push stream; must fire even
        // though the wheel has sent nothing.
        assertTrue("expected periodic 0x00 keep-alive with zero frames seen",
            emitted.count { it == 0x00 } >= 4)
        // Stream-start kick (0x5E) fires exactly once.
        assertEquals("0x5E stream-start kick must fire exactly once", 1,
            emitted.count { it == 0x5E })
        // 0x98 limits read must NOT fire at a silent wheel (it can chirp).
        assertEquals("0x98 must stay gated until a frame arrives", 0,
            emitted.count { it == 0x98 })
    }

    @Test fun streamKick_neverRepeats() {
        val a = KingsongAdapter()
        val emitted = (1..60).mapNotNull { opcode(a.pollRealtime()) }
        assertEquals("0x5E must be one-shot for the whole connection", 1,
            emitted.count { it == 0x5E })
    }

    @Test fun limitsRetry_firesOnlyAfterAFrameArrives() {
        val a = KingsongAdapter()
        // Stream is demonstrably alive (a frame arrived) but limits never
        // answered -> the 0x98 retry should now run.
        a.onRawNotification(inbound(0xA9))
        val emitted = (1..40).mapNotNull { opcode(a.pollRealtime()) }
        assertTrue("0x98 limits retry should fire once a frame has arrived",
            emitted.any { it == 0x98 })
    }

    @Test fun resetsOnDisconnect() {
        val a = KingsongAdapter()
        repeat(20) { a.pollRealtime() }      // consume the one-shot kick
        a.onDisconnect()
        val afterReconnect = (1..30).mapNotNull { opcode(a.pollRealtime()) }
        // The kick must arm again for the next connection.
        assertEquals("0x5E kick must re-arm after disconnect", 1,
            afterReconnect.count { it == 0x5E })
    }
}
