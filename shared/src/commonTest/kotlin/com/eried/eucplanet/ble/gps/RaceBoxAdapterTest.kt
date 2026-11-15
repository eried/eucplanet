package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RaceBox parser against whole frames written out byte for byte (sync,
 * class, id, length, payload, Fletcher-8), on both phones: the standard
 * UBX-NAV-PVT a box streams by default and the RaceBox Data Message
 * (0xFF 0x01) it streams with the G-force. The post-connect assistance
 * frames are pinned too, as the Android adapter wrote them before it moved
 * to shared code.
 */
class RaceBoxAdapterTest {

    private fun hex(s: String): ByteArray =
        s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** NAV-PVT: 3D fix, 11 sats, 59.9139 N 10.7387 E, 120 m, 1.5 m, 10 m/s, heading 90, 0.25 m/s up. */
    private val navPvt = hex(
        "B5 62 01 07 5C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03 00 00 0B " +
            "78 98 66 06 B8 22 B6 23 00 00 00 00 C0 D4 01 00 DC 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
            "06 FF FF FF 10 27 00 00 40 54 89 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
            "00 00 00 00 6E 35"
    )

    /** Data Message: 3D fix, 14 sats, 8.333 m/s at heading -5, 0.9 m, charging at 64%, G 0.12 / -0.05 / 1.0. */
    private val dataMessage = hex(
        "B5 62 FF 01 50 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03 00 00 0E " +
            "78 98 66 06 B8 22 B6 23 00 00 00 00 C0 D4 01 00 84 03 00 00 00 00 00 00 8D 20 00 00 E0 5E F8 FF " +
            "00 00 00 00 10 27 00 00 00 00 00 C0 78 00 CE FF E8 03 00 00 00 00 00 00 B5 39"
    )

    @Test fun `standard NAV-PVT decodes every field`() {
        val s = RaceBoxAdapter().decode(navPvt)
        assertNotNull(s)
        assertEquals(ExternalGpsSource.RACEBOX, s.source)
        assertEquals(36f, s.speedKmh, 0.01f)
        assertEquals(59.9139, s.latitude, 1e-6)
        assertEquals(10.7387, s.longitude, 1e-6)
        assertEquals(120f, s.altitudeMeters, 0.01f)
        assertEquals(1.5f, s.accuracyMeters, 0.001f)
        assertEquals(90f, s.headingDeg!!, 0.01f)
        assertEquals(0.25f, s.verticalSpeedMps!!, 0.001f)
        assertEquals(11, s.numSatellites)
        assertNull(s.accelXG, "the standard frame carries no G-force")
        assertNull(s.batteryPercent)
    }

    @Test fun `data message reads speed at 48 and heading at 52`() {
        val s = RaceBoxAdapter().decode(dataMessage)
        assertNotNull(s)
        // Offset 60 holds the heading accuracy (10000); reading speed there
        // was the 100-200 mph bug.
        assertEquals(29.9988f, s.speedKmh, 0.001f)
        assertEquals(355f, s.headingDeg!!, 0.01f)
        assertEquals(0.9f, s.accuracyMeters, 0.001f)
        assertEquals(14, s.numSatellites)
        assertEquals(64, s.batteryPercent)
        assertEquals(true, s.charging)
        assertEquals(0f, s.verticalSpeedMps)
    }

    @Test fun `data message G-force has gravity filtered out`() {
        val a = RaceBoxAdapter()
        // The first sample seeds the gravity estimate, so it reads level.
        val first = a.decode(dataMessage)!!
        assertEquals(0f, first.accelXG!!, 1e-6f)
        assertEquals(0f, first.accelZG!!, 1e-6f)
        // A steady box stays at zero once gravity is learned.
        repeat(50) { a.decode(dataMessage) }
        val later = a.decode(dataMessage)!!
        assertEquals(0f, later.accelXG!!, 1e-4f)
        assertEquals(0f, later.accelYG!!, 1e-4f)
        assertEquals(0f, later.accelZG!!, 1e-4f)
    }

    @Test fun `a frame in 20-byte notifications decodes on the last piece`() {
        val a = RaceBoxAdapter()
        val pieces = VirtualRaceBox.chunks(dataMessage)
        assertEquals(5, pieces.size)
        pieces.dropLast(1).forEach { assertNull(a.decode(it)) }
        assertNotNull(a.decode(pieces.last()))
    }

    @Test fun `bytes before the sync are skipped`() {
        val s = RaceBoxAdapter().decode(byteArrayOf(0x00, 0x13, 0x37) + navPvt)
        assertNotNull(s)
        assertEquals(36f, s.speedKmh, 0.01f)
    }

    @Test fun `a corrupted checksum and a frame without a fix give nothing`() {
        val bad = navPvt.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(RaceBoxAdapter().decode(bad))
        val noFix = navPvt.copyOf().also { it[6 + 20] = 0 }
        assertNull(RaceBoxAdapter().decode(noFix))
    }

    @Test fun `matches RaceBox names only`() {
        val a = RaceBoxAdapter()
        assertTrue(a.matches("RaceBox Mini 1234567"))
        assertTrue(a.matches("racebox mini s 42"))
        assertFalse(a.matches("Dragy"))
        assertFalse(a.matches("V14-123"))
        assertEquals(ExternalGpsGattProfile.NORDIC_UART, a.gattProfile())
    }

    @Test fun `init writes are MGA-INI-TIME_UTC then MGA-INI-POS_LLH`() {
        // 2026-11-15 12:34:56.789 UTC.
        val cmds = RaceBoxAdapter().initCommands(1_794_746_096_789L, 59.9139, 10.7387, 5f)
        assertEquals(2, cmds.size)
        assertContentEquals(
            hex("B5 62 13 40 18 00 10 00 00 12 EA 07 0B 0F 0C 22 38 00 40 2F 07 2F 0A 00 00 00 00 00 00 00 AD E0"),
            cmds[0],
        )
        assertContentEquals(
            hex("B5 62 13 40 14 00 01 00 00 00 B8 22 B6 23 78 98 66 06 00 00 00 00 F4 01 00 00 8C BA"),
            cmds[1],
        )
    }

    @Test fun `without a phone fix only the time goes out`() {
        val cmds = RaceBoxAdapter().initCommands(0L, null, null, null)
        assertEquals(1, cmds.size)
        assertEquals(0x10.toByte(), cmds[0][6])
        // 1970-01-01 00:00:00.
        assertEquals(1970, (cmds[0][10].toInt() and 0xFF) or ((cmds[0][11].toInt() and 0xFF) shl 8))
        assertEquals(1, cmds[0][12].toInt())
        assertEquals(1, cmds[0][13].toInt())
    }

    @Test fun `utc fields match the calendar across leap days and before 1970`() {
        assertEquals(UtcTime(2024, 2, 29, 23, 59, 59, 999), UtcTime.of(1_709_251_199_999L))
        assertEquals(UtcTime(2024, 3, 1, 0, 0, 0, 0), UtcTime.of(1_709_251_200_000L))
        assertEquals(UtcTime(2000, 1, 1, 0, 0, 0, 0), UtcTime.of(946_684_800_000L))
        assertEquals(UtcTime(1969, 12, 31, 23, 59, 59, 999), UtcTime.of(-1L))
    }
}
