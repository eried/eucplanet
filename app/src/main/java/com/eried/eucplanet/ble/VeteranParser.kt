package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.ByteUtils
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reassembler + parser for the Veteran (LeaperKim) BLE wire format.
 *
 * Frames are sent in pieces over notifications and must be reassembled before
 * we can parse them. Each frame starts with the magic `DC 5A 5C`, a one-byte
 * LEN at offset 3, then payload bytes; total buffer size is always LEN + 4
 * bytes. For long (LEN > 38, smart-BMS) frames the last 4 bytes are a
 * big-endian CRC32 covering bytes 0..LEN-1; short frames have no CRC and
 * the full LEN + 4 bytes are payload data. Wire layout, offsets, scale
 * factors and per-model voltage curves come from docs/protocols/veteran.md.
 *
 * The parser is a small state machine driven by [feed]. A 100 ms gap without
 * continuation drops the partial buffer and re-syncs on the next magic; the
 * connection manager calls [feed] from the same coroutine that schedules
 * notifications, so wall-clock time on each call is the right re-sync clock.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
 * reference; the implementation here is original).
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

    // Wall-clock millis of the last byte that landed in the buffer. Used to
    // drop stale partials when the wheel goes quiet mid-frame (e.g. the BLE
    // stack dropped a notification). Spec calls for ~100 ms tolerance.
    private var lastFedAt: Long = 0L

    /**
     * Push raw notification bytes into the reassembler. Returns zero or more
     * complete frames in arrival order. Each returned frame is the full
     * reassembled buffer including magic, LEN, payload, and (if present)
     * CRC32 trailer; CRC has already been verified against the payload, so
     * downstream code can ignore the trailing 4 bytes.
     *
     * The 100 ms re-sync is checked against [now] so tests can drive the
     * clock; production callers pass System.currentTimeMillis().
     */
    fun feed(rawBytes: ByteArray, now: Long = System.currentTimeMillis()): List<Frame> {
        val out = mutableListOf<Frame>()
        if (rawBytes.isEmpty()) return out

        // Stale partial: the wheel paused mid-frame. Drop what we had so
        // the new bytes can re-sync on their own magic; bytes inside the
        // dropped chunk that happened to look like a magic triple are gone
        // for good, but that's the right trade per spec.
        if (buffer.isNotEmpty() && (now - lastFedAt) > RESYNC_TIMEOUT_MS) {
            buffer.clear()
        }
        lastFedAt = now

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

    /** Drop any partial frame and reset the re-sync clock. */
    fun reset() {
        buffer.clear()
        lastFedAt = 0L
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
     * Pull one complete frame off the front of the buffer, or return null if
     * we don't have enough bytes yet. Drops the frame and re-syncs if the
     * CRC fails on a long frame.
     *
     * Frame size rule (verified against WheelLog's VeteranAdapter and against
     * a captured Lynx S long frame whose CRC32 over the first LEN bytes
     * matched the trailing 4 bytes exactly): total buffer = LEN + 4 bytes,
     * always. For LEN > 38 the last 4 bytes are a CRC32 trailer covering
     * bytes 0..LEN-1. For short (LEN <= 38) frames there is no CRC and all
     * LEN+4 bytes are frame content (magic + LEN_byte + payload). An earlier
     * version of this parser used LEN + 3 (+ optional 4 for CRC) which was
     * off by 3 bytes for long frames and one of the causes of BMS-equipped
     * Veterans (Lynx, Lynx S, Patton smart-BMS, Oryx) showing 0V / 0% on
     * the dashboard.
     */
    private fun tryExtractFrame(): Frame? {
        if (buffer.size < 4) return null
        val len = buffer[3].toInt() and 0xFF
        val totalSize = len + 4
        if (buffer.size < totalSize) return null

        val frame = ByteArray(totalSize)
        for (i in 0 until totalSize) frame[i] = buffer[i]

        // Pop the bytes we just consumed. ArrayList.subList(...).clear() is
        // the cheapest contiguous-prefix removal Kotlin gives us.
        buffer.subList(0, totalSize).clear()

        val isLong = len > LONG_FRAME_THRESHOLD
        if (isLong) {
            // CRC32 covers bytes 0..LEN-1 (magic + LEN_byte + payload); trailer
            // sits at offsets LEN..LEN+3 as a big-endian u32. Mismatch means
            // we resync on the next magic; partial garbage that aligned with
            // a `DC 5A 5C` triple gets eaten this way.
            val crc = CRC32().apply { update(frame, 0, len) }.value
            val expected = ByteUtils.getUint32BE(frame, len)
            if (crc != expected) return null
        }

        return Frame(frame, isLong)
    }

    companion object {
        private const val MAGIC0: Byte = 0xDC.toByte()
        private const val MAGIC1: Byte = 0x5A.toByte()
        private const val MAGIC2: Byte = 0x5C.toByte()

        // Spec: short telemetry frame is LEN=38; anything larger is a
        // smart-BMS frame and carries a CRC32 trailer.
        private const val LONG_FRAME_THRESHOLD = 38

        // Drop partial buffers if the wheel goes quiet mid-frame. The spec
        // suggested ~100 ms, but the Oryx delivers each frame as two BLE
        // notifications ~190 ms apart -- a long frame (87 bytes) straddles
        // the gap, so a 100 ms timeout cleared the first half before the
        // second arrived and the parser never produced any frames. 500 ms
        // gives a ~2.5x margin on the observed gap while still discarding
        // genuinely-stale buffers when the wheel disconnects mid-frame.
        private const val RESYNC_TIMEOUT_MS = 500L

        // ---- Telemetry parsing ---------------------------------------------------

        /**
         * Parse a short realtime frame (LEN == 38). The buffer is the full
         * reassembled wire bytes including magic and LEN; offsets in the
         * spec are absolute into this buffer.
         *
         * [model] is the wheel model resolved from the BLE name when known;
         * it picks the per-pack voltage curve and decides whether to trust
         * the hardware PWM field at offsets 34..35 (only valid for
         * model >= Abrams firmware per spec section 7).
         *
         * [signedSpeedMode] mirrors WheelLog's "GotwayNegative" preference:
         * 0 = unsigned (force absolute), 1 = use the i16 sign as-is, -1 = invert.
         * Default 1 matches what most firmwares emit honestly.
         */
        fun parseTelemetry(
            frame: ByteArray,
            model: VeteranModel?,
            signedSpeedMode: Int = 1
        ): WheelData? {
            // Need at least magic + LEN + the full 38-byte short frame.
            if (frame.size < 36) return null

            val voltageCv = ByteUtils.getUint16BE(frame, 4)
            val rawSpeed = ByteUtils.getInt16BE(frame, 6)
            val rawTrip = ByteUtils.getWordSwappedUint32(frame, 8)
            val rawTotal = ByteUtils.getWordSwappedUint32(frame, 12)
            val rawCurrent = ByteUtils.getInt16BE(frame, 16)
            val rawTempC = ByteUtils.getInt16BE(frame, 18)
            // Wheel-enforced speed-alert and tilt-back thresholds, both u16 BE
            // in 0.1 km/h. These are set by the vendor (LeaperKim) app on the
            // wheel firmware itself; our adapter has no write command for them
            // (spec section 8) so we surface them read-only so Settings reflects
            // reality. WheelLog reference at VeteranAdapter.java:46-47.
            val rawAlertKmh = if (frame.size >= 26) ByteUtils.getUint16BE(frame, 24) / 10f else -1f
            val rawTiltbackKmh = if (frame.size >= 28) ByteUtils.getUint16BE(frame, 26) / 10f else -1f

            // mVer from offset 28 (u16 BE / 1000 per WheelLog convention).
            // When non-zero this is the authoritative source for model id,
            // battery curve, PWM gate, beep variant, and cell count;
            // overrides the BLE-name match because many wheels advertise
            // as a generic "Veteran-xxxx" with no model token.
            val rawVersion = if (frame.size >= 30) ByteUtils.getUint16BE(frame, 28) else 0
            val mVer = rawVersion / 1000
            val resolvedModel = VeteranModel.fromMVer(mVer) ?: model

            val voltage = voltageCv / 100f
            val speedKmh = applySignMode(rawSpeed, signedSpeedMode) / 10f
            // Note: this offset carries PHASE current per WheelLog's
            // setPhaseCurrent. We expose it on the `current` field for now;
            // downstream consumers that need bus current have to derive
            // it from phase * pwm / 100.
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
            return WheelData(
                speed = speedKmh,
                voltage = voltage,
                current = current,
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
                    // Spec open question: pnum == 8 is a newer packet type
                    // whose contents are not yet decoded. Returning a stub
                    // slice keeps the dispatcher simple; we just don't
                    // populate any of the optional fields.
                    BmsSlice(pnum = pnum, packIndex = 0)
                }
                else -> null
            }
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
            // Patton S + Nosfet Aero) their own, and 151 V wheels (Lynx +
            // Sherman L + Lynx S + Nosfet Apex + Nosfet Aeon) theirs.
            val percent = when (model) {
                VeteranModel.PATTON,
                VeteranModel.PATTON_S,
                VeteranModel.NOSFET_AERO ->
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
