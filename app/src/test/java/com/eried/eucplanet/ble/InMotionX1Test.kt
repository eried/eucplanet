package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InMotion X1, locked to REAL frames from a tester's Service Mode log
 * (2026-10-05, app 0.22.0-beta1, firmware Main 1.10.20 Drv 8.5.3). Every hex
 * string below is a RECV line copied verbatim from that log.
 */
class InMotionX1Test {

    private fun hex(s: String) = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    // RECV 57: the reply to the V14 settings poll `14 02 20 20`.
    private val settingsFrame = hex("aa aa 14 34 a0 20 00 00 00 00 00 00 00 00 c0 5d df 2e e0 2e 28 23 34 21 28 23 00 00 58 02 10 32 32 14 03 0a 0c 64 64 28 d0 07 32 32 20 4e 64 41 64 a0 3c 07 31 36 00 16 16")

    // RECV 120: pack 0 (0x24) cells, 56 of them.
    private val pack0Frame = hex("aa aa 16 73 24 02 82 29 10 2a 10 29 10 28 10 27 10 2a 10 2a 10 28 10 2a 10 1e 10 2a 10 29 10 27 10 25 10 29 10 26 10 26 10 24 10 28 10 27 10 28 10 27 10 2a 10 27 10 28 10 27 10 27 10 28 10 1f 10 2b 10 2a 10 27 10 26 10 27 10 2a 10 27 10 29 10 27 10 26 10 25 10 28 10 26 10 28 10 2a 10 29 10 28 10 28 10 26 10 26 10 29 10 28 10 26 10 27 10 28 10 27 10 27 10 cc")

    // RECV 92: realtime while moving, 17:56:01.
    private val realtimeMoving = hex("aa aa 14 57 84 ac 5a bc ff 00 00 00 00 73 04 00 00 1f fa 46 00 63 ff 41 ff ca ff d5 fc 00 00 00 00 01 00 00 00 fc fa 0c 27 41 26 98 3a e0 2e df 2e 10 27 34 21 e0 2e e0 2e 50 c3 00 00 00 00 d5 d7 00 dc b0 db d3 dc b0 d0 00 02 00 00 00 00 49 00 00 00 00 00 00 00 00 00 00 00 6e")

    private fun carType(series: Int, type: Int): ByteArray =
        InMotionV2Protocol.buildPacket(0x14, 0x82.toByte(), byteArrayOf(0x01, 0x01, series.toByte(), type.toByte(), 0, 0, 0))

    private fun x1Adapter() = InMotionV2Adapter().also { a ->
        val r = a.onRawNotification(carType(17, 1))
        val name = r.filterIsInstance<DecodeResult.ModelName>().single()
        assertEquals("InMotion X1", name.name)
        assertEquals(InMotionV2Model.X1, name.model)
    }

    @Test
    fun modelId171_isTheX1() {
        assertEquals(InMotionV2Model.X1, InMotionV2Model.fromId(171))
    }

    @Test
    fun settings_readTheP6Page_notZero() {
        val s = x1Adapter().onRawNotification(settingsFrame)
            .filterIsInstance<DecodeResult.Settings>().single().data
        // The V14 parser read 0 km/h here; the P6 offsets give the real values.
        assertEquals(240.00f, s.maxSpeedKmh, 0.001f)
        assertEquals(119.99f, s.alarmSpeedKmh, 0.001f)
    }

    @Test
    fun speedWrites_goTheP6Way() {
        val a = x1Adapter()
        assertTrue(a.setMaxSpeed(60f, 55f).contentEquals(InMotionV2Commands.setP6MaxSpeed(60f)))
        assertTrue(a.setAlarmSpeedCommit(55f)!!.contentEquals(InMotionV2Commands.setP6AlarmSpeed(55f)))
    }

    @Test
    fun packCells_all56() {
        val bms = x1Adapter().onRawNotification(pack0Frame)
            .filterIsInstance<DecodeResult.Bms>().single()
        assertEquals(56, bms.cellVoltages!!.size)
        assertEquals(4.137f, bms.cellVoltages!!.first(), 0.0005f)
        // 56 cells at ~4.136 V is the 231.6 V pack the wheel reported.
        assertEquals(231.6f, bms.cellVoltages!!.sum(), 0.2f)
    }

    @Test
    fun packPolls_onlyTheTwoPacksThatAnswer() {
        val a = x1Adapter()
        val packs = (1..8).map { a.pollStats()!![5].toInt() and 0xFF }.toSet()
        assertEquals(setOf(0x24, 0x25), packs)
    }

    @Test
    fun realtime_dropsTheTwoEmptySensorSlots() {
        val t = x1Adapter().onRawNotification(realtimeMoving)
            .filterIsInstance<DecodeResult.Telemetry>().single().data
        assertEquals(4, t.temperatures.size)
        assertFalse(t.temperatures.any { it < -40f || it == 0f })
        assertTrue(t.speed > 10f)
    }

    @Test
    fun aV14_isUntouched() {
        val a = InMotionV2Adapter()
        a.onRawNotification(carType(9, 1))
        val packs = (1..8).map { a.pollStats()!![5].toInt() and 0xFF }.toSet()
        assertEquals(setOf(0x24, 0x25, 0x26, 0x27), packs)
        assertTrue(a.setMaxSpeed(60f, 55f).contentEquals(InMotionV2Commands.setMaxSpeedV14(60f, 55f)))
        assertEquals(null, a.setAlarmSpeedCommit(55f))
        assertNotNull(a)
    }
}
