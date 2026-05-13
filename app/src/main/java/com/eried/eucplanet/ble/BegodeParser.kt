package com.eried.eucplanet.ble

import android.util.Log
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.util.ByteUtils

/**
 * Frame reassembler + parser for Begode/Gotway wheels.
 *
 * Wire format (spec docs/protocols/begode.md sections 3-4):
 *   - 24-byte BIG-ENDIAN frames with header `55 AA`, terminator `5A 5A 5A 5A`
 *     and a tag byte at offset 18 (0x00 Live A, 0x01..0x03 BMS, 0x04 Live B,
 *     0x07 extras, 0xFF SmirnoV PID).
 *   - No checksum, no length, no sequence number. Validity inferred from
 *     header + 4-byte 0x5A terminator + size == 24.
 *   - The HM-10 bridge occasionally injects spurious 0x5A bytes; spec 3.6
 *     documents the explicit re-sync rules implemented in [feed].
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
class BegodeParser {

    private val buffer = ArrayDeque<Byte>()

    /** Most recent voltage scaler; cached so 0x04 / 0x07 frames can render alongside 0x00. */
    @Volatile private var lastVoltage: Float = 0f
    @Volatile private var lastBatteryPct: Int = 0
    @Volatile private var lastSpeedKmh: Float = 0f
    @Volatile private var lastPhaseCurrent: Float = 0f
    @Volatile private var lastTempC: Float = 0f
    @Volatile private var lastTripKm: Float = 0f
    @Volatile private var lastPwmPct: Float = 0f
    @Volatile private var lastLightOn: Boolean = false

    /** True once we have ever seen a 0x07 extras frame; from then on, we trust 0x07 PWM/current over 0x00 derivations. */
    @Volatile private var hasExtras: Boolean = false

    fun reset() {
        buffer.clear()
        lastVoltage = 0f
        lastBatteryPct = 0
        lastSpeedKmh = 0f
        lastPhaseCurrent = 0f
        lastTempC = 0f
        lastTripKm = 0f
        lastPwmPct = 0f
        lastLightOn = false
        hasExtras = false
    }

    /**
     * Push raw bytes from a BLE notification into the reassembler and emit
     * a [DecodeResult] for every complete 24-byte frame found, in order.
     *
     * Re-sync rules at small buffer sizes mirror spec 3.6 verbatim — they
     * exist to recover when the bridge spuriously inserts a 0x5A inside a
     * fresh `55 AA ...` sequence.
     */
    fun feed(rawBytes: ByteArray, model: BegodeModel?): List<DecodeResult> {
        for (b in rawBytes) buffer.addLast(b)

        val results = mutableListOf<DecodeResult>()

        while (buffer.size >= 2) {
            // Drop bytes until we land on a `55 AA` header.
            if (buffer[0] != HEADER_0 || buffer[1] != HEADER_1) {
                buffer.removeFirst()
                continue
            }

            // Spec 3.6 re-sync at sizes 5 and 6: a stray 0x5A right after the
            // header followed by another `55 AA` means the first header was
            // garbage, not a frame start.
            if (buffer.size >= 5 &&
                buffer[2] == TERM && buffer[3] == HEADER_0 && buffer[4] == HEADER_1
            ) {
                repeat(3) { buffer.removeFirst() }
                continue
            }
            if (buffer.size >= 6 &&
                buffer[2] == TERM && buffer[3] == TERM &&
                buffer[4] == HEADER_0 && buffer[5] == HEADER_1
            ) {
                repeat(4) { buffer.removeFirst() }
                continue
            }

            if (buffer.size < FRAME_SIZE) break

            // Validate terminator. If any of the four trailing bytes isn't 0x5A,
            // discard the failed `55 AA` and resync from the next byte.
            val terminatorOk = (0 until 4).all { buffer[FRAME_SIZE - 4 + it] == TERM }
            if (!terminatorOk) {
                buffer.removeFirst()
                continue
            }

            val frame = ByteArray(FRAME_SIZE)
            for (i in 0 until FRAME_SIZE) frame[i] = buffer[i]
            repeat(FRAME_SIZE) { buffer.removeFirst() }

            decodeFrame(frame, model)?.let { results += it }
        }

        return results
    }

    private fun decodeFrame(frame: ByteArray, model: BegodeModel?): DecodeResult? {
        val tag = frame[18].toInt() and 0xFF
        // Surface every 24-byte frame to the Service Mode Inspect tab. The tag
        // byte is what differentiates Live A / Live B / extras / BMS pages, so
        // we include it in the prefix-stamped line; the body covers all 24
        // bytes (header through terminator) so an investigator can sanity-
        // check framing as well as payload.
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
            "Begode realtime tag=0x${"%02x".format(tag)} len=${frame.size} body=${frame.joinToString(" ") { "%02x".format(it) }}"
        )
        return when (tag) {
            0x00 -> parseLiveA(frame, model)?.let { DecodeResult.Telemetry(it) }
            0x01 -> parseBmsSummary(frame, model)?.let { DecodeResult.Telemetry(it) }
            0x02, 0x03 -> null // BMS cell pages: cell-level UI not exposed yet, skip cleanly.
            0x04 -> parseLiveB(frame)?.let { DecodeResult.Settings(it) }
            0x07 -> parseExtras(frame)?.let { DecodeResult.Telemetry(it) }
            0xFF -> {
                // SmirnoV PID frame; tuning surface, not telemetry. Spec 4.8 says
                // "skip unless you target that FW" — log once-ish and move on.
                Log.d(TAG, "ignoring 0xFF Alexovik PID frame (SmirnoV FW only)")
                null
            }
            else -> {
                Log.d(TAG, "unknown tag 0x${"%02x".format(tag)}, frame discarded")
                null
            }
        }
    }

    /**
     * Live A telemetry (spec 4.1). Voltage at offsets 2..3 is u16 BE in
     * centivolts at the wheel's *unscaled* 84 V reference; multiply by the
     * per-pack ratio from [voltageRatioFor] to land on real volts.
     */
    private fun parseLiveA(frame: ByteArray, model: BegodeModel?): WheelData? {
        val rawCv = ByteUtils.getUint16BE(frame, 2)
        val ratio = voltageRatioFor(model)
        val voltage = (rawCv / 100f) * ratio
        // Spec 4.1: speed = i16 BE * 3.6 / 100. Some FW emit absolute speed
        // only — we expose signed and let the UI normalise. See open question
        // 9 "reverse speed sign".
        val rawSpeed = ByteUtils.getInt16BE(frame, 4)
        val speed = rawSpeed * 3.6f / 100f

        // WheelLog reads only the low u16 at offset 8 as trip-meters; the high
        // word at 6..7 is unused on most firmwares (open question 9). We follow
        // that convention so we don't render bogus distance from older wheels.
        val tripMeters = ByteUtils.getUint16BE(frame, 8)
        val tripKm = tripMeters / 1000f

        val phaseCurrent = ByteUtils.getInt16BE(frame, 10) / 100f

        // Temperature: raw IMU register, MPU6050 default formula since we
        // don't yet parse the `MPU` ASCII banner that would distinguish 6500.
        val rawTemp = ByteUtils.getInt16BE(frame, 12)
        val tempC = rawTemp / 340f + 36.53f

        // Hardware PWM at 14..15 is meaningful only on Freestyl3r CF FW;
        // otherwise it's noise. We emit it as-is and let the UI live with
        // a near-zero reading on stock wheels. Spec 4.1 says raw * 10 for
        // percent; since we want centi-percent we multiply by 100 to keep
        // parity with InMotion's pwm-as-percent convention.
        val pwmPct = if (!hasExtras) ByteUtils.getInt16BE(frame, 14) / 100f else lastPwmPct

        val battPct = batteryPercentFromRawCv(rawCv)

        lastVoltage = voltage
        lastBatteryPct = battPct
        lastSpeedKmh = speed
        lastPhaseCurrent = phaseCurrent
        lastTempC = tempC
        lastTripKm = tripKm
        if (!hasExtras) lastPwmPct = pwmPct

        return WheelData(
            speed = speed,
            voltage = voltage,
            current = phaseCurrent,
            batteryPercent = battPct,
            pwm = lastPwmPct,
            temperatures = listOf(tempC),
            maxTemperature = tempC,
            tripDistance = tripKm,
            lightOn = lastLightOn,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Live B (spec 4.5): mileage + settings. We expose only the user-facing
     * settings the app currently writes; alarm-byte / settings-bitfield bits
     * are documented in spec 4.5 and can be wired up when the UI grows
     * matching toggles.
     */
    private fun parseLiveB(frame: ByteArray): WheelSettings? {
        // Tiltback / max speed (spec 4.5 offset 10..11). The original Gotway
        // spec called out ">= 100 = disabled" back when 50 km/h wheels were
        // the norm; modern high-voltage wheels (Master Pro, XWay, MSX HS)
        // genuinely set tiltbacks above 100 km/h, so we only treat clearly
        // out-of-range sentinels (>= 200) as disabled.
        val rawMax = ByteUtils.getUint16BE(frame, 10)
        val maxSpeed = if (rawMax >= 200) 0f else rawMax.toFloat()

        // Settings bitfield at offset 6 (u16 BE). bit 0 is miles flag; we
        // surface that via WheelSettings if the UI ever needs it. The other
        // bits (pedal mode, speed alarms, roll angle) are spec-documented
        // but the UI doesn't read them yet, so we skip parsing for now.

        // Light mode at offset 15 — low 2 bits: 0=off, 1=on, 2=strobe.
        val lightBits = frame[15].toInt() and 0x03
        lastLightOn = lightBits != 0

        return WheelSettings(
            maxSpeedKmh = maxSpeed
        )
    }

    /**
     * BMS summary (spec 4.7 frame 0x01). When present, the pack voltage on
     * this frame is authoritative and bypasses the per-pack scaler — useful
     * for users who have refit non-standard packs.
     */
    private fun parseBmsSummary(frame: ByteArray, model: BegodeModel?): WheelData? {
        // Pack voltage at offset 6 in 0.1 V; multiply by 10 to get centivolts.
        val packV = ByteUtils.getUint16BE(frame, 6) / 10f
        if (packV <= 0f) return null
        lastVoltage = packV
        // Re-derive battery % from the authoritative voltage, expressed back
        // in 84-V-equivalent centivolts so the curve in [batteryPercentFromRawCv]
        // applies unchanged.
        val ratio = voltageRatioFor(model)
        val equivalentCv = ((packV / ratio) * 100f).toInt()
        lastBatteryPct = batteryPercentFromRawCv(equivalentCv)

        return WheelData(
            speed = lastSpeedKmh,
            voltage = lastVoltage,
            current = lastPhaseCurrent,
            batteryPercent = lastBatteryPct,
            pwm = lastPwmPct,
            temperatures = listOf(lastTempC),
            maxTemperature = lastTempC,
            tripDistance = lastTripKm,
            lightOn = lastLightOn,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Extras frame (spec 4.6 tag 0x07). Carries true battery current,
     * motor temperature and true PWM — present on post-2022 firmwares.
     * After the first 0x07 we trust these over the derived values from 0x00.
     */
    private fun parseExtras(frame: ByteArray): WheelData? {
        hasExtras = true
        val battCurrent = ByteUtils.getInt16BE(frame, 2) / 100f
        val motorTempC = ByteUtils.getInt16BE(frame, 6).toFloat()
        val truePwm = ByteUtils.getInt16BE(frame, 8) / 100f

        lastPhaseCurrent = battCurrent
        lastPwmPct = truePwm

        // Pick the hottest of (motor, IMU) so the dashboard's max-temp ring
        // shows whichever is more concerning at this moment.
        val temps = listOf(lastTempC, motorTempC)

        return WheelData(
            speed = lastSpeedKmh,
            voltage = lastVoltage,
            current = battCurrent,
            batteryPercent = lastBatteryPct,
            pwm = truePwm,
            temperatures = temps,
            maxTemperature = temps.max(),
            tripDistance = lastTripKm,
            lightOn = lastLightOn,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Battery percent from raw centi-volts on the 84 V reference scale (spec
     * 4.3, "better percents" curve). Callers must pass the *unscaled* cV so
     * the curve constants line up — for non-84 V packs we divide voltage by
     * the per-pack scaler before applying the curve.
     */
    private fun batteryPercentFromRawCv(rawCv: Int): Int {
        return when {
            rawCv <= 5120 -> 0
            rawCv <= 5440 -> ((rawCv - 5120) / 36).coerceIn(0, 100)
            rawCv <= 6680 -> (((rawCv - 5320) / 13.6f).toInt()).coerceIn(0, 100)
            else -> 100
        }
    }

    companion object {
        private const val TAG = "BegodeParser"
        const val FRAME_SIZE = 24
        private const val HEADER_0: Byte = 0x55
        private const val HEADER_1: Byte = 0xAA.toByte()
        private const val TERM: Byte = 0x5A

        /**
         * Per-pack voltage ratio (spec 4.4). Multiplying raw_cV / 100 by this
         * ratio yields real volts at the wheel's actual nominal pack voltage.
         *
         * The spec's table is keyed by app-index 0..6 mapped to nominal
         * voltage; we key on [BegodeModel.nominalVoltage] so the model
         * registry stays the single source of truth. Unknown / null defaults
         * to the 84 V tier (1.25) since that's the most common Begode pack
         * and the failure mode of mis-scaling under-reads voltage rather
         * than over-reading it (safer for the user).
         */
        fun voltageRatioFor(model: BegodeModel?): Float {
            // Spec 4.4 table reproduced as a switch on the nominal-voltage
            // class so the per-model registry stays one-source-of-truth.
            return when (model?.nominalVoltage) {
                67 -> 1.00f
                84 -> 1.25f
                100 -> 1.50f
                116 -> 1.7381f
                126 -> 1.875f // Spec note: 30S packs are not in WheelLog's list; treat as manual override.
                134 -> 2.00f
                151 -> 2.25f
                168 -> 2.50f
                else -> 1.25f
            }
        }
    }
}
