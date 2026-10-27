package com.eried.eucplanet.ble

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InMotion V6 dialect, locked to REAL frames from the rider-labelled capture
 * of the official app against V6-700326F3 (btsnoop + two annotated
 * screenshots). The two telemetry bodies below are verbatim payloads of the
 * `21 02 84` extended reply, routing bytes stripped; the stats body is the
 * first `21 02 91` payload of the same session.
 */
class InMotionV6Test {

    // Moving frame near the "5.6 km/h / 68 %" screenshot moment.
    private val movingBody = byteArrayOf(-15, 19, 18, 0, 56, 2, 81, 0, -49, 7, 9, 0, 5, 0, 0, 0, 119, 0, 100, 0, 127, 0, 5, 0, 5, 27, 98, 26, -72, 11, 58, 9, -36, 5, 0, 0, 0, 0, 0, 0, -49, -80, 0, -49, -80, -52, -80, 0, 0, 0, 0, 0, 0, 0, 73, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    // Late idle frame at the "49.3 V" end label (trip = the full 2.56 km).
    private val idleBody = byteArrayOf(56, 19, 20, 0, 0, 0, 20, -1, -46, 1, 0, 0, 0, 0, 0, 0, 75, 0, 88, 0, -7, 3, 0, 1, 72, 19, 61, 18, -72, 11, 101, 7, -36, 5, 0, 0, 0, 0, 0, 0, -33, -80, 0, -35, -80, -38, -80, 0, 0, 0, 0, 0, 0, 0, 73, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private val statsBody = byteArrayOf(-15, 1, 0, 0, 61, 87, 0, 0, 63, 6, 0, 0, -53, 8, 0, 0, -105, 65, 0, 0, 47, -1, 0, 0)

    @Test
    fun v6NameRule_matchesTheAdvertisedName_andNothingElse() {
        assertTrue(InMotionV2Adapter.isV6NameForTest("V6-700326F3"))
        assertTrue(InMotionV2Adapter.isV6NameForTest("v6-virtual1"))
        assertFalse(InMotionV2Adapter.isV6NameForTest("Adventure-123"))
        assertFalse(InMotionV2Adapter.isV6NameForTest("V14-ABCD"))
        assertFalse(InMotionV2Adapter.isV6NameForTest("KV6X"))
        assertFalse(InMotionV2Adapter.isV6NameForTest(null))
    }

    @Test
    fun parseV6Telemetry_movingFrame_readsTheLabelledValues() {
        val d = InMotionV2Parser.parseV6Telemetry(movingBody)
        assertNotNull(d)
        assertEquals(51.05f, d!!.voltage, 0.001f)
        assertEquals(0.18f, d.current, 0.001f)
        assertEquals(5.68f, d.speed, 0.001f)
        assertEquals(0.05f, d.tripDistance, 0.001f)
        // (51.05 - 44.4) * 10 on the two-point 13S line from the screenshots;
        // the Float product lands at 66.4999 so it rounds down.
        assertEquals(66, d.batteryPercent)
        assertEquals(28f, d.maxTemperature, 0.001f)
        assertEquals(1, d.pcMode)
        // PWM against the wheel's own 30 km/h ceiling: 5.68 km/h is 18.9 % of
        // it, and the wheel reports 19.99 %.
        assertEquals(19.99f, d.pwm, 0.001f)
    }

    @Test
    fun parseV6Telemetry_lateIdleFrame_matchesTheEndLabels() {
        val d = InMotionV2Parser.parseV6Telemetry(idleBody)
        assertNotNull(d)
        assertEquals(49.20f, d!!.voltage, 0.001f)
        assertEquals(0.0f, d.speed, 0.001f)
        // The session trip the odometer delta confirmed: 2.56 km.
        assertEquals(2.56f, d.tripDistance, 0.001f)
        assertEquals(48, d.batteryPercent)
        assertEquals(42f, d.maxTemperature, 0.001f)
        // The end label was taken still standing on the wheel at 0 km/h.
        assertEquals(1, d.pcMode)
        // Balancing a stationary rider still costs a few percent of duty.
        assertEquals(4.66f, d.pwm, 0.001f)
    }

    @Test
    fun v6Connect_enablesTheSessionHandshake_andDisconnectClearsIt() {
        // The capture shows the official app repeating the auth echo every
        // ~6 s for the whole ride; without it the real wheel stops answering
        // (the "still disconnects" tester report). The repository runs that
        // exchange only when requiresConnectAuth() says so.
        val adapter = InMotionV2Adapter()
        adapter.notifyConnectingTo("V6-700326F3")
        assertTrue(adapter.requiresConnectAuth())
        adapter.onDisconnect()
        assertFalse(adapter.requiresConnectAuth())
        // The V14 path never runs the connect handshake.
        adapter.notifyConnectingTo("V14-ABCD")
        assertFalse(adapter.requiresConnectAuth())
    }

    @Test
    fun v6WriteCharacteristic_isNoResponseOnly_soTheWriteTypeSwitches() {
        // The tester's wheel answered nothing at all: its Nordic UART RX
        // characteristic advertises properties 0x04 (write-no-response only,
        // straight from the capture's GATT discovery), so every
        // write-with-response we sent died before reaching the firmware.
        val noResponseOnly = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            BleProfile.writeTypeFor(BleProfile.NORDIC_UART.writeType, noResponseOnly)
        )
        // A characteristic that supports write-with-response keeps the
        // profile's type: InMotion V1 must keep the ATT retransmit, and the
        // V11-V14 / P6 firmware is unaffected.
        val both = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        assertEquals(
            BleProfile.INMOTION_V1.writeType,
            BleProfile.writeTypeFor(BleProfile.INMOTION_V1.writeType, both)
        )
        assertEquals(
            BleProfile.NORDIC_UART.writeType,
            BleProfile.writeTypeFor(
                BleProfile.NORDIC_UART.writeType, BluetoothGattCharacteristic.PROPERTY_WRITE
            )
        )
        // Unknown properties leave the profile in charge.
        assertEquals(
            BleProfile.NORDIC_UART.writeType,
            BleProfile.writeTypeFor(BleProfile.NORDIC_UART.writeType, 0)
        )
    }

    @Test
    fun v6Handshake_roundTripsThroughTheSimulatorAndAdapter() {
        // Full loop the repository drives: query -> key -> echo -> ack, using
        // the same command builders and decode path as a live connection.
        val adapter = InMotionV2Adapter()
        adapter.notifyConnectingTo("V6-VIRTUAL1")
        val sim = com.eried.eucplanet.ble.virtual.V6VirtualWheel()

        val keyReplies = sim.onWrite(InMotionV2Commands.requestAuthKey())
        assertEquals(1, keyReplies.size)
        val keyResult = adapter.onRawNotification(keyReplies[0])
            .filterIsInstance<DecodeResult.AuthKey>().single()
        assertEquals(16, keyResult.encryptedKey.size)

        val ackReplies = sim.onWrite(InMotionV2Commands.verifyAuth(keyResult.encryptedKey))
        assertEquals(1, ackReplies.size)
        val confirm = adapter.onRawNotification(ackReplies[0])
            .filterIsInstance<DecodeResult.AuthConfirm>().single()
        assertTrue(confirm.success)

        // A wrong echo is refused, mirroring the real wheel's gate.
        val badAck = sim.onWrite(InMotionV2Commands.verifyAuth(ByteArray(16)))
        val badConfirm = adapter.onRawNotification(badAck.single())
            .filterIsInstance<DecodeResult.AuthConfirm>().single()
        assertFalse(badConfirm.success)
    }

    @Test
    fun parseV6Telemetry_riderLifted_readsIdleNotLock() {
        // Same frame with the rider marker cleared. Must read idle (3),
        // never lock (0): the lock chip treats pcMode 0 as "wheel locked".
        val lifted = idleBody.copyOf().also { it[54] = 0x00 }
        assertEquals(3, InMotionV2Parser.parseV6Telemetry(lifted)!!.pcMode)
    }

    @Test
    fun parseV6TotalKm_firstStatsFrame_readsTheOdometer() {
        // 497 raw = 4.97 km; the app showed 5.0 km before the ride.
        assertEquals(4.97f, InMotionV2Parser.parseV6TotalKm(statsBody)!!, 0.001f)
    }

    @Test
    fun carTypeReply_mapsToTheV6ModelEntry() {
        // Reply body after the sub-cmd echo: mainSeries 02, series 0e, type 01.
        val info = InMotionV2Parser.parseCarType(byteArrayOf(0x02, 0x0e, 0x01, 0x01, 0x01, 0x00))
        assertNotNull(info)
        assertEquals(141, info!!.modelId)
        assertEquals(InMotionV2Model.V6, info.model)
    }

    @Test
    fun v6Queries_haveTheCapturedWireBytes() {
        // Exactly the frames the official app sent, checksums included.
        assertEquals("aaaa130402000201 16", hex(InMotionV2Commands.getV6Info(0x01)))
        assertEquals("aaaa130402000202 15", hex(InMotionV2Commands.getV6Info(0x02)))
        assertEquals("aaaa160302210432", hex(InMotionV2Commands.getV6RealTimeData()).replace(" ", ""))
        assertEquals("aaaa160302211127", hex(InMotionV2Commands.getV6TotalStats()).replace(" ", ""))
    }

    @Test
    fun nameRouter_sendsV6ToTheV2Family_andOldSeriesStaysV1() {
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName("V6-700326F3"))
        assertEquals(WheelFamily.INMOTION_V1, wheelFamilyForName("V5F-1234"))
        assertEquals(WheelFamily.INMOTION_V1, wheelFamilyForName("V8-ABCD"))
        assertEquals(WheelFamily.INMOTION_V1, wheelFamilyForName("V10F-77"))
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName("V11-X"))
    }

    private fun hex(b: ByteArray): String {
        val s = b.joinToString("") { "%02x".format(it) }
        // Split the checksum for readability in the two 0x13 asserts.
        return if (s.length == 18) s.substring(0, 16) + " " + s.substring(16) else s
    }
}
