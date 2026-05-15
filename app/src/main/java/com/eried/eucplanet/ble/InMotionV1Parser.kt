package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.util.ByteUtils
import kotlin.math.abs

/**
 * Parsers for inbound InMotion V1 frames. Each top-level helper takes the
 * unescaped CAN payload (16-byte prefix + extended bytes) returned by
 * [InMotionV1Protocol.unwrap]; the CAN ID at offsets 0..3 selects the layout.
 *
 * Spec: docs/protocols/inmotion_v1.md sections 4 (fast info) and 5 (slow info).
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
object InMotionV1Parser {

    /** CAN ID is a u32 LE at offset 0 of every unwrapped frame. */
    fun canIdOf(unwrapped: ByteArray): Int? {
        if (unwrapped.size < 4) return null
        return (ByteUtils.getUint32LE(unwrapped, 0).toInt())
    }

    /** True when this is an extended (variable-length) frame per spec 3.2. */
    fun isExtended(unwrapped: ByteArray): Boolean =
        unwrapped.size > 12 && (unwrapped[12].toInt() and 0xFF) == 0xFE

    /**
     * Slice the extended payload (everything after the 16-byte CAN prefix).
     * Empty array when the frame is not extended.
     */
    fun extPayload(unwrapped: ByteArray): ByteArray {
        if (unwrapped.size <= 16) return ByteArray(0)
        return unwrapped.copyOfRange(16, unwrapped.size)
    }

    /**
     * Fast info `0x0F550113`. Offsets are into the extended payload — speed
     * pair at 12+16 averaged then divided by [InMotionV1Model.speedFactor],
     * voltage / current at 24 / 20, temps at 32+34, trip at 48, total at 44.
     * Returns null when the payload is too short to read the core fields.
     */
    fun parseFastInfo(payload: ByteArray, model: InMotionV1Model?): WheelData? {
        if (payload.size < 52) return null

        val factor = model?.speedFactor ?: 3812f
        val pitch = ByteUtils.getInt32LE(payload, 0) / 65536f
        val sampleA = ByteUtils.getInt32LE(payload, 12)
        val sampleB = ByteUtils.getInt32LE(payload, 16)
        val speed = abs((sampleA + sampleB) / (2f * factor)) * 3.6f
        val current = ByteUtils.getInt32LE(payload, 20) / 1000f
        val voltage = ByteUtils.getUint32LE(payload, 24).toFloat() / 100f

        val mosTemp = (payload[32].toInt() and 0xFF).toFloat()
        val imuTemp = (payload[34].toInt() and 0xFF).toFloat()
        val temps = listOf(mosTemp, imuTemp)

        val totalDistance = readTotalDistanceMeters(payload, model) / 1000f
        val tripDistance = ByteUtils.getUint32LE(payload, 48).toFloat() / 1000f

        // V10 family roll at offset 72 is unreliable per spec 8.
        val roll = if (payload.size >= 76 && model?.isV10Family != true) {
            ByteUtils.getInt32LE(payload, 72) / 90f
        } else 0f

        val pcMode = if (payload.size >= 64) parseWorkMode(payload, 60, model) else -1
        val batteryPercent = batteryPercentFromVoltage(voltage, model)

        return WheelData(
            speed = speed,
            voltage = voltage,
            current = current,
            batteryPercent = batteryPercent,
            temperatures = temps,
            maxTemperature = temps.maxOrNull() ?: 0f,
            tripDistance = tripDistance,
            totalDistance = totalDistance,
            pitchAngle = pitch,
            rollAngle = roll,
            pcMode = pcMode,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Slow info `0x0F550114`. Returns the [SlowInfo] bundle of model code,
     * serial, firmware version and the user-visible settings (max speed, light,
     * volume, ride mode). Offsets 125 / 129 / 130 / 132 are firmware-dependent
     * and only read when the payload is long enough.
     */
    fun parseSlowInfo(payload: ByteArray): SlowInfo? {
        if (payload.size < 64) return null

        val serial = parseSerial(payload)
        val firmware = parseFirmware(payload)
        val maxSpeedKmh = ByteUtils.getUint16LE(payload, 60) / 1000f
        val lightOn = payload.size > 80 && payload[80].toInt() == 1

        val low = if (payload.size > 104) payload[104].toInt() and 0xFF else 0
        val high = if (payload.size > 107) payload[107].toInt() and 0xFF else 0
        val model = InMotionV1Model.fromCarType(low, high)

        val pedalSensitivity = if (payload.size > 124) {
            ((payload[124].toInt() and 0xFF) - 28) and 0xFF
        } else 0
        val volumePercent = if (payload.size > 126) {
            ByteUtils.getUint16LE(payload, 125) / 100f
        } else 0f
        val handleEnabled = if (payload.size > 129) payload[129].toInt() != 1 else true
        val drlOn = if (payload.size > 130) payload[130].toInt() == 1 else false
        val rideClassic = if (payload.size > 132) payload[132].toInt() == 1 else false

        val settings = WheelSettings(
            maxSpeedKmh = maxSpeedKmh,
            alarmSpeedKmh = maxSpeedKmh,
            comfortSensitivity = pedalSensitivity,
            classicSensitivity = if (rideClassic) 1 else 0,
            mute = volumePercent == 0f,
            drl = drlOn,
            transportMode = !handleEnabled
        )

        return SlowInfo(
            model = model,
            serial = serial,
            firmware = firmware,
            settings = settings,
            volumePercent = volumePercent,
            lightOn = lightOn
        )
    }

    /**
     * Mileage at offset 44 varies across firmware revisions — see spec
     * section 4.2. L6 stores u32 LE in centimetres (multiply by 100 to get
     * metres); every other current model stores u32 LE in metres directly.
     */
    private fun readTotalDistanceMeters(payload: ByteArray, model: InMotionV1Model?): Float {
        if (payload.size < 48) return 0f
        val raw = ByteUtils.getUint32LE(payload, 44).toFloat()
        return if (model?.isL6 == true) raw * 100f else raw
    }

    /**
     * Work mode at offset 60. Modern wheels (V8F / V8S / V10 family) encode
     * the macro state in the high nibble of the low byte; legacy wheels map
     * the low nibble directly to a state ID. Returns the state ID or -1 when
     * the value is unrecognised.
     */
    private fun parseWorkMode(payload: ByteArray, offset: Int, model: InMotionV1Model?): Int {
        val raw = payload[offset].toInt() and 0xFF
        return if (model?.isModern == true) {
            (raw ushr 4) and 0x0F
        } else {
            raw and 0x0F
        }
    }

    /**
     * Serial as 8 hex bytes read in reverse order, matching the WheelLog
     * reference. The first 8 bytes of the slow-info payload are the serial
     * stored little-endian relative to the printed format on the chassis;
     * iterating 7..0 reproduces the rider-visible value.
     */
    private fun parseSerial(payload: ByteArray): String {
        val sb = StringBuilder(16)
        for (i in 7 downTo 0) sb.append("%02X".format(payload[i].toInt() and 0xFF))
        return sb.toString()
    }

    /** Firmware version `f0.f1.f2` from u16 LE @24 + bytes @26 / @27. */
    private fun parseFirmware(payload: ByteArray): String {
        if (payload.size < 28) return "?.?.?"
        val patch = ByteUtils.getUint16LE(payload, 24)
        val minor = payload[26].toInt() and 0xFF
        val major = payload[27].toInt() and 0xFF
        return "$major.$minor.$patch"
    }

    /**
     * Battery percent derived from voltage. V1 wheels do NOT report SOC over
     * BLE — the host computes it from the per-model curves in spec 4.5.
     */
    fun batteryPercentFromVoltage(voltage: Float, model: InMotionV1Model?): Int {
        if (voltage <= 0f) return 0
        val pct = when {
            model?.isV10Family == true -> v10Curve(voltage)
            model?.isV8Family == true -> v8Curve(voltage)
            else -> defaultCurve(voltage)
        }
        return (pct * 100f).toInt().coerceIn(0, 100)
    }

    /** V10 family (84 V pack) curve from spec 4.5. */
    private fun v10Curve(v: Float): Float = when {
        v >= 83.5f -> 1.0f
        v > 68.0f  -> (v - 66.5f) / 17f
        v > 64.0f  -> (v - 64.0f) / 45f
        else -> 0f
    }

    /** V8 / V8F / V8S / Glide 3 (84 V pack) curve from spec 4.5. */
    private fun v8Curve(v: Float): Float = when {
        v >= 82.5f -> 1.0f
        v > 68.0f  -> (v - 68.0f) / 14.5f
        else -> 0f
    }

    /**
     * Default piecewise-linear curve used by V5 / R-series / L6 / V3. Slopes
     * picked to match WheelLog within 1-2 percent — the breakpoints come from
     * spec 4.5 (82.0 / 77.8 / 74.8 / 71.8 / 70.3 / 68.0 V).
     */
    private fun defaultCurve(v: Float): Float = when {
        v >= 82.0f -> 1.0f
        v > 77.8f  -> 0.6f + (v - 77.8f) * (0.4f / 4.2f)
        v > 74.8f  -> 0.4f + (v - 74.8f) * (0.2f / 3.0f)
        v > 71.8f  -> 0.2f + (v - 71.8f) * (0.2f / 3.0f)
        v > 70.3f  -> 0.1f + (v - 70.3f) * (0.1f / 1.5f)
        v > 68.0f  -> (v - 68.0f) * (0.1f / 2.3f)
        else -> 0f
    }

    /**
     * Decoded slow-info bundle. The adapter splits this into separate
     * [DecodeResult.ModelName] / [DecodeResult.Firmware] / [DecodeResult.Settings]
     * results so the repository can update each state slice independently.
     */
    data class SlowInfo(
        val model: InMotionV1Model?,
        val serial: String,
        val firmware: String,
        val settings: WheelSettings,
        val volumePercent: Float,
        val lightOn: Boolean
    )
}
