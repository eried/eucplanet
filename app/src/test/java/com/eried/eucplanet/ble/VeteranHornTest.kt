package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.zip.CRC32

/**
 * Pins the Veteran horn command for current (Lynx-class) LeaperKim firmware.
 *
 * Decoded from a btsnoop of the official LeaperKim app sounding the horn on
 * a Lynx S (mVer 9, May 2026): every press writes an `LkAp` frame immediately
 * followed by an `LdAp` companion frame. Older EUC Planet builds sent only
 * the `LkAp` half, which Lynx-class firmware silently ignores, so the horn
 * never beeped. Both frames are byte-for-byte fixed (no nonce/counter
 * across the three captured presses). Pure-JVM test; no Android runtime
 * needed.
 */
class VeteranHornTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    // Exact bytes observed on the wire, both presses identical.
    private val LKAP_HORN = "4c 6b 41 70 0e 00 80 80 80 01 ca 87 e6 6f"
    private val LDAP_HORN = "4c 64 41 70 0e 00 00 80 80 01 f8 67 9f 85"

    @Test
    fun `veteran horn emits the LkAp frame then the LdAp companion`() {
        val adapter = VeteranAdapter()
        assertEquals(LKAP_HORN, adapter.horn().hex())
        val followup = adapter.hornFollowup()
        assertNotNull("Veteran must send the LdAp horn companion", followup)
        assertEquals(LDAP_HORN, followup!!.hex())
    }

    @Test
    fun `both horn frames carry a valid big-endian CRC32 trailer`() {
        for (frame in listOf(VeteranCommands.horn(), VeteranCommands.hornCompanion())) {
            val body = frame.copyOfRange(0, frame.size - 4)
            val want = CRC32().apply { update(body) }.value.toInt()
            val got = ((frame[frame.size - 4].toInt() and 0xff) shl 24) or
                ((frame[frame.size - 3].toInt() and 0xff) shl 16) or
                ((frame[frame.size - 2].toInt() and 0xff) shl 8) or
                (frame[frame.size - 1].toInt() and 0xff)
            assertEquals("CRC32 mismatch on ${frame.hex()}", want, got)
        }
    }
}
