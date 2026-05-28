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
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
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
    /** Begode firmware has no discrete park/drive state, so we derive it from
     *  speed in parseLiveA and surface it as pcMode so the dashboard's P/D
     *  cluster lights up while moving. 3 = idle (stopped), 1 = drive. */
    @Volatile private var lastPcMode: Int = 3

    /** True once we have ever seen a 0x07 extras frame; from then on, we trust 0x07 PWM/current over 0x00 derivations. */
    @Volatile private var hasExtras: Boolean = false

    /**
     * True once a Freestyl3r (CF) or SmirnoV (BF) firmware banner identifies
     * this wheel as one that populates a real hardware-PWM value at frame
     * 0x00 offset 14. Stock Begode (GW) and ExtremeBull (JN) firmware leave
     * that field unpopulated; usually 0, but some builds (e.g. Mten3) emit a
     * constant noise value of 1, so PWM must be derived there instead. Set by
     * [BegodeAdapter] when it decodes the V-query banner; defaults false so
     * unknown / un-probed firmware derives, which is the safe choice.
     */
    @Volatile var hwPwmFirmware: Boolean = false

    /**
     * Whether the wheel's on-screen display is set to imperial units. Read
     * from Live B settings bitfield bit 0. When set, Begode firmware emits
     * speed/distance/max-speed values pre-scaled to mph/miles on the wire
     * (not km/h-scaled as the protocol comments suggest), so downstream we
     * have to multiply by 1.609 to get back to the canonical km internal
     * unit. Defaults to false until the first Live B frame; the brief
     * mismatch on the very first 0x00 frame after pairing is bounded to a
     * single 0.2 s telemetry tick and not worth a barrier.
     */
    @Volatile private var wheelInMiles: Boolean = false

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
        lastPcMode = 3
        hasExtras = false
        hwPwmFirmware = false
        wheelInMiles = false
    }

    /**
     * Push raw bytes from a BLE notification into the reassembler and emit
     * a [DecodeResult] for every complete 24-byte frame found, in order.
     *
     * Re-sync rules at small buffer sizes mirror spec 3.6 verbatim; they
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
                // "skip unless you target that FW"; log once-ish and move on.
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
        // only; we expose signed and let the UI normalise. See open question
        // 9 "reverse speed sign".
        val rawSpeed = ByteUtils.getInt16BE(frame, 4)
        val rawSpeedKmh = rawSpeed * 3.6f / 100f
        // If the rider has flipped their wheel's screen to imperial via the
        // Begode app, the wheel ships speed/distance pre-scaled to mph/miles
        // on the wire even though the protocol spec doesn't acknowledge it.
        // Multiply by MILES_TO_KM so every downstream consumer keeps seeing
        // canonical km/h. (Reported by riders comparing wheel + GPS speed in
        // Compare; WheelLog has the same bug but masks it by auto-flipping
        // its own display preference to mph in that case.)
        val speed = if (wheelInMiles) rawSpeedKmh * MILES_TO_KM else rawSpeedKmh

        // WheelLog reads only the low u16 at offset 8 as trip-meters; the high
        // word at 6..7 is unused on most firmwares (open question 9). We follow
        // that convention so we don't render bogus distance from older wheels.
        val tripMeters = ByteUtils.getUint16BE(frame, 8)
        val tripKmRaw = tripMeters / 1000f
        val tripKm = if (wheelInMiles) tripKmRaw * MILES_TO_KM else tripKmRaw

        val phaseCurrent = ByteUtils.getInt16BE(frame, 10) / 100f

        // Temperature: raw IMU register, MPU6050 default formula since we
        // don't yet parse the `MPU` ASCII banner that would distinguish 6500.
        val rawTemp = ByteUtils.getInt16BE(frame, 12)
        val tempC = rawTemp / 340f + 36.53f

        // Hardware PWM at offset 14 carries a real reading only on Freestyl3r
        // (CF) and SmirnoV (BF) firmware. Stock Begode (GW) and ExtremeBull
        // (JN) firmware leave it unpopulated; usually 0, but some builds
        // (e.g. Mten3) emit a constant noise value of 1, so a bare "non-zero"
        // test wrongly latches onto 0.1 %. We therefore trust offset 14 only
        // when a CF/BF banner has flagged the firmware as HW-PWM capable
        // ([hwPwmFirmware]); otherwise we derive PWM from speed/voltage like
        // WheelLog, so Master / Mten3 / EX30 / E20 riders see a real number.
        val hardwarePwmRaw = ByteUtils.getInt16BE(frame, 14)
        val hardwarePwmPct = hardwarePwmRaw / 10f
        val pwmPct = when {
            hasExtras -> lastPwmPct
            hwPwmFirmware && hardwarePwmPct != 0f -> hardwarePwmPct
            else -> derivedPwmPct(model, voltage, speed)
        }

        val battPct = batteryPercentFromRawCv(rawCv)

        lastVoltage = voltage
        lastBatteryPct = battPct
        lastSpeedKmh = speed
        lastPhaseCurrent = phaseCurrent
        lastTempC = tempC
        lastTripKm = tripKm
        if (!hasExtras) lastPwmPct = pwmPct
        lastPcMode = if (kotlin.math.abs(speed) > 0.5f) 1 else 3

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
            pcMode = lastPcMode,
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
        val maxSpeedRaw = if (rawMax >= 200) 0f else rawMax.toFloat()

        // Settings bitfield at offset 6 (u16 BE). Bit 0 is the imperial-units
        // flag, set when the rider has switched the wheel's screen to mph in
        // the Begode app. We stash it on the parser so subsequent Live A
        // frames can de-convert speed/distance back to km internally; the
        // tiltback (max speed) on this same frame is in mph too when the
        // flag is set, so undo it here as well.
        val settings = ByteUtils.getUint16BE(frame, 6)
        wheelInMiles = (settings and 0x0001) != 0
        val maxSpeed = if (wheelInMiles) maxSpeedRaw * MILES_TO_KM else maxSpeedRaw

        // Light mode at offset 15. Low 2 bits: 0=off, 1=on, 2=strobe.
        val lightBits = frame[15].toInt() and 0x03
        lastLightOn = lightBits != 0

        return WheelSettings(
            maxSpeedKmh = maxSpeed
        )
    }

    /**
     * BMS summary (spec 4.7 frame 0x01). WheelLog only applies this frame's
     * voltage when the rider explicitly enables `autoVoltage`, because BMS
     * voltage on stock Begode firmware disagrees with the Live A scaled
     * reading (different reference point) and produces voltage / battery
     * flicker on Master and other high-voltage packs. We follow that default
     * and drop the frame entirely (Live A is the source of truth) until we
     * surface per-cell BMS in a dedicated UI that justifies a user opt-in.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun parseBmsSummary(frame: ByteArray, model: BegodeModel?): WheelData? = null

    /**
     * Extras frame (spec 4.6 tag 0x07). Carries true battery current,
     * motor temperature and true PWM; present on post-2022 firmwares.
     * After the first 0x07 we trust these over the derived values from 0x00.
     */
    private fun parseExtras(frame: ByteArray): WheelData? {
        // WheelLog inverts the sign here (`setCurrent((-1) * batteryCurrent)`)
        // so positive current means motoring and negative means regen; the
        // convention used everywhere else in the app. Without the flip the
        // dashboard reads backwards during acceleration vs braking.
        val battCurrent = -(ByteUtils.getInt16BE(frame, 2) / 100f)
        val motorTempC = ByteUtils.getInt16BE(frame, 6).toFloat()
        // 0x07 offset 8 carries true PWM as a signed short already in PERCENT
        // (raw 50 = 50 % PWM). WheelLog confirms: `wd.setOutput(hwPWMb * 100);
        // mCalculatedPwm = mOutput/10000.0; getCalculatedPwm = mCalculatedPwm
        // * 100.0` collapses to raw == percent. Our previous `/ 100f` was
        // dividing again and producing 0.x % for every reading.
        val truePwmRaw = ByteUtils.getInt16BE(frame, 8)
        val truePwm = truePwmRaw.toFloat()

        // Only latch onto the 0x07 PWM path when the field is actually
        // populated, matching WheelLog's `Math.abs(hwPWMb) > 0` arming check.
        // Some Begode firmwares emit 0x07 frames with offset 8 = 0 at idle,
        // and the old unconditional latch silently locked us out of the
        // 0x00 / derived PWM fallbacks forever after.
        if (truePwmRaw != 0) {
            hasExtras = true
            lastPwmPct = truePwm
        }
        lastPhaseCurrent = battCurrent

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
            pcMode = lastPcMode,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Stock Begode firmware doesn't populate either the 0x07 true-PWM field
     * or the 0x00 hardware-PWM field, so without a fallback the dashboard
     * reads 0 % forever on Master / Mten3 / EX30 / E20 and friends. WheelLog
     * covers this case by deriving:
     *
     *   pwm = speed / (rotationSpeed / rotationVoltage * voltage * powerFactor)
     *
     * with per-model `rotationSpeed` (km/h at full PWM) and `rotationVoltage`
     * (V at full PWM) constants from `DialogHelper.kt:90-114`. Default
     * `powerFactor` is 0.9.
     *
     * Returns a value in 0..100 (percent). Models we don't have explicit
     * numbers for fall back to WheelLog's stock defaults (50, 84), which is
     * qualitatively correct but quantitatively over-reads on high-voltage
     * wheels; better to refine via a labelled capture than to ship 0 %.
     */
    private fun derivedPwmPct(model: BegodeModel?, voltage: Float, speedKmh: Float): Float {
        if (voltage <= 0f) return 0f
        val (rotSpeed, rotVoltage) = rotationConstantsFor(model)
        val powerFactor = 0.9f
        val denom = (rotSpeed / rotVoltage) * voltage * powerFactor
        if (denom <= 0f) return 0f
        return (kotlin.math.abs(speedKmh) / denom * 100f).coerceIn(0f, 100f)
    }

    /** WheelLog `DialogHelper.kt:90-114` Begode rotation constants by model. */
    private fun rotationConstantsFor(model: BegodeModel?): Pair<Float, Float> = when (model) {
        BegodeModel.MTEN4    -> 56.0f to 84.0f
        BegodeModel.MTEN5    -> 79.0f to 100.8f
        BegodeModel.MCM5_V1  -> 44.0f to 67.2f
        BegodeModel.MCM5_V2  -> 64.0f to 84.0f
        BegodeModel.MSX      -> 95.0f to 100.8f
        BegodeModel.MSP      -> 100.5f to 100.8f
        BegodeModel.HERO     -> 105.0f to 100.8f
        BegodeModel.EX       -> 79.0f to 100.8f
        BegodeModel.EX_N     -> 107.1f to 100.8f
        BegodeModel.EX2      -> 107.1f to 100.8f
        BegodeModel.EX30     -> 110.0f to 134.4f
        BegodeModel.RS       -> 105.0f to 100.8f
        BegodeModel.RS_HT    -> 79.0f to 100.8f
        BegodeModel.T3       -> 66.5f to 84.0f
        BegodeModel.T4       -> 66.5f to 84.0f
        BegodeModel.MASTER   -> 113.0f to 134.4f
        BegodeModel.MASTER_PRO -> 113.0f to 134.4f
        else -> 50.0f to 84.0f   // WheelLog AppConfig defaults, generic fallback
    }

    /**
     * Battery percent from raw centi-volts on the 84 V reference scale (spec
     * 4.3, "better percents" curve). Callers must pass the *unscaled* cV so
     * the curve constants line up; for non-84 V packs we divide voltage by
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
        /** Used to de-convert Begode wire values when the wheel's screen is
         *  in imperial mode; see [wheelInMiles]. */
        private const val MILES_TO_KM: Float = 1.609344f

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
                // 50S packs (Begode Race) - not in WheelLog's stock table, but
                // ratio = nominalV / 67.2 fits the family pattern and matches
                // labelled cell-voltage telemetry (4.085 V x 50 = 204 V real).
                210 -> 3.125f
                else -> 1.25f
            }
        }
    }
}
