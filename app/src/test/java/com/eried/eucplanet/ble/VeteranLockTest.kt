package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.zip.CRC32

/**
 * Pins the Veteran software-lock command against captured reference frames.
 *
 * Decoded from a Lynx S btsnoop on 2026-06-19 where the rider toggled the
 * official LeaperKim app's lock 10 times. Bytes 4..7 of the payload are the
 * rider's local wall clock (day, hour, minute, second) at the moment of the
 * write — the wheel rejects frames whose timestamp doesn't match the live
 * clock, which is why an earlier hardcoded-constants implementation never
 * actually locked the wheel. Pure-JVM test; pass a fixed [Calendar] so this
 * doesn't drift with the real wall clock.
 */
class VeteranLockTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    /** A specific captured frame: 2026-06-19 10:59:05 local, state=LOCK. */
    private fun captureFor(state: Int, day: Int, hour: Int, minute: Int, second: Int): String {
        val out = ByteArray(25)
        // magic LdAp
        out[0] = 0x4C; out[1] = 0x64; out[2] = 0x41; out[3] = 0x70
        // length
        out[4] = 25
        // payload[0..3] = 00 05 1A 06
        out[5] = 0x00; out[6] = 0x05; out[7] = 0x1A; out[8] = 0x06
        // payload[4..7] = day, hour, minute, second
        out[9] = day.toByte(); out[10] = hour.toByte()
        out[11] = minute.toByte(); out[12] = second.toByte()
        // payload[8..11] = 02 04 0C AB
        out[13] = 0x02; out[14] = 0x04; out[15] = 0x0C; out[16] = 0xAB.toByte()
        // payload[12] = state
        out[17] = state.toByte()
        // payload[13..15] = 00 00 00 (last is the valueByte slot)
        out[18] = 0x00; out[19] = 0x00; out[20] = 0x00
        // CRC32 BE over bytes 0..20
        val crc = CRC32().apply { update(out, 0, 21) }.value.toInt()
        out[21] = ((crc ushr 24) and 0xFF).toByte()
        out[22] = ((crc ushr 16) and 0xFF).toByte()
        out[23] = ((crc ushr 8) and 0xFF).toByte()
        out[24] = (crc and 0xFF).toByte()
        return out.hex()
    }

    private fun calAt(day: Int, hour: Int, minute: Int, second: Int): Calendar =
        GregorianCalendar(2026, Calendar.JUNE, day, hour, minute).apply {
            set(Calendar.SECOND, second)
        }

    @Test
    fun `lock at 2026-06-19 10-59-05 matches the captured frame`() {
        val expected = captureFor(state = 0x01, day = 19, hour = 10, minute = 59, second = 5)
        val actual = VeteranCommands.setLock(locked = true, now = calAt(19, 10, 59, 5)).hex()
        assertEquals(expected, actual)
    }

    @Test
    fun `unlock at 2026-06-19 10-59-08 matches the captured frame`() {
        val expected = captureFor(state = 0x00, day = 19, hour = 10, minute = 59, second = 8)
        val actual = VeteranCommands.setLock(locked = false, now = calAt(19, 10, 59, 8)).hex()
        assertEquals(expected, actual)
    }

    @Test
    fun `lock at 2026-06-19 11-00-00 matches the minute-rollover capture`() {
        val expected = captureFor(state = 0x00, day = 19, hour = 11, minute = 0, second = 0)
        val actual = VeteranCommands.setLock(locked = false, now = calAt(19, 11, 0, 0)).hex()
        assertEquals(expected, actual)
    }

    @Test
    fun `frame length is always 25 bytes`() {
        assertEquals(25, VeteranCommands.setLock(true, calAt(1, 0, 0, 0)).size)
        assertEquals(25, VeteranCommands.setLock(false, calAt(31, 23, 59, 59)).size)
    }

    @Test
    fun `wall-clock bytes track the Calendar values`() {
        val frame = VeteranCommands.setLock(true, calAt(7, 14, 23, 42))
        assertEquals(7, frame[9].toInt() and 0xFF)
        assertEquals(14, frame[10].toInt() and 0xFF)
        assertEquals(23, frame[11].toInt() and 0xFF)
        assertEquals(42, frame[12].toInt() and 0xFF)
    }
}
