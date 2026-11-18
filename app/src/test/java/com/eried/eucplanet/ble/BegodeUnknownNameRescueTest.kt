package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Begode whose name we have never heard of still shows its speed (issue #34,
 * the Panther: "connects but no speed information is displayed").
 *
 * Begode, KingSong and Veteran all sit on the same HM-10 Bluetooth profile, so
 * the profile cannot say which one is on the line. The name router knows the
 * Begode names it was taught; anything else falls to the InMotion default,
 * finds no InMotion service, and the service rescue guesses KingSong, the
 * common blank-named case. A Begode stream fed to the KingSong parser decodes
 * to nothing at all, which is exactly a connected wheel with every tile empty.
 *
 * The first frame settles it: KingSong opens `AA 55`, Begode opens `55 AA`.
 */
class BegodeUnknownNameRescueTest {

    private fun composite() = CompositeWheelAdapter(
        InMotionV2Adapter(),
        InMotionV1Adapter(),
        KingsongAdapter(),
        BegodeAdapter(),
        VeteranAdapter(),
        NinebotAdapter(),
    )

    /** A 24-byte Begode live frame at 42 km/h: 55 AA, payload, tag 0 at 18, 5A 5A 5A 5A. */
    private fun liveFrame(): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55; f[1] = 0xAA.toByte()
        fun put(offset: Int, value: Int) {
            f[offset] = ((value shr 8) and 0xFF).toByte()
            f[offset + 1] = (value and 0xFF).toByte()
        }
        put(2, 6500)
        put(4, (42f * 100f / 3.6f).toInt())
        put(10, 1200)
        for (i in 20..23) f[i] = 0x5A
        return f
    }

    /** Connect the way the app does for a name no router knows, on a bare HM-10 wheel. */
    private fun connectUnknownHm10(name: String): CompositeWheelAdapter = composite().apply {
        notifyConnectingTo(name)
        assertTrue(pickAdapterByDiscoveredServices(setOf(BleProfile.HM10.serviceUuid), name))
    }

    @Test fun `an unknown name on HM-10 starts as the KingSong guess`() {
        assertEquals("kingsong", connectUnknownHm10("Panther_1234").familyId)
    }

    @Test fun `the first Begode frame moves it to the Begode adapter and the speed decodes`() {
        val c = connectUnknownHm10("Panther_1234")
        val frame = liveFrame()
        // The radio delivers 20 bytes at a time, so a 24-byte frame arrives in two pieces.
        val results = c.onRawNotification(frame.copyOfRange(0, 20)) +
            c.onRawNotification(frame.copyOfRange(20, 24))
        assertEquals("begode", c.familyId)
        val speed = results.filterIsInstance<DecodeResult.Telemetry>().last().data.speed
        assertEquals(42f, kotlin.math.abs(speed), 0.5f)
    }

    @Test fun `a KingSong frame leaves a KingSong where it is`() {
        val c = connectUnknownHm10("RW")
        val ks = ByteArray(20).also {
            it[0] = 0xAA.toByte(); it[1] = 0x55
            it[16] = 0xA9.toByte(); it[18] = 0x5A; it[19] = 0x5A
        }
        c.onRawNotification(ks)
        assertEquals("kingsong", c.familyId)
    }

    @Test fun `a wheel named as KingSong is not moved by stray bytes that are not a frame start`() {
        val c = composite().apply { notifyConnectingTo("KS-S22-1234") }
        // 55 AA in the middle of a notification is data, not a header.
        c.onRawNotification(byteArrayOf(0xAA.toByte(), 0x55, 0x55, 0xAA.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xA9.toByte(), 0, 0x5A, 0x5A))
        assertEquals("kingsong", c.familyId)
    }
}
