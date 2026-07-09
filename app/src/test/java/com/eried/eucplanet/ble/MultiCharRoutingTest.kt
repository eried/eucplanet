package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Guards the multi-characteristic extension against regressing the six
 * single-stream families (the one the rider tests: InMotion V14).
 *
 * The extension routes notifications through [WheelAdapter.onCharacteristicData]
 * and lets an adapter declare extra notify + read characteristics. For every
 * existing family those must resolve to the OLD behaviour: one notify char, no
 * reads, and onCharacteristicData decoding a frame exactly as onRawNotification
 * did. Only IPS (characteristic-per-value) opts into the new path.
 */
class MultiCharRoutingTest {

    private fun composite() = CompositeWheelAdapter(
        InMotionV2Adapter(), InMotionV1Adapter(), KingsongAdapter(),
        BegodeAdapter(), VeteranAdapter(), NinebotAdapter(), IpsAdapter()
    )

    /** A 20-byte KingSong telemetry frame (type byte at offset 16). */
    private fun ksFrame(type: Int): ByteArray = ByteArray(20).apply {
        this[0] = 0xAA.toByte(); this[1] = 0x55.toByte()
        this[16] = type.toByte(); this[18] = 0x5A; this[19] = 0x5A
    }

    @Test fun onCharacteristicData_routes_identically_to_onRawNotification() {
        // Same frame, two fresh single-stream adapters: the new per-UUID entry
        // point must produce the same result shape the old one did. If the
        // routing dropped or altered frames, the rider's V14 would "connect but
        // show nothing" - this catches exactly that.
        val viaRaw = KingsongAdapter().onRawNotification(ksFrame(0xA9))
        val viaData = KingsongAdapter().onCharacteristicData(UUID.randomUUID(), ksFrame(0xA9))
        assertEquals(
            viaRaw.map { it::class.java },
            viaData.map { it::class.java }
        )
    }

    @Test fun composite_keeps_single_stream_families_off_the_multichar_path() {
        // InMotion V14 (Adventure-*): one notify char, no reads -> the
        // connection manager's `multiChar` flag stays false and none of the new
        // subscribe/read machinery runs.
        val c = composite()
        c.notifyConnectingTo("Adventure-1234")
        assertEquals(
            listOf(BleProfile.NORDIC_UART.notifyCharacteristic),
            c.notifyCharacteristics()
        )
        assertTrue("single-stream wheels must not declare reads",
            c.readCharacteristics().isEmpty())
    }

    @Test fun composite_routes_ips_onto_the_multichar_path() {
        // IPS i5: several notify chars + a read set, so multiChar activates.
        // This is what the delegation fix enables - without it the Composite
        // reported the single-stream defaults and the i5 got nothing.
        val c = composite()
        c.notifyConnectingTo("i5-0001")
        assertTrue("IPS must declare more than one notify char",
            c.notifyCharacteristics().size > 1)
        assertTrue("IPS must declare read characteristics",
            c.readCharacteristics().isNotEmpty())
    }

    @Test fun composite_delegates_decoding_to_the_active_family() {
        // A KingSong frame through the Composite's per-UUID entry point must
        // still reach the KingSong parser (delegation, not the interface
        // default).
        val c = composite()
        c.notifyConnectingTo("KS-S22")
        val viaData = c.onCharacteristicData(UUID.randomUUID(), ksFrame(0xA9))
        val viaRaw = KingsongAdapter().onRawNotification(ksFrame(0xA9))
        assertEquals(viaRaw.map { it::class.java }, viaData.map { it::class.java })
    }
}
