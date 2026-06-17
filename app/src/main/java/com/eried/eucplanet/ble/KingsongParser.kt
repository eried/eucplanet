package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.util.ByteUtils

/**
 * Parsers for inbound KingSong BLE frames. Each frame is a fixed 20-byte
 * structure validated by the `AA 55` magic at offsets 0..1; the type byte at
 * offset 16 selects a layout. See docs/protocols/kingsong.md sections 3 and 4.
 *
 * Each top-level helper returns null when the input doesn't match its layout
 * so callers can route by type byte and silently drop frames they can't
 * decode (for example, BMS pages on a wheel that doesn't ship that page).
 *
 * Protocol reference (upstream, GPLv3):
 * https://github.com/Wheellog/wheellog.android/blob/master/app/src/main/java/com/cooper/wheellog/utils/KingsongAdapter.java
 */
object KingsongParser {

    private const val HEADER0: Byte = 0xAA.toByte()
    private const val HEADER1: Byte = 0x55.toByte()

    /** Type byte at offset 16 of any complete 20-byte frame. */
    fun typeOf(frame: ByteArray): Int? {
        if (frame.size < 20) return null
        if (frame[0] != HEADER0 || frame[1] != HEADER1) return null
        return frame[16].toInt() and 0xFF
    }

    /**
     * Live telemetry, frame `0xA9`. Speed and voltage are u16 LE / 100; the
     * 4-byte distance field at offsets 6..9 uses the documented word-swapped
     * LE32 layout (see spec section 4.1, note on byte order).
     *
     * The wheel does not transmit battery percent; we derive it from voltage
     * using a per-pack-class linear curve keyed on [model]'s nominal voltage.
     */
    fun parseLiveTelemetry(frame: ByteArray, model: KingsongModel?): WheelData? {
        if (frame.size < 20) return null
        if (frame[16] != 0xA9.toByte()) return null

        val voltage = ByteUtils.getUint16LE(frame, 2) / 100f
        val speed = ByteUtils.getInt16LE(frame, 4) / 100f
        // 0xA9 offset 6 carries the LIFETIME odometer, not the per-power-
        // cycle trip. An earlier wire-up routed this to `tripDistance`,
        // which was then overwritten by 0xB9's real trip value on the
        // next frame, so riders never saw their lifetime odo and
        // sometimes saw mid-ride trip resets.
        val totalDistanceMeters = readWordSwappedLE32(frame, 6)
        val current = ByteUtils.getInt16LE(frame, 10) / 100f
        val temperature = ByteUtils.getInt16LE(frame, 12) / 100f

        // Mode byte is only meaningful when the sentinel at offset 15 == 0xE0.
        val modeValid = (frame[15].toInt() and 0xFF) == 0xE0
        val pcMode = if (modeValid) (frame[14].toInt() and 0xFF) else -1

        val batteryPercent = batteryPercentFromVoltage(voltage, model)

        // KingSong frames don't carry an explicit power reading; estimate
        // battery power (and use the same value for motor power) as
        // voltage * current so the dashboard POWER tiles populate.
        val powerW = (voltage * current).toInt()
        return WheelData(
            speed = speed,
            voltage = voltage,
            current = current,
            batteryPercent = batteryPercent,
            temperatures = listOf(temperature),
            maxTemperature = temperature,
            totalDistance = totalDistanceMeters / 1000f,
            batteryPower = powerW,
            motorPower = powerW,
            pcMode = pcMode,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Trip / second-temperature frame `0xB9`. Carries the per-power-cycle
     * trip distance, top speed since power-on, fan and charging flags, and a
     * second temperature sensor (board or motor depending on model).
     *
     * Returned as a partial [WheelData]; caller should merge with the latest
     * `0xA9` snapshot rather than overwrite it.
     */
    fun parseTripFrame(frame: ByteArray): WheelData? {
        if (frame.size < 20) return null
        if (frame[16] != 0xB9.toByte()) return null

        val tripMeters = readWordSwappedLE32(frame, 2)
        val topSpeed = ByteUtils.getUint16LE(frame, 8) / 100f
        val temperature2 = ByteUtils.getInt16LE(frame, 14) / 100f

        return WheelData(
            tripDistance = tripMeters / 1000f,
            dynamicSpeedLimit = topSpeed,
            temperatures = listOf(temperature2),
            maxTemperature = temperature2,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Light-mode echo carried in `0xB9[10]` (same byte layout as the outbound
     * `setLightMode` write). Wheel firmware echoes its current setting back on
     * every B9 frame so a freshly-connected phone can sync to whatever state
     * the wheel was already in.
     *
     * Mapping: 0x12=ON, 0x13=OFF, 0x14=AUTO. AUTO returns null because a
     * single boolean can't represent "let the ambient sensor decide", and we'd
     * rather not lie in either direction; callers should leave the prior
     * `lightOn` value untouched when this returns null.
     */
    fun parseLightOn(frame: ByteArray): Boolean? {
        if (frame.size < 20) return null
        if (frame[16] != 0xB9.toByte()) return null
        return when (frame[10].toInt() and 0xFF) {
            0x12 -> true
            0x13 -> false
            else -> null
        }
    }

    /** True when frame `0xB9` reports the wheel is currently charging. */
    fun isCharging(frame: ByteArray): Boolean {
        if (frame.size < 20) return false
        if (frame[16] != 0xB9.toByte()) return false
        return frame[13].toInt() != 0
    }

    /**
     * Name / model frame `0xBB`. The ASCII payload is `<model>-<version_int>`
     * where the version is the last dash-separated token, divided by 100.
     * Returns the human-readable model string (e.g. `KS-S22`) or null.
     */
    fun parseModelName(frame: ByteArray): String? {
        if (frame.size < 20) return null
        if (frame[16] != 0xBB.toByte()) return null
        val raw = String(frame, 2, 14, Charsets.US_ASCII).trimEnd { it == ' ' || it.code == 0 }
        if (raw.isBlank()) return null
        val lastDash = raw.lastIndexOf('-')
        return if (lastDash > 0 && raw.substring(lastDash + 1).all { it.isDigit() }) {
            raw.substring(0, lastDash)
        } else raw
    }

    /**
     * Firmware version derived from the `0xBB` ASCII payload's trailing
     * dash-separated integer, divided by 100. Returns null when the payload
     * doesn't end in a numeric token.
     */
    fun parseFirmwareVersion(frame: ByteArray): String? {
        if (frame.size < 20) return null
        if (frame[16] != 0xBB.toByte()) return null
        val raw = String(frame, 2, 14, Charsets.US_ASCII).trimEnd { it == ' ' || it.code == 0 }
        val lastDash = raw.lastIndexOf('-')
        if (lastDash <= 0) return null
        val token = raw.substring(lastDash + 1)
        if (token.isEmpty() || !token.all { it.isDigit() }) return null
        val v = token.toInt()
        return "%.2f".format(v / 100f)
    }

    /**
     * Serial number frame `0xB3`. Bytes 14..16 of the serial overlap the
     * trailer slots, so this frame's trailer is NOT `0x14 0x5A 0x5A`. We
     * validate by header magic only.
     */
    fun parseSerial(frame: ByteArray): String? {
        if (frame.size < 20) return null
        if (frame[0] != HEADER0 || frame[1] != HEADER1) return null
        if (frame[16] != 0xB3.toByte()) return null
        val bytes = ByteArray(17)
        System.arraycopy(frame, 2, bytes, 0, 14)
        System.arraycopy(frame, 17, bytes, 14, 3)
        return String(bytes, Charsets.US_ASCII)
            .trimEnd { it == ' ' || it.code == 0 }
            .ifBlank { null }
    }

    /**
     * CPU and PWM frame `0xF5`. PWM is reported as an unsigned percent at
     * offset 15; our [WheelData.pwm] convention is hundredths-of-percent so
     * we scale by 100 to match the V14 dashboard.
     */
    fun parseCpuPwm(frame: ByteArray): WheelData? {
        if (frame.size < 20) return null
        if (frame[16] != 0xF5.toByte()) return null
        val pwmPercent = (frame[15].toInt() and 0xFF).toFloat()
        return WheelData(
            pwm = pwmPercent,
            timestamp = System.currentTimeMillis()
        )
    }

    /** Speed-limit frame `0xF6`: the dynamic PWM-derived ceiling, in km/h. */
    fun parseDynamicSpeedLimit(frame: ByteArray): WheelData? {
        if (frame.size < 20) return null
        if (frame[16] != 0xF6.toByte()) return null
        val limitKmh = ByteUtils.getUint16LE(frame, 2) / 100f
        return WheelData(
            dynamicSpeedLimit = limitKmh,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Alarms + max-speed reply, frames `0xA4` and `0xB5`. Returns the four
     * thresholds packaged as a [WheelSettings]; the `0xA4` flavour expects
     * the app to echo the frame back with offset 16 changed to `0x98`;
     * use [buildAlarmEcho] to produce that echo.
     */
    fun parseAlarmsAndMaxSpeed(frame: ByteArray): WheelSettings? {
        if (frame.size < 20) return null
        val type = frame[16].toInt() and 0xFF
        if (type != 0xA4 && type != 0xB5) return null
        val alarm1 = (frame[4].toInt() and 0xFF).toFloat()
        val alarm2 = (frame[6].toInt() and 0xFF).toFloat()
        val alarm3 = (frame[8].toInt() and 0xFF).toFloat()
        val maxSpeed = (frame[10].toInt() and 0xFF).toFloat()
        return WheelSettings(
            maxSpeedKmh = maxSpeed,
            alarmSpeedKmh = alarm1,
            comfortSensitivity = alarm2.toInt(),
            classicSensitivity = alarm3.toInt()
        )
    }

    /** True when [parseAlarmsAndMaxSpeed]'s frame requires an echo back to the wheel. */
    fun requiresAlarmEcho(frame: ByteArray): Boolean =
        frame.size >= 20 && frame[16] == 0xA4.toByte()

    /**
     * Echo of a `0xA4` settings push: same 20 bytes with offset 16 rewritten
     * to `0x98`. The wheel uses this as a handshake before applying defaults.
     */
    fun buildAlarmEcho(frame: ByteArray): ByteArray? {
        if (frame.size < 20) return null
        val out = frame.copyOf(20)
        out[16] = 0x98.toByte()
        return out
    }

    /**
     * Word-swapped little-endian 32-bit read documented in spec section 3.
     * For input bytes `[b0, b1, b2, b3]` the value is
     * `(b1 << 24) | (b0 << 16) | (b3 << 8) | b2`. Public reference parsers
     * compute the same thing; if a labelled capture later shows a plain
     * LE32 read works, swap this for [ByteUtils.getUint32LE].
     */
    private fun readWordSwappedLE32(frame: ByteArray, offset: Int): Int {
        val b0 = frame[offset].toInt() and 0xFF
        val b1 = frame[offset + 1].toInt() and 0xFF
        val b2 = frame[offset + 2].toInt() and 0xFF
        val b3 = frame[offset + 3].toInt() and 0xFF
        return (b1 shl 24) or (b0 shl 16) or (b3 shl 8) or b2
    }

    /**
     * Voltage-to-percent curve. KingSong never transmits a battery
     * percentage, so it's estimated from per-cell pack voltage.
     *
     * Reverse-engineered from side-by-side captures against the official
     * KingSong app on a KS-16X (20S):
     *
     *   pack V   per cell   KS app    matches our formula
     *   73.00 V  3.6500 V   52 %      52.4 %  ->  52
     *   74.00 V  3.7000 V   57 %      57.1 %  ->  57
     *   82.27 V  4.1135 V   96-97 %   96.5 %  ->  96-97
     *
     * Those three points sit exactly on a single per-cell line from
     * 3.10 V/cell (0 %) to 4.15 V/cell (100 %). The KS app does not appear
     * to apply a knee in the rideable range; the previous 3-knee fit was a
     * wrong guess. Endpoint span is 1.05 V/cell -> 0.0105 V/cell per 1 %.
     *
     * Same per-cell line applies across every voltage class (16/20/24/30/36/42)
     * because all KS packs share the same 18650/21700 NMC chemistry; per-class
     * pack endpoints are just `cells * 3.10` and `cells * 4.15`.
     *
     * Returns 0 when no model is detected so the dashboard renders an empty
     * battery rather than a stale fallback.
     */
    private fun batteryPercentFromVoltage(voltage: Float, model: KingsongModel?): Int {
        if (model == null || voltage <= 0f) return 0
        val cells = model.cellsSeries
        if (cells <= 0) return 0
        val vPerCell = voltage / cells
        val raw = ((vPerCell - KS_EMPTY_V_PER_CELL) * 100f) /
            (KS_FULL_V_PER_CELL - KS_EMPTY_V_PER_CELL)
        return raw.toInt().coerceIn(0, 100)
    }

    private const val KS_EMPTY_V_PER_CELL: Float = 3.10f
    private const val KS_FULL_V_PER_CELL:  Float = 4.15f
}
