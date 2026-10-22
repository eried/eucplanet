package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.ByteUtils
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reassembler + parser for the Veteran (LeaperKim) BLE wire format.
 *
 * Frames arrive in fragments over notifications. Both formats start with
 * `DC 5A 5C` and a one-byte LEN; total size is LEN + 4. Short frames
 * (LEN <= 38) have no CRC, while long frames (LEN > 38) carry CRC32 at
 * offset LEN over bytes 0 until LEN. After a valid CRC frame, CRC remains
 * required until disconnect. See docs/protocols/veteran.md.
 */
class VeteranParser {

    /** Reassembled frame plus a flag for which decode path it takes. */
    data class Frame(val bytes: ByteArray, val isLong: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Frame && isLong == other.isLong && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + isLong.hashCode()
    }

    // Buffer holds the partially-assembled frame, starting at the magic.
    // Empty when we're scanning for the next magic.
    private val buffer = ArrayList<Byte>(64)

    // A CRC-verified stream must not later fall back to unverified parsing.
    // Reset this connection-specific mode on disconnect.
    private var usingCrc = false

    /**
     * Accumulate notification bytes in arrival order and return complete frames.
     * Long frames are CRC-checked; short frames use structural checks until a
     * valid CRC frame latches CRC mode. Partial frames persist until the next
     * notification or [reset] on disconnect: a timeout would discard long frames
     * whose notifications arrive far apart.
     */
    fun feed(rawBytes: ByteArray): List<Frame> {
        val out = mutableListOf<Frame>()
        if (rawBytes.isEmpty()) return out

        for (b in rawBytes) {
            buffer.add(b)
            // Trim leading garbage until the buffer either starts with the
            // magic or is empty. Cheap because the magic is rare in random
            // payload, so the loop usually finds it inside one or two
            // shifts after a drop.
            while (buffer.isNotEmpty() && !startsWithMagic()) {
                buffer.removeAt(0)
            }
            // We now either have an empty buffer or one starting with the
            // magic. Try to extract a complete frame.
            while (true) {
                val frame = tryExtractFrame() ?: break
                out += frame
            }
        }
        return out
    }

    /** Drop partial data and reset the connection-specific CRC mode. */
    fun reset() {
        buffer.clear()
        usingCrc = false
    }

    /**
     * True when the buffer begins with the 3-byte Veteran magic. Until the
     * full magic is in, we can't tell yet; return true so the buffer keeps
     * accumulating instead of getting trimmed prematurely.
     */
    private fun startsWithMagic(): Boolean {
        if (buffer.size >= 1 && buffer[0] != MAGIC0) return false
        if (buffer.size >= 2 && buffer[1] != MAGIC1) return false
        if (buffer.size >= 3 && buffer[2] != MAGIC2) return false
        return true
    }

    /**
     * Extract a complete frame or wait for more bytes. Without a CRC, accept
     * only captured legacy layouts; arbitrary lengths can turn embedded magic
     * into unverified telemetry. In latched CRC mode, CRC validation provides
     * that protection for every length. On invalid input drop just the first
     * magic byte to find a real frame inside damaged data.
     */
    private fun tryExtractFrame(): Frame? {
        if (buffer.size < 4) return null
        val len = buffer[3].toInt() and 0xFF
        val isLong = len > 38
        val requiresCrc = isLong || usingCrc
        if (!requiresCrc && len != 32 && len != 34) {
            buffer.removeAt(0)
            return null
        }
        val totalSize = len + 4
        if (buffer.size < totalSize) return null

        val frame = ByteArray(totalSize)
        for (i in 0 until totalSize) frame[i] = buffer[i]
        if (requiresCrc) {
            val crc = CRC32().apply { update(frame, 0, len) }.value
            val expected = ByteUtils.getUint32BE(frame, len)
            if (crc != expected) {
                buffer.removeAt(0)
                return null
            }
            usingCrc = true
        } else {
            val voltageCv = ByteUtils.getUint16BE(frame, 4)
            val speedTenths = ByteUtils.getInt16BE(frame, 6)
            val currentTenths = ByteUtils.getInt16BE(frame, 16)
            val temperatureHundredths = ByteUtils.getInt16BE(frame, 18)
            if (frame[22] != 0.toByte() ||
                (frame[23].toInt() and 0xFE) != 0 ||
                (frame[30] != 0.toByte() && frame[30] != 7.toByte()) ||
                voltageCv !in 5000..25000 ||
                abs(speedTenths) > 2000 ||
                abs(currentTenths) > 4000 ||
                temperatureHundredths !in -4000..15000
            ) {
                buffer.removeAt(0)
                return null
            }
        }

        buffer.subList(0, totalSize).clear()
        return Frame(frame, isLong = isLong)
    }

    companion object {
        private const val MAGIC0: Byte = 0xDC.toByte()
        private const val MAGIC1: Byte = 0x5A.toByte()
        private const val MAGIC2: Byte = 0x5C.toByte()

        // ---- Telemetry parsing ---------------------------------------------------

        /**
         * Parse realtime telemetry from a complete Veteran frame. Offsets in
         * the spec are absolute into the frame; later fields are optional.
         *
         * [model] is the wheel model resolved from the BLE name when known;
         * it picks the per-pack voltage curve and decides whether to trust
         * the hardware PWM field at offsets 34..35 (only valid for
         * model >= Abrams firmware per spec section 7).
         *
         * [signedSpeedMode] mirrors the canonical "gotwayNegative" preference:
         * 0 = unsigned (force absolute), 1 = use the i16 sign as-is,
         * -1 = invert. Default 1 matches what most firmwares emit honestly.
         */
        fun parseTelemetry(
            frame: ByteArray,
            model: VeteranModel?,
            signedSpeedMode: Int = 1
        ): WheelData? {
            // The last mandatory field (temperature) ends at byte 19.
            if (frame.size < 20) return null

            val voltageCv = ByteUtils.getUint16BE(frame, 4)
            val rawSpeed = ByteUtils.getInt16BE(frame, 6)
            val rawTrip = ByteUtils.getWordSwappedUint32(frame, 8)
            val rawTotal = ByteUtils.getWordSwappedUint32(frame, 12)
            val rawCurrent = ByteUtils.getInt16BE(frame, 16)
            val rawTempC = ByteUtils.getInt16BE(frame, 18)
            // Wheel-enforced speed-alert and tilt-back thresholds, both u16 BE
            // in 0.1 km/h. These are set by the vendor (LeaperKim) app on
            // the wheel firmware itself; our adapter has no write command
            // for them (spec section 8) so we surface them read-only so
            // Settings reflects reality.
            val rawAlertKmh = if (frame.size >= 26) ByteUtils.getUint16BE(frame, 24) / 10f else -1f
            val rawTiltbackKmh = if (frame.size >= 28) ByteUtils.getUint16BE(frame, 26) / 10f else -1f

            // mVer from offset 28 (u16 BE / 1000). When non-zero this is
            // the authoritative source for model id, battery curve, PWM
            // gate, beep variant, and cell count; overrides the BLE-name
            // match because many wheels advertise as a generic
            // "Veteran-xxxx" with no model token.
            val mVer = if (frame.size >= 30) ByteUtils.getUint16BE(frame, 28) / 1000 else -1
            val resolvedModel = VeteranModel.fromMVer(mVer) ?: model

            val voltage = voltageCv / 100f
            val speedKmh = applySignMode(rawSpeed, signedSpeedMode) / 10f
            // Note: this offset carries PHASE current. We expose it on the
            // `current` field for now; downstream consumers that need bus
            // current have to derive it from phase * pwm / 100.
            val current = applySignMode(rawCurrent, signedSpeedMode) / 10f
            val tripKm = rawTrip / 1000f
            val totalKm = rawTotal / 1000f
            val tempC = rawTempC / 100f

            // Hardware PWM is only meaningful on mVer >= 2 (Abrams onwards
            // per spec). Older firmwares put garbage there. Fall back to
            // the model-based gate when mVer is unknown.
            val pwm = if (frame.size >= 36 && (mVer >= 2 || hasValidPwm(resolvedModel))) {
                ByteUtils.getUint16BE(frame, 34) / 100f
            } else 0f

            // Pitch angle in hundredths of a degree, signed. Positive forward.
            val pitch = if (frame.size >= 34) ByteUtils.getInt16BE(frame, 32) / 100f else 0f

            // The Oryx reports its own BMS state-of-charge verbatim at byte 50
            // of the page-2 sub-frame (page selector at byte 46), which is the
            // value the wheel and vendor app display. Prefer it; fall back to
            // the voltage curve for the other pages / older firmware. The
            // adapter caches the last page-2 reading so battery doesn't flicker
            // back to the curve on the ~8-in-9 non-page-2 frames.
            val percent = oryxBatterySoc(frame, resolvedModel)
                ?: batteryPercentForModel(voltageCv, resolvedModel)

            // Veteran frames don't carry power directly; estimate from
            // voltage * current so the POWER tile populates.
            val powerW = (voltage * current).toInt()

            // Charge mode byte at offset 0x17 (= 23). >0 means the wheel is
            // on a charger. The base current field at offset 0x10 is too
            // coarse for the balance-phase tail at 100 % (the charger
            // pulses on/off and `current` reads ~0 for long stretches),
            // so the charging-session detector misses balancing without
            // this explicit flag. All wheel pages carry it identically.
            val chargingFlag = frame.size > 23 && (frame[23].toInt() and 0xFF) > 0

            return WheelData(
                speed = speedKmh,
                voltage = voltage,
                current = current,
                // The offset carries PHASE current (see the note above), so the
                // dedicated field mirrors it for surfaces that ask for phase.
                phaseCurrent = current,
                batteryPercent = percent,
                pwm = pwm,
                temperatures = listOf(tempC),
                maxTemperature = tempC,
                tripDistance = tripKm,
                totalDistance = totalKm,
                batteryPower = powerW,
                motorPower = powerW,
                pitchAngle = pitch,
                wheelMaxSpeedKmh = rawTiltbackKmh,
                wheelAlarmSpeedKmh = rawAlertKmh,
                charging = chargingFlag,
                timestamp = System.currentTimeMillis()
            )
        }

        /**
         * Parse a smart-BMS long frame (LEN > 38). Returned data is best-effort:
         * we cover the slices the spec documents (`pnum` 0..7) and silently
         * accept `pnum == 8`, which the spec marks as "contents not yet
         * decoded". Cell-voltage and BMS-temperature arrays are returned
         * separately; the caller merges them into a richer telemetry view
         * once we have UI for per-cell state.
         */
        fun parseLongFrame(frame: ByteArray): BmsSlice? {
            // Smart-BMS frames carry pnum at offset 46. If the buffer is
            // shorter than that, it's mis-framed; drop silently.
            if (frame.size < 47) return null
            val pnum = frame[46].toInt() and 0xFF

            return when (pnum) {
                0, 4 -> {
                    // Pack-current header. Two i16 BE in 0.1 A at 69 and 71.
                    if (frame.size < 73) return null
                    val pack1 = ByteUtils.getInt16BE(frame, 69) / 100f
                    val pack2 = ByteUtils.getInt16BE(frame, 71) / 100f
                    BmsSlice(
                        pnum = pnum,
                        packIndex = if (pnum == 0) 0 else 1,
                        packCurrent1A = pack1,
                        packCurrent2A = pack2
                    )
                }
                1, 5 -> {
                    // Cells 0..14 as 15 i16 BE values at offset 53, /1000 = volts.
                    if (frame.size < 53 + 30) return null
                    val cells = FloatArray(15)
                    for (i in 0 until 15) {
                        cells[i] = ByteUtils.getInt16BE(frame, 53 + i * 2) / 1000f
                    }
                    BmsSlice(
                        pnum = pnum,
                        packIndex = if (pnum == 1) 0 else 1,
                        cellVoltages = cells.toList(),
                        cellRangeStart = 0
                    )
                }
                2, 6 -> {
                    // Cells 15..29 as 15 u16 BE at offset 53. Same scale.
                    if (frame.size < 53 + 30) return null
                    val cells = FloatArray(15)
                    for (i in 0 until 15) {
                        cells[i] = ByteUtils.getUint16BE(frame, 53 + i * 2) / 1000f
                    }
                    BmsSlice(
                        pnum = pnum,
                        packIndex = if (pnum == 2) 0 else 1,
                        cellVoltages = cells.toList(),
                        cellRangeStart = 15
                    )
                }
                3, 7 -> {
                    // Cells 30..41 (up to 12 cells) at offset 59, plus 6 BMS
                    // temps at offsets 47, 49, 51, 53, 55, 57. Each i16 BE,
                    // temps /100 = degrees C, cells /1000 = volts.
                    if (frame.size < 59 + 24) return null
                    val temps = FloatArray(6)
                    for (i in 0 until 6) {
                        temps[i] = ByteUtils.getInt16BE(frame, 47 + i * 2) / 100f
                    }
                    val cells = FloatArray(12)
                    for (i in 0 until 12) {
                        cells[i] = ByteUtils.getInt16BE(frame, 59 + i * 2) / 1000f
                    }
                    BmsSlice(
                        pnum = pnum,
                        packIndex = if (pnum == 3) 0 else 1,
                        cellVoltages = cells.toList(),
                        cellRangeStart = 30,
                        bmsTempsC = temps.toList()
                    )
                }
                8 -> {
                    // Page 8 is a configuration settings frame (decoded by parsePage8),
                    // not a smart-BMS cell telemetry slice. Returning a stub slice
                    // preserves backwards compatibility for callers expecting BmsSlice.
                    BmsSlice(pnum = pnum, packIndex = 0)
                }
                else -> null
            }
        }

        /**
         * Parse a Page 8 configuration settings frame (75 bytes, pageId == 8).
         * Returns null if the frame is too short, does not have pageId 8, or
         * has invalid framing.
         *
         * Offsets 47..70 carry wheel settings. Sentinel value 0x80 (128) denotes
         * an unsupported or inactive field, which maps to null (never zero-filled).
         */
        fun parsePage8(
            frame: ByteArray,
            receivedAtNanos: Long = System.nanoTime()
        ): VeteranPage8Settings? {
            if (frame.size < 71 || pageId(frame) != 8) return null

            fun extractByte(offset: Int): Int? {
                if (offset >= frame.size) return null
                val raw = frame[offset].toInt() and 0xFF
                return if (raw == 0x80) null else raw
            }

            fun extractSignedByte(offset: Int): Int? {
                if (offset >= frame.size) return null
                val raw = frame[offset]
                return if (raw == 0x80.toByte()) null else raw.toInt()
            }

            val pedalHardness = extractByte(50)?.takeIf { it in 0..100 }
            val tiltbackSpeed = extractByte(52)?.takeIf { it in 10..120 || it == 200 }
            val pwmTiltback = extractByte(53)?.takeIf { it in 30..100 || it == 200 }
            val alarmSpeed = extractByte(54)?.takeIf { it in 10..120 }
            val displayBrightness = extractByte(55)?.takeIf { it in 0..100 }
            val gyroCalibration = extractByte(56)?.takeIf { it in 0..2 }
            val transport = extractByte(57)?.let { it == 1 }
            val displayUnits = extractByte(58)?.takeIf { it in 0..1 }
            val voltageAdjustment = extractSignedByte(59)?.takeIf { it in -15..15 }
            val lowBatteryMode = extractByte(60)?.takeIf { it in 0..1 }
            val highSpeedMode = extractByte(61)?.takeIf { it in 0..1 }
            val keyToneVolume = extractByte(63)?.takeIf { it in 0..100 }
            val maxChargeVoltageRaw = extractByte(64)?.takeIf { it in 0..120 }
            val voltageBase = extractByte(65)
            val dynamicAssist = extractByte(66)?.takeIf { it in 0..100 }
            val pedalDipCompensation = extractByte(68)?.takeIf { it in 0..100 }
            val headlightMode = extractByte(47)?.takeIf { it in 0..3 }

            val rawPayload = if (frame.size >= 71) {
                frame.copyOfRange(47, 71)
            } else {
                ByteArray(0)
            }

            return VeteranPage8Settings(
                headlightMode = headlightMode,
                pedalHardnessPercent = pedalHardness,
                tiltbackSpeedKmh = tiltbackSpeed,
                pwmTiltbackPercent = pwmTiltback,
                alarmSpeedKmh = alarmSpeed,
                displayBrightnessPercent = displayBrightness,
                gyroCalibrationState = gyroCalibration,
                transportMode = transport,
                displayUnits = displayUnits,
                voltageAdjustmentTenths = voltageAdjustment,
                lowBatteryMode = lowBatteryMode,
                highSpeedMode = highSpeedMode,
                keyToneVolumePercent = keyToneVolume,
                maxChargeVoltageRaw = maxChargeVoltageRaw,
                voltageBase = voltageBase,
                dynamicAssistPercent = dynamicAssist,
                pedalDipCompensationPercent = pedalDipCompensation,
                receivedAtNanos = receivedAtNanos,
                rawPayload = rawPayload,
            )
        }

        // --- Helpers --------------------------------------------------------------

        /** Page id of the rotating sub-frame, at byte 46 (cycles 0..8). -1 if
         *  the frame is too short to carry it. */
        fun pageId(frame: ByteArray): Int =
            if (frame.size >= 47) frame[46].toInt() and 0xFF else -1

        /** Major model version (mVer) from the u16 at byte 28 / 1000. -1 if the
         *  frame is too short. mVer 8 == Oryx. */
        fun mVerOf(frame: ByteArray): Int =
            if (frame.size >= 30) ByteUtils.getUint16BE(frame, 28) / 1000 else -1

        /**
         * Oryx battery: the wheel's own BMS SoC, read verbatim from byte 50, but
         * only valid in the page-2 sub-frame. Returns null for other models, the
         * other pages, or an out-of-range value so the caller uses the voltage
         * curve instead.
         */
        fun oryxBatterySoc(frame: ByteArray, model: VeteranModel?): Int? {
            if (model != VeteranModel.ORYX) return null
            if (pageId(frame) != 2) return null
            if (frame.size < 51) return null
            val soc = frame[50].toInt() and 0xFF
            return if (soc in 0..100) soc else null
        }

        private fun applySignMode(raw: Int, mode: Int): Int = when (mode) {
            0 -> abs(raw)
            -1 -> -raw
            else -> raw
        }

        /**
         * Per-model linear battery curve. Constants kept exactly as spec to
         * preserve fidelity with the wheel's own LED display.
         * Spec: docs/protocols/veteran.md section 7.
         */
        private fun batteryPercentForModel(voltageCv: Int, model: VeteranModel?): Int {
            // Per-pack-class linear curves: Sherman / Abrams / Sherman S /
            // Sherman Max share the 100 V 24-cell range, 134 V wheels (Patton +
            // Patton S) and 126 V wheels (Nosfet Aero + Nosfet Xeno) their own,
            // and 151 V wheels (Lynx + Sherman L + Lynx S + Nosfet Apex + Nosfet Aeon) theirs.
            val percent = when (model) {
                VeteranModel.PATTON,
                VeteranModel.PATTON_S,
                VeteranModel.NOSFET_AERO,
                VeteranModel.NOSFET_XENO ->
                    ((voltageCv - 9918) / 24.2f).roundToInt()
                VeteranModel.LYNX,
                VeteranModel.LYNX_S,
                VeteranModel.SHERMAN_L,
                VeteranModel.NOSFET_APEX,
                VeteranModel.NOSFET_AEON ->
                    ((voltageCv - 11902) / 29.03f).roundToInt()
                // Oryx (mVer 8) is a 42-cell ~175 V pack. Calibrated to the
                // wheel's own readout: ~94 % at 171.5 V, reaching 100 % near
                // 173.3 V. The previous curve read a near-full pack as 0 %.
                // Single linear pass until a low-charge sample lets the bottom
                // end be tuned; coerceIn below clamps the unmeasured extremes.
                VeteranModel.ORYX ->
                    ((voltageCv - 14280) / 30.5f).roundToInt()
                // SHERMAN / ABRAMS / SHERMAN_S / SHERMAN_MAX / null all share
                // the 24-cell 100 V range.
                else ->
                    ((voltageCv - 7935) / 19.5f).roundToInt()
            }
            return percent.coerceIn(0, 100)
        }

        /**
         * Per spec, hardware PWM at offsets 34..35 is only valid on mVer >= 2
         * firmwares (Abrams onwards). For the older 100 V Sherman family the
         * field carries garbage; callers should compute PWM locally from
         * speed+current there. The mVer-based gate in `parseTelemetry` takes
         * precedence over this fallback when the version byte is present.
         */
        private fun hasValidPwm(model: VeteranModel?): Boolean = when (model) {
            VeteranModel.SHERMAN, null -> false
            else -> true
        }
    }

    /**
     * Decoded smart-BMS slice. `pnum` identifies which kind of slice it is;
     * the caller stitches successive slices together over time to build a
     * full per-cell view. Fields that don't apply to a given slice stay null.
     *
     * Spec: docs/protocols/veteran.md section 5 ("BMS frame").
     */
    data class BmsSlice(
        val pnum: Int,
        val packIndex: Int,
        val cellVoltages: List<Float>? = null,
        val cellRangeStart: Int? = null,
        val bmsTempsC: List<Float>? = null,
        val packCurrent1A: Float? = null,
        val packCurrent2A: Float? = null
    )
}
