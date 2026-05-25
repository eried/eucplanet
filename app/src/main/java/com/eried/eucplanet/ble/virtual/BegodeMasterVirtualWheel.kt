package com.eried.eucplanet.ble.virtual

import kotlin.math.PI
import kotlin.math.sin

/**
 * Software fake of a Begode Master (134 V class) with the **inverted motor
 * wiring** failure mode that real-world Masters occasionally ship with: the
 * speed field is sent sign-flipped so forward riding lands at WheelData.speed
 * as a negative i16. The point of the simulator is to verify that the abs()
 * applied in WheelRepository at the apply site keeps the dial, voice, alarms
 * and watch on the positive side regardless of what the firmware sent. It is
 * the same payload as a stock Master otherwise (~123 V battery, plausible
 * phase current, IMU temperature).
 *
 * Routes via [bleName] "Master_VIRTUAL" so CompositeWheelAdapter.pickAdapter
 * picks Begode and BegodeModel.fromReportedName picks MASTER (134 V ratio
 * 2.0, derived-PWM constants 113 km/h @ 134.4 V). The display name is the
 * stock "Virtual Begode Master" so the rider sees this as a normal Master in
 * the wheel picker; the inversion is a hardware bug we're simulating, not a
 * branded variant.
 */
class BegodeMasterVirtualWheel : VirtualWheel {

    override val displayName = "Virtual Begode Master"
    override val id = "MASTER"
    override val bleName = "Master_VIRTUAL"

    private var startTimeMs = System.currentTimeMillis()

    override fun reset() {
        startTimeMs = System.currentTimeMillis()
    }

    override fun onWrite(data: ByteArray): List<ByteArray> {
        // Lazy V/N probes from BegodeAdapter: respond with a stock GW
        // firmware banner and Master model name so the probe loop self-
        // disables exactly as it would against real firmware.
        if (data.size == 1) {
            return when (data[0]) {
                'V'.code.toByte() -> listOf("GW2-MASTER-1.42".toByteArray(Charsets.US_ASCII))
                'N'.code.toByte() -> listOf("NAME=Master".toByteArray(Charsets.US_ASCII))
                else -> emptyList()
            }
        }
        return emptyList()
    }

    override fun onTick(elapsedMs: Long): List<ByteArray> {
        // 10 s sine, 0..30 km/h forward. Wire encoding: raw = kmh * 100 / 3.6.
        val forwardKmh = 15f * (1f + sin(elapsedMs / 1000.0 * 2 * PI / 10).toFloat())
        // SIGN FLIPPED: simulate the inverted-wiring failure mode so the
        // dashboard, voice, alarms and watch can be verified to render the
        // value as positive thanks to abs() in WheelRepository.
        val rawSpeed = -(forwardKmh * 100f / 3.6f).toInt()

        // rawCv = 6150 → BegodeParser at ratio 2.0 reads 123 V → battery ~61 %.
        // Sits in the "better percents" curve middle so the dial actually moves
        // between simulator runs instead of pinning at 100 %.
        val rawCv = 6150
        val rawCurrent = 1200       // 12.0 A phase current
        val rawTempReg = 0x0EFF     // ≈ 47 °C through the MPU6050 formula

        val frame = ByteArray(24)
        frame[0] = 0x55
        frame[1] = 0xAA.toByte()
        putUint16BE(frame, 2, rawCv)
        putInt16BE(frame, 4, rawSpeed)
        // 6..7: unused on most firmwares
        putUint16BE(frame, 8, ((elapsedMs / 1000L) % 1000L).toInt())
        putInt16BE(frame, 10, rawCurrent)
        putInt16BE(frame, 12, rawTempReg)
        // 14..15: hardware PWM stays zero so derivedPwmPct() drives the load gauge.
        // 16..17: padding
        frame[18] = 0x00 // Live A tag
        frame[19] = 0x00
        frame[20] = 0x5A
        frame[21] = 0x5A
        frame[22] = 0x5A
        frame[23] = 0x5A
        return listOf(frame)
    }

    private fun putUint16BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putInt16BE(target: ByteArray, offset: Int, value: Int) {
        val v = value and 0xFFFF
        target[offset] = ((v shr 8) and 0xFF).toByte()
        target[offset + 1] = (v and 0xFF).toByte()
    }
}
