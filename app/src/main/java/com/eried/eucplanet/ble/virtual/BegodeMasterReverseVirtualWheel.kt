package com.eried.eucplanet.ble.virtual

import kotlin.math.PI
import kotlin.math.sin

/**
 * Software fake of a Begode Master with **inverted motor wiring / sensor
 * mount** — i.e. the wheel reports speed with the *opposite* sign from what
 * the app expects. Forward riding lands in WheelData.speed as a negative
 * number, so the dial, voice announcement and history graph all read
 * "backward" until the rider flips the per-wheel "Reverse speed direction"
 * toggle in Settings → Wheel parameters.
 *
 * Use case: lets the dev verify that toggle without needing FlyboyEUC to
 * fly his real Master out the door at 30 km/h with the phone strapped on.
 *
 * Implementation: pushes a Begode "Live A" telemetry frame (tag 0x00) every
 * 100 ms with a sine-wave forward speed of ~0..30 km/h. The 16-bit BE speed
 * field at offset 4 is emitted with the sign flipped (`-rawSpeed`), so
 * BegodeParser.parseLiveA reads it as a negative i16 and the dashboard
 * displays a negative number. Voltage, current, temperature and battery
 * percent track a plausible 134 V pack so the rest of the UI looks alive.
 */
class BegodeMasterReverseVirtualWheel : VirtualWheel {

    override val displayName = "Virtual Begode Master (reverse-wired)"
    override val id = "MASTER_REV"

    // Routes to the Begode adapter via CompositeWheelAdapter.pickAdapter
    // (`n.startsWith("master")`) and also to BegodeModel.MASTER inside
    // BegodeModel.fromReportedName (`"master" in n && "pro" !in n`). That
    // means the parser uses the Master-specific voltage scaler (1.6) and
    // derived-PWM rotation constants (113 km/h @ 134.4 V), so the dial
    // reads sensible numbers throughout.
    override val bleName = "Master_VIRTUAL"

    private var startTimeMs = System.currentTimeMillis()

    override fun reset() {
        startTimeMs = System.currentTimeMillis()
    }

    override fun onWrite(data: ByteArray): List<ByteArray> {
        // BegodeAdapter's initSequence sends "V" (firmware probe). Respond
        // with a stock GW firmware banner so the V/N lazy-probe loop in
        // BegodeAdapter.onRawNotification sees the firmware string and stops
        // asking. Same logic for "N" (model name).
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
        // Forward speed sweeps 0..30 km/h on a 10 s sine. Convert to the
        // wire encoding: raw = (kmh * 100) / 3.6 → ~833 for 30 km/h.
        val forwardKmh = 15f * (1f + sin(elapsedMs / 1000.0 * 2 * PI / 10).toFloat())
        val rawSpeedForward = (forwardKmh * 100f / 3.6f).toInt()

        // The whole point of this simulator: ship the speed with the sign
        // INVERTED so forward = negative. Lets the rider see the bug and
        // verify the new "Reverse speed direction" toggle in Wheel
        // parameters flips it back to positive.
        val rawSpeedSigned = -rawSpeedForward

        // Voltage on the 84 V reference (BegodeParser divides by 100 then
        // multiplies by the per-model ratio — Master ratio = 1.6 → 1.6 *
        // (rawCv / 100) ≈ 100 V displayed). A rawCv of 6200 lands at ~99 V
        // / ~74 % battery on the "better percents" curve.
        val rawCv = 6200

        // Plausible phase current and temperature so the rest of the
        // dashboard reads alive. Begode encodes temp as a raw IMU register
        // value; BegodeParser divides by 340 and offsets by 36.53.
        val rawCurrent = 1200  // 12.0 A
        val rawTempReg = 0x0EFF // ≈ 47 °C

        val frame = ByteArray(24)
        frame[0] = 0x55
        frame[1] = 0xAA.toByte()
        putUint16BE(frame, 2, rawCv)
        putInt16BE(frame, 4, rawSpeedSigned)
        // 6..7: unused on most firmwares
        putUint16BE(frame, 8, ((elapsedMs / 1000L) % 1000L).toInt())  // trip meters, slowly ticking
        putInt16BE(frame, 10, rawCurrent)
        putInt16BE(frame, 12, rawTempReg)
        // 14..15: hardware PWM stays zero — derivedPwmPct() takes over
        // 16..17: padding
        frame[18] = 0x00 // Live A tag
        frame[19] = 0x00 // subidx
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
