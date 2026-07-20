package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Dragy adapter's u-blox UBX decode against synthetic frames.
 * Dragy has no public spec; the format here (Nordic UART + UBX NAV-PVT, or
 * NAV-POSLLH + NAV-VELNED + NAV-SAT) is derived from its u-blox module and the
 * OpenDragy DIY clone, and MUST be confirmed against a real Dragy Lite. These
 * tests lock in the parser math so a confirmed capture only has to adjust the
 * message set, not re-derive the offsets.
 */
class DragyAdapterTest {

    private fun i32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

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

    private fun set(payload: ByteArray, off: Int, bytes: ByteArray) =
        System.arraycopy(bytes, 0, payload, off, bytes.size)

    @Test fun `matches Dragy and DRG names, not others`() {
        val a = DragyAdapter()
        assertTrue(a.matches("Dragy"))
        assertTrue(a.matches("DRAGY 12345"))
        assertTrue(a.matches("Dragy Lite"))
        assertTrue(a.matches("DRG70-ABCD"))
        assertFalse(a.matches("RaceBox Mini 123"))
        assertFalse(a.matches("Adventure-1234"))
    }

    @Test fun `decodes NAV-PVT into a sample`() {
        val payload = ByteArray(92)
        payload[20] = 3                       // fixType = 3D
        payload[23] = 11                      // numSV
        set(payload, 24, i32((10.7387 * 1e7).toInt()))  // lon
        set(payload, 28, i32((59.9139 * 1e7).toInt()))  // lat
        set(payload, 36, i32(120_000))        // hMSL = 120 m in mm
        set(payload, 40, i32(1_500))          // hAcc = 1.5 m in mm
        set(payload, 60, i32(10_000))         // gSpeed = 10 m/s in mm/s -> 36 km/h

        val sample = DragyAdapter().decode(ubx(0x01, 0x07, payload))
        assertNotNull(sample)
        assertEquals(ExternalGpsSource.DRAGY, sample!!.source)
        assertEquals(36f, sample.speedKmh, 0.1f)
        assertEquals(59.9139, sample.latitude, 1e-4)
        assertEquals(10.7387, sample.longitude, 1e-4)
        assertEquals(120f, sample.altitudeMeters, 0.5f)
        assertEquals(11, sample.numSatellites)
    }

    @Test fun `merges POSLLH plus VELNED plus SAT into a sample`() {
        val a = DragyAdapter()

        // SAT first so the fix-proxy passes (8 sats).
        val sat = ByteArray(8); sat[5] = 8
        assertNull("SAT alone is not a fix update", a.decode(ubx(0x01, 0x35, sat)))

        // POSLLH: lon@4, lat@8, hMSL@16, hAcc@20.
        val pos = ByteArray(28)
        set(pos, 4, i32((10.0 * 1e7).toInt()))
        set(pos, 8, i32((60.0 * 1e7).toInt()))
        set(pos, 16, i32(50_000))    // 50 m
        set(pos, 20, i32(2_000))     // 2 m
        val afterPos = a.decode(ubx(0x01, 0x02, pos))
        assertNotNull("POSLLH with a good position + sats should emit", afterPos)
        assertEquals(60.0, afterPos!!.latitude, 1e-4)

        // VELNED: gSpeed@20 in cm/s. 1000 cm/s = 10 m/s = 36 km/h.
        val vel = ByteArray(36)
        set(vel, 20, i32(1000))
        val afterVel = a.decode(ubx(0x01, 0x12, vel))
        assertNotNull(afterVel)
        assertEquals(36f, afterVel!!.speedKmh, 0.1f)
        assertEquals(8, afterVel.numSatellites)
    }

    @Test fun `rejects a frame with a bad checksum`() {
        val payload = ByteArray(92); payload[20] = 3
        val frame = ubx(0x01, 0x07, payload)
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()  // corrupt CK_B
        assertNull(DragyAdapter().decode(frame))
    }

    @Test fun `reassembles a frame split across notifications`() {
        val payload = ByteArray(92)
        payload[20] = 3; payload[23] = 7
        set(payload, 60, i32(5_000))   // 5 m/s -> 18 km/h
        val frame = ubx(0x01, 0x07, payload)
        val a = DragyAdapter()
        assertNull("partial frame yields nothing yet", a.decode(frame.copyOfRange(0, 40)))
        val sample = a.decode(frame.copyOfRange(40, frame.size))
        assertNotNull(sample)
        assertEquals(18f, sample!!.speedKmh, 0.1f)
    }
}
