package com.eried.eucplanet.ble.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in external-GPS battery decoding for both boxes:
 *  - RaceBox carries it in the extended Data Message's batteryStatus byte
 *    (payload offset 67: bit 7 = charging, bits 0-6 = percent).
 *  - Dragy has no battery in its telemetry stream; the connection manager
 *    reads the FD04 device-status characteristic and hands the bytes to
 *    [DragyAdapter.onPollResult], which folds the percent (byte[1]) into the
 *    next decoded sample.
 */
class ExternalGpsBatteryTest {

    /** Wrap a payload in a valid UBX frame with a correct Fletcher-8 checksum. */
    private fun ubx(cls: Int, id: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(6 + payload.size + 2)
        frame[0] = 0xB5.toByte(); frame[1] = 0x62
        frame[2] = cls.toByte(); frame[3] = id.toByte()
        frame[4] = (payload.size and 0xFF).toByte()
        frame[5] = ((payload.size shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 6, payload.size)
        var a = 0; var b = 0
        for (i in 2 until frame.size - 2) {
            a = (a + (frame[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        frame[frame.size - 2] = a.toByte(); frame[frame.size - 1] = b.toByte()
        return frame
    }

    @Test fun `racebox extended frame decodes battery percent and charging`() {
        val payload = ByteArray(80)
        payload[20] = 3                          // fixType = 3D (else no sample)
        payload[67] = (0x80 or 73).toByte()      // charging flag + 73%
        val sample = RaceBoxAdapter().decode(ubx(0xFF, 0x01, payload))
        assertNotNull(sample)
        assertEquals(73, sample!!.batteryPercent)
        assertEquals(true, sample.charging)
    }

    @Test fun `racebox battery reads not-charging`() {
        val payload = ByteArray(80)
        payload[20] = 3
        payload[67] = 42                          // 42%, bit 7 clear
        val sample = RaceBoxAdapter().decode(ubx(0xFF, 0x01, payload))
        assertEquals(42, sample!!.batteryPercent)
        assertEquals(false, sample.charging)
    }

    @Test fun `dragy has no battery until FD04 is polled, then folds it in`() {
        val a = DragyAdapter()
        val nav = ByteArray(92).also { it[20] = 3; it[23] = 9 }  // 3D fix, 9 sats

        val before = a.decode(ubx(0x01, 0x07, nav))
        assertNotNull(before)
        assertNull("no battery before the first FD04 read", before!!.batteryPercent)

        a.onPollResult(byteArrayOf(0x00, 0x55))   // device-status: byte[1] = 0x55 = 85%
        val after = a.decode(ubx(0x01, 0x07, nav))
        assertEquals(85, after!!.batteryPercent)
        assertNull("Dragy status byte is percent-only", after.charging)
    }

    @Test fun `dragy ignores a too-short FD04 read`() {
        val a = DragyAdapter()
        a.onPollResult(byteArrayOf(0x00))         // only 1 byte, no battery field
        val nav = ByteArray(92).also { it[20] = 3 }
        assertNull(a.decode(ubx(0x01, 0x07, nav))!!.batteryPercent)
    }
}
