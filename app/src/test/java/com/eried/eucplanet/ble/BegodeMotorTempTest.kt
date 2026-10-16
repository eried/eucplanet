package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.MetricRegistry
import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Begode temperatures land in the app's slots: [0] motor, [1] controller.
 * A rider on an Xmax saw the Motor temp tile show the controller (board)
 * temperature: Live A sent only the board reading, in slot 0, and 0x07 sent
 * [board, motor], so the motor value sat in the controller slot and the next
 * Live A frame wiped it. Motor temperature is 0x07 bytes 6..7, whole degrees
 * C, and only wheels with a motor NTC fill it.
 */
class BegodeMotorTempTest {

    private fun frame(tag: Int, fill: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55; f[1] = 0xAA.toByte()
        fill(f)
        f[18] = tag.toByte()
        for (i in 20..23) f[i] = 0x5A
        return f
    }

    private fun ByteArray.putI16(offset: Int, value: Int) {
        val v = value and 0xFFFF
        this[offset] = ((v shr 8) and 0xFF).toByte()
        this[offset + 1] = (v and 0xFF).toByte()
    }

    /** Live A with the IMU register for a ~32 C main board. */
    private fun liveA() = frame(0x00) { f ->
        f.putI16(2, 6500)
        f.putI16(12, ((32.1f - 36.53f) * 340f).toInt())
    }

    private fun extras(motorC: Int) = frame(0x07) { f -> f.putI16(6, motorC) }

    private fun telemetry(results: List<DecodeResult>): WheelData =
        results.filterIsInstance<DecodeResult.Telemetry>().last().data

    @Test fun `a wheel with a motor sensor shows motor and board in their own tiles`() {
        val p = BegodeParser()
        p.feed(liveA(), BegodeModel.MASTER)
        val onExtras = telemetry(p.feed(extras(41), BegodeModel.MASTER))
        assertEquals(41f, MetricRegistry.read("MOTOR_TEMP", onExtras)!!, 0.01f)
        assertEquals(32.1f, MetricRegistry.read("CONTROLLER_TEMP", onExtras)!!, 0.1f)
        assertEquals(41f, onExtras.maxTemperature, 0.01f)
    }

    @Test fun `the 10 Hz Live A frames keep the motor reading instead of wiping it`() {
        val p = BegodeParser()
        p.feed(extras(41), BegodeModel.MASTER)
        val onLiveA = telemetry(p.feed(liveA(), BegodeModel.MASTER))
        assertEquals(41f, MetricRegistry.read("MOTOR_TEMP", onLiveA)!!, 0.01f)
        assertEquals(32.1f, MetricRegistry.read("CONTROLLER_TEMP", onLiveA)!!, 0.1f)
    }

    @Test fun `a wheel without a motor sensor shows no motor temperature, not the board's`() {
        val p = BegodeParser()
        p.feed(liveA(), BegodeModel.MASTER)
        val t = telemetry(p.feed(extras(0), BegodeModel.MASTER))
        assertNull(MetricRegistry.read("MOTOR_TEMP", t))
        assertEquals(32.1f, MetricRegistry.read("CONTROLLER_TEMP", t)!!, 0.1f)
        assertEquals(32.1f, t.maxTemperature, 0.1f)
        // Old firmware never sends 0x07 at all.
        val old = BegodeParser()
        assertNull(MetricRegistry.read("MOTOR_TEMP", telemetry(old.feed(liveA(), BegodeModel.MASTER))))
    }

    @Test fun `once the sensor has read, a later reading still follows it`() {
        val p = BegodeParser()
        p.feed(extras(41), BegodeModel.MASTER)
        assertEquals(44f, MetricRegistry.read("MOTOR_TEMP", telemetry(p.feed(extras(44), BegodeModel.MASTER)))!!, 0.01f)
        p.reset()
        assertNull(MetricRegistry.read("MOTOR_TEMP", telemetry(p.feed(liveA(), BegodeModel.MASTER))))
    }

    @Test fun `an impossible reading neither arms the sensor nor reaches the alarm`() {
        val p = BegodeParser()
        p.feed(liveA(), BegodeModel.MASTER)
        val bad = telemetry(p.feed(extras(32767), BegodeModel.MASTER))
        assertNull(MetricRegistry.read("MOTOR_TEMP", bad))
        assertEquals(32.1f, bad.maxTemperature, 0.1f)
        p.feed(extras(41), BegodeModel.MASTER)
        val kept = telemetry(p.feed(extras(32767), BegodeModel.MASTER))
        assertEquals(41f, MetricRegistry.read("MOTOR_TEMP", kept)!!, 0.01f)
        assertEquals(41f, kept.maxTemperature, 0.01f)
    }
}
