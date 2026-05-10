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
 * LEN that declares total frame length minus 4 (header), then LEN-1 payload
 * bytes, then an optional 4-byte big-endian CRC32 when LEN > 38 (smart-BMS
 * wheels). Wire layout, offsets, scale factors and per-model voltage curves
 * come from docs/protocols/veteran.md.
 *
 * The parser is a small state machine driven by [feed]. A 100 ms gap without
 * continuation drops the partial buffer and re-syncs on the next magic — the
 * connection manager calls [feed] from the same coroutine that schedules
 * notifications, so wall-clock time on each call is the right re-sync clock.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
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

        // Stale partial — the wheel paused mid-frame. Drop what we had so
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
     * full magic is in, we can't tell yet — return true so the buffer keeps
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
     */
    private fun tryExtractFrame(): Frame? {
        if (buffer.size < 4) return null
        val len = buffer[3].toInt() and 0xFF
        // Total frame size pre-CRC is LEN + 3 bytes (magic + LEN + payload).
        // Long frames (LEN > 38) carry a 4-byte CRC32 trailer.
        val hasCrc = len > LONG_FRAME_THRESHOLD
        val totalSize = len + 3 + if (hasCrc) 4 else 0
        if (buffer.size < totalSize) return null

        val frame = ByteArray(totalSize)
        for (i in 0 until totalSize) frame[i] = buffer[i]

        // Pop the bytes we just consumed. ArrayList.subList(...).clear() is
        // the cheapest contiguous-prefix removal Kotlin gives us.
        buffer.subList(0, totalSize).clear()

        if (hasCrc) {
            // CRC32 over the bytes from magic through end-of-payload, big-endian
            // 4-byte trailer. Mismatch means we resync on the next magic;
            // partial garbage that aligned with a `DC 5A 5C` triple gets
            // eaten this way.
            val crc = CRC32().apply { update(frame, 0, totalSize - 4) }.value
            val expected = ByteUtils.getUint32BE(frame, totalSize - 4)
            if (crc != expected) return null
        }

        val isLong = len > LONG_FRAME_THRESHOLD
        return Frame(frame, isLong)
    }

    companion object {
        private const val MAGIC0: Byte = 0xDC.toByte()
        private const val MAGIC1: Byte = 0x5A.toByte()
        private const val MAGIC2: Byte = 0x5C.toByte()

        // Spec: short telemetry frame is LEN=38; anything larger is a
        // smart-BMS frame and carries a CRC32 trailer.
        private const val LONG_FRAME_THRESHOLD = 38

        // Spec section 3 step 6: drop partial buffers if the wheel goes quiet
        // mid-frame for ~100 ms.
        private const val RESYNC_TIMEOUT_MS = 100L

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

            val voltage = voltageCv / 100f
            val speedKmh = applySignMode(rawSpeed, signedSpeedMode) / 10f
            val current = applySignMode(rawCurrent, signedSpeedMode) / 10f
            val tripKm = rawTrip / 1000f
            val totalKm = rawTotal / 1000f
            val tempC = rawTempC / 100f

            // Hardware PWM is only meaningful on model >= 2 (Abrams onwards
            // per spec). Older firmwares put garbage there; computing PWM
            // locally from speed+current is left to a higher layer because
            // it needs the rider's reported max-speed.
            val pwm = if (frame.size >= 36 && hasValidPwm(model)) {
                ByteUtils.getUint16BE(frame, 34) / 100f
            } else 0f

            // Pitch angle in hundredths of a degree, signed. Positive forward.
            val pitch = if (frame.size >= 34) ByteUtils.getInt16BE(frame, 32) / 100f else 0f

            val percent = batteryPercentForModel(voltageCv, model)

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
                pitchAngle = pitch,
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
            // shorter than that, it's mis-framed — drop silently.
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
            // We pick the curve from the resolved model when known; otherwise
            // use the nominal-voltage hint on VeteranModel as a coarse
            // disambiguator. Spec groups Sherman/Sherman S/Sherman Max/Abrams
            // under the 24-cell 100V class even though the user-facing
            // VeteranModel.nominalVoltage tags Abrams as 168V — the spec
            // ranges win because the wheel reports raw centivolts.
            val percent = when (model) {
                VeteranModel.SHERMAN, VeteranModel.SHERMAN_S, VeteranModel.SHERMAN_MAX,
                VeteranModel.ABRAMS, null ->
                    ((voltageCv - 7935) / 19.5f).roundToInt()
                VeteranModel.PATTON ->
                    ((voltageCv - 9918) / 24.2f).roundToInt()
                VeteranModel.LYNX ->
                    ((voltageCv - 11902) / 29.03f).roundToInt()
            }
            return percent.coerceIn(0, 100)
        }

        /**
         * Per spec, hardware PWM at offsets 34..35 is only valid on model >= 2
         * firmwares (Abrams onwards). For the older 100 V Sherman family the
         * field carries garbage; callers should compute PWM locally from
         * speed+current there. We approximate the model gate by enum identity
         * because the firmware version byte is only present mid-frame and is
         * consumed by the same parser pass.
         */
        private fun hasValidPwm(model: VeteranModel?): Boolean = when (model) {
            VeteranModel.SHERMAN -> false
            null -> false
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
