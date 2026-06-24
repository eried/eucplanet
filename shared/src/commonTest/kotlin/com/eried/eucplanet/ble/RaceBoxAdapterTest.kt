package com.eried.eucplanet.ble

import com.eried.eucplanet.ble.extgps.RaceBoxAdapter
import com.eried.eucplanet.data.ExternalGpsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies the ported UBX-NAV-PVT parsing against a hand-built frame. */
class RaceBoxAdapterTest {

    private fun writeI32LE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** Wrap a payload into a complete, Fletcher-8-checksummed UBX frame. */
    private fun ubx(cls: Int, id: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(6 + payload.size + 2)
        frame[0] = 0xB5.toByte(); frame[1] = 0x62
        frame[2] = cls.toByte(); frame[3] = id.toByte()
        frame[4] = (payload.size and 0xFF).toByte()
        frame[5] = ((payload.size ushr 8) and 0xFF).toByte()
        payload.copyInto(frame, 6)
        var ckA = 0; var ckB = 0
        for (i in 2 until frame.size - 2) {
            ckA = (ckA + (frame[i].toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        frame[frame.size - 2] = ckA.toByte(); frame[frame.size - 1] = ckB.toByte()
        return frame
    }

    private fun navPvt(fixType: Int, numSV: Int, lonE7: Int, latE7: Int, hMslMm: Int, gSpeedMmS: Int, headE5: Int): ByteArray {
        val payload = ByteArray(92)
        payload[20] = fixType.toByte()
        payload[23] = numSV.toByte()
        writeI32LE(payload, 24, lonE7)
        writeI32LE(payload, 28, latE7)
        writeI32LE(payload, 36, hMslMm)
        writeI32LE(payload, 60, gSpeedMmS)
        writeI32LE(payload, 64, headE5)
        return ubx(0x01, 0x07, payload)
    }

    @Test
    fun decodesNavPvt() {
        val a = RaceBoxAdapter()
        // 41.4° lat, 2.17° lon, 100 m, 10000 mm/s (= 36 km/h), heading 90°, 11 sats, 3D fix.
        val frame = navPvt(3, 11, 21_700_000, 414_000_000, 100_000, 10_000, 9_000_000)
        val s = a.decode(frame)
        assertNotNull(s)
        assertEquals(ExternalGpsSource.RACEBOX, s.source)
        assertEquals(36.0f, s.speedKmh, 0.01f)
        assertTrue(kotlin.math.abs(s.latitude - 41.4) < 1e-5, "lat=${s.latitude}")
        assertTrue(kotlin.math.abs(s.longitude - 2.17) < 1e-5, "lon=${s.longitude}")
        assertEquals(100f, s.altitudeMeters, 0.01f)
        assertEquals(90f, s.headingDeg!!, 0.1f)
        assertEquals(11, s.numSatellites)
    }

    @Test
    fun reassemblesAcrossChunks() {
        val a = RaceBoxAdapter()
        val frame = navPvt(3, 8, 0, 0, 0, 5_000, 0)
        // Feed in two BLE-sized chunks; only the second completes the frame.
        assertNull(a.decode(frame.copyOfRange(0, 20)))
        val s = a.decode(frame.copyOfRange(20, frame.size))
        assertNotNull(s)
        assertEquals(18.0f, s.speedKmh, 0.01f) // 5000 mm/s * 0.0036
    }

    @Test
    fun rejectsNoFix() {
        val a = RaceBoxAdapter()
        // fixType 0 = no fix → dropped.
        assertNull(a.decode(navPvt(0, 0, 0, 0, 0, 9999, 0)))
    }

    @Test
    fun rejectsBadChecksum() {
        val a = RaceBoxAdapter()
        val frame = navPvt(3, 8, 0, 0, 0, 5000, 0)
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte() // corrupt ckB
        assertNull(a.decode(frame))
    }
}
