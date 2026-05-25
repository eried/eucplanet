package com.eried.eucplanet.ble

import com.eried.eucplanet.util.ByteUtils

/**
 * Outbound command builders for the KingSong BLE protocol. Every frame is a
 * fixed 20-byte structure: `AA 55` header, 14 payload bytes, type code at
 * offset 16, then the `0x14 0x5A 0x5A` trailer at offsets 17..19. See
 * docs/protocols/kingsong.md section 3 and section 6.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
object KingsongCommands {

    private const val HEADER0: Byte = 0xAA.toByte()
    private const val HEADER1: Byte = 0x55.toByte()
    private const val TRAILER0: Byte = 0x14
    private const val TRAILER1: Byte = 0x5A.toByte()

    object Type {
        const val POWER_OFF: Byte = 0x40
        const val STANDBY: Byte = 0x3F
        const val SERIAL_REQ: Byte = 0x63
        const val LIGHT: Byte = 0x73.toByte()
        const val MAX_SPEED_AND_ALARMS: Byte = 0x85.toByte()
        const val PEDAL_MODE: Byte = 0x87.toByte()
        const val BEEP: Byte = 0x88.toByte()
        const val CALIBRATE: Byte = 0x89.toByte()
        const val WHEEL_PARAM: Byte = 0x8A.toByte()
        const val QUERY_LIMITS: Byte = 0x98.toByte()
        const val NAME_REQ: Byte = 0x9B.toByte()
        const val BMS1_SERIAL_REQ: Byte = 0xE1.toByte()
    }

    /**
     * Public wrap entry point for Service Mode's Raw tab. The user types
     * `<type-hex> <up-to-14 bytes of payload>`; we emit the 20-byte frame
     * with the standard header / trailer. Payload longer than 14 bytes is
     * truncated to fit (the wheel only ever cares about the first ~14
     * bytes anyway).
     */
    fun wrapArbitrary(typeAndPayload: ByteArray): ByteArray {
        if (typeAndPayload.isEmpty()) return ByteArray(0)
        val type = typeAndPayload[0]
        val payload = if (typeAndPayload.size > 1) typeAndPayload.copyOfRange(1, typeAndPayload.size) else byteArrayOf()
        return frame(type) { f ->
            val n = minOf(payload.size, 14) // bytes 2..15 are payload (14 slots)
            for (i in 0 until n) {
                f[2 + i] = payload[i]
            }
        }
    }

    /**
     * Build a 20-byte frame with the given type byte. Payload bytes default
     * to zero; callers fill in the type-specific slots before sending.
     */
    private fun frame(type: Byte, fill: (ByteArray) -> Unit = {}): ByteArray {
        val out = ByteArray(20)
        out[0] = HEADER0
        out[1] = HEADER1
        fill(out)
        out[16] = type
        out[17] = TRAILER0
        out[18] = TRAILER1
        out[19] = TRAILER1
        return out
    }

    /** Single short beep. */
    fun horn(): ByteArray = frame(Type.BEEP)

    /** Powers the wheel off. No confirmation. */
    fun powerOff(): ByteArray = frame(Type.POWER_OFF)

    /** Triggers gyro recalibration. Wheel must be on its side; no safety here. */
    fun calibrate(): ByteArray = frame(Type.CALIBRATE)

    /** Wheel replies with `0xBB` carrying the ASCII model+version string. */
    fun queryName(): ByteArray = frame(Type.NAME_REQ)

    /**
     * Wheel replies with the `0xB3` serial frame, whose ASCII payload spans
     * payload+trailer slots; see KingsongParser for the unusual layout.
     */
    fun querySerial(): ByteArray = frame(Type.SERIAL_REQ)

    /** Wheel replies with `0xA4` (echo-required) or `0xB5` carrying the four speed limits. */
    fun queryLimits(): ByteArray = frame(Type.QUERY_LIMITS)

    /**
     * Light mode: 0=off, 1=on, 2=auto. Encoded as `mode + 0x12` per the public
     * reference, with `data[3] = 0x01` to make the write take.
     */
    fun setLightMode(mode: Int): ByteArray = frame(Type.LIGHT) { f ->
        f[2] = (mode.coerceIn(0, 2) + 0x12).toByte()
        f[3] = 0x01
    }

    /** Convenience: on/off only. Auto requires [setLightMode] directly. */
    fun setLight(on: Boolean): ByteArray = setLightMode(if (on) 1 else 0)

    /**
     * Pedal hardness mode: 0=soft, 1=medium, 2=hard. Note the non-standard
     * trailer byte `0x15` at offset 17, verified across multiple firmwares.
     */
    fun setPedalMode(mode: Int): ByteArray {
        val out = frame(Type.PEDAL_MODE) { f ->
            f[2] = mode.coerceIn(0, 2).toByte()
            f[3] = 0xE0.toByte()
        }
        out[17] = 0x15
        return out
    }

    /**
     * Writes all four speed thresholds in a single frame. Values are km/h,
     * each stored as a single byte at the documented even offsets.
     */
    fun setMaxSpeedAndAlarms(
        alarm1Kmh: Int,
        alarm2Kmh: Int,
        alarm3Kmh: Int,
        maxKmh: Int
    ): ByteArray = frame(Type.MAX_SPEED_AND_ALARMS) { f ->
        f[2] = alarm1Kmh.coerceIn(0, 255).toByte()
        f[4] = alarm2Kmh.coerceIn(0, 255).toByte()
        f[6] = alarm3Kmh.coerceIn(0, 255).toByte()
        f[8] = maxKmh.coerceIn(0, 255).toByte()
    }

    /**
     * Charge cutoff percentage (e.g. 80 / 90 / 100). Older KS-18L firmwares
     * may silently ignore this; see spec open question 10.
     */
    fun setChargeLimit(percent: Int): ByteArray = frame(Type.WHEEL_PARAM) { f ->
        f[2] = 0x09
        f[4] = percent.coerceIn(0, 100).toByte()
    }

    /** Idle auto-poweroff delay in seconds (e.g. 3600 = 60 minutes). */
    fun setStandbyDelay(seconds: Int): ByteArray = frame(Type.STANDBY) { f ->
        f[2] = 0x01
        val v = ByteUtils.putUint16LE(seconds.coerceIn(0, 65535))
        f[4] = v[0]
        f[5] = v[1]
    }

    /** Pedal cutoff lean angle, in tenths of a degree (e.g. 501 = 50.1 deg). */
    fun setGyroSwitchOffAngle(tenthsOfDegree: Int): ByteArray = frame(Type.WHEEL_PARAM) { f ->
        f[2] = 0x03
        val v = ByteUtils.putUint16LE(tenthsOfDegree.coerceIn(0, 65535))
        f[4] = v[0]
        f[5] = v[1]
    }

    /** Pedal pitch trim, in tenths of a degree, signed (e.g. -32 = -3.2 deg). */
    fun setGyroFrontTrim(tenthsOfDegree: Int): ByteArray = frame(Type.WHEEL_PARAM) { f ->
        f[2] = 0x01
        val clamped = tenthsOfDegree.coerceIn(-32768, 32767)
        val raw = if (clamped < 0) clamped + 0x10000 else clamped
        val v = ByteUtils.putUint16LE(raw)
        f[4] = v[0]
        f[5] = v[1]
    }

    /**
     * BMS query frame. Per spec section 6, BMS request frames use a zeroed
     * trailer (`data[17..19] = 0x00`) instead of the standard `0x14 0x5A 0x5A`.
     * The wheel replies on the matching reply type (e.g. send `0xE1`, reply
     * on type `0xE1` for the serial frame).
     */
    fun bmsQuery(type: Byte): ByteArray {
        val out = ByteArray(20)
        out[0] = HEADER0
        out[1] = HEADER1
        out[16] = type
        // Trailer slots intentionally left as 0x00.
        return out
    }
}
