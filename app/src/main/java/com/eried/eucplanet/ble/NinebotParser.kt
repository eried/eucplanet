package com.eried.eucplanet.ble

import android.util.Log
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelSettings
import com.eried.eucplanet.util.ByteUtils

/**
 * Frame reassembler + parser for both Ninebot protocol families.
 *
 * Wire format (spec docs/protocols/ninebot.md sections 3 and 13):
 *   - Z protocol: `5A A5 <len> <src> <dst> <cmd> <param> <data...> <crc16 LE>`,
 *     total `len + 9` bytes. Encrypted with XOR keystream after the GetKey
 *     handshake; the magic bytes and length byte stay plaintext.
 *   - Legacy protocol: `55 AA <len> <src> <dst> <param> <data...> <crc16 LE>`,
 *     total `len + 6` bytes. Plaintext on the wire, no `cmd` byte.
 *
 * The parser reassembles across BLE notifications (default ATT MTU splits Z
 * live-data replies into two notifies), validates CRC, and dispatches
 * payload bytes to the per-family decoder.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik / Palachzzz and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
class NinebotParser(private val protocol: NinebotProtocol) {

    private val buffer = ArrayDeque<Byte>()

    /**
     * Decoded frame handed to the adapter. Z protocol carries an extra `cmd`
     * byte that the legacy stack does not; we keep both on the same record
     * (cmd = 0 for legacy) so the dispatch logic in the adapter is uniform.
     */
    data class Frame(
        val src: Int,
        val dst: Int,
        val cmd: Int,
        val param: Int,
        val data: ByteArray
    )

    fun reset() {
        buffer.clear()
    }

    /**
     * Push raw notification bytes into the reassembler. The optional
     * [crypto] is consulted only for Z; when a key is installed each
     * candidate frame is decrypted in place before CRC validation. The
     * pre-handshake GetKey request and its reply travel plaintext, so the
     * decoder accepts a CRC match against the raw bytes too — that gives
     * us a clean path for the very first frame of the session.
     */
    fun feed(rawBytes: ByteArray, crypto: NinebotZCrypto? = null): List<Frame> {
        for (b in rawBytes) buffer.addLast(b)
        val out = mutableListOf<Frame>()

        while (true) {
            val frame = tryExtract(crypto) ?: break
            out += frame
        }
        return out
    }

    /**
     * Attempt to extract one full frame from the head of the buffer. Returns
     * null when the buffer doesn't yet contain a full frame; mutates the
     * buffer otherwise (consumes the bytes belonging to the extracted frame
     * or drops a single byte to resync on a bad header / bad CRC).
     */
    private fun tryExtract(crypto: NinebotZCrypto?): Frame? {
        // Slide forward until a recognised magic sequence sits at offsets 0..1.
        while (buffer.size >= 2) {
            val ok = when (protocol) {
                NinebotProtocol.Z      -> buffer[0] == Z_MAGIC_0  && buffer[1] == Z_MAGIC_1
                NinebotProtocol.LEGACY -> buffer[0] == LEG_MAGIC_0 && buffer[1] == LEG_MAGIC_1
            }
            if (ok) break
            buffer.removeFirst()
        }
        if (buffer.size < 3) return null

        val len = buffer[2].toInt() and 0xFF
        val totalSize = when (protocol) {
            NinebotProtocol.Z      -> len + 9 // magic(2) + len(1) + src+dst+cmd+param(4) + data(len) + crc(2)
            NinebotProtocol.LEGACY -> len + 6 // magic(2) + len(1) + src+dst+param(3) + data(len) + crc(2)
        }
        if (buffer.size < totalSize) return null

        val frame = ByteArray(totalSize)
        for (i in 0 until totalSize) frame[i] = buffer[i]

        // Z: try decrypted-first if a key is installed; fall back to plaintext
        // for the rare case of a pre-handshake frame slipping through. CRC
        // mismatch on both means the framing is bogus and we resync by one
        // byte — same conservative strategy KingsongParser uses.
        val verified = when (protocol) {
            NinebotProtocol.Z -> verifyZFrame(frame, crypto)
            NinebotProtocol.LEGACY -> if (crc16Ok(frame, lengthFieldOffset = 2, crcStart = totalSize - 2)) frame else null
        }
        if (verified == null) {
            buffer.removeFirst()
            return null
        }

        repeat(totalSize) { buffer.removeFirst() }

        return when (protocol) {
            NinebotProtocol.Z -> Frame(
                src = verified[3].toInt() and 0xFF,
                dst = verified[4].toInt() and 0xFF,
                cmd = verified[5].toInt() and 0xFF,
                param = verified[6].toInt() and 0xFF,
                data = verified.copyOfRange(7, totalSize - 2)
            )
            NinebotProtocol.LEGACY -> Frame(
                src = verified[3].toInt() and 0xFF,
                dst = verified[4].toInt() and 0xFF,
                cmd = 0,
                param = verified[5].toInt() and 0xFF,
                data = verified.copyOfRange(6, totalSize - 2)
            )
        }
    }

    /**
     * Try to verify a Z frame against the CRC. When a key is present we
     * decrypt a copy first; if that fails CRC, we also try the raw buffer
     * because the GetKey request/reply travels plaintext even after a
     * (logically) installed key would otherwise trigger XOR.
     *
     * Returns the plaintext frame on success (with magic + length kept as
     * received), null on failure.
     */
    private fun verifyZFrame(frame: ByteArray, crypto: NinebotZCrypto?): ByteArray? {
        if (crypto != null && crypto.hasKey) {
            val decrypted = crypto.applyCopy(frame)
            if (crc16Ok(decrypted, lengthFieldOffset = 2, crcStart = decrypted.size - 2)) return decrypted
        }
        // Plaintext path: pre-handshake frames or unencrypted captures.
        return if (crc16Ok(frame, lengthFieldOffset = 2, crcStart = frame.size - 2)) frame else null
    }

    /**
     * CRC16 = ones-complement of the 16-bit running sum of every byte from
     * the length field through the last data byte (i.e. excluding magic and
     * the CRC bytes themselves). Stored u16 LE.
     */
    private fun crc16Ok(frame: ByteArray, lengthFieldOffset: Int, crcStart: Int): Boolean {
        if (crcStart + 1 >= frame.size) return false
        var sum = 0
        for (i in lengthFieldOffset until crcStart) {
            sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFFFF
        }
        val expected = sum xor 0xFFFF
        val got = ((frame[crcStart].toInt() and 0xFF) or
                ((frame[crcStart + 1].toInt() and 0xFF) shl 8)) and 0xFFFF
        return got == expected
    }

    // ---- Z protocol decoders --------------------------------------------------

    /**
     * Z live-data telemetry, param 0xB0 (spec section 8). Data payload is at
     * least 28 bytes; some firmwares append fields we ignore. Voltage scale
     * is the spec's `/100` default — Z10 (84 V class) needs a labelled
     * capture to confirm it's not actually decivolts. See spec open
     * question 1.
     */
    fun parseZTelemetry(data: ByteArray, model: NinebotModel?): WheelData? {
        if (data.size < 28) return null

        val batteryPct = ByteUtils.getUint16LE(data, 8).coerceIn(0, 100)
        val speed = ByteUtils.getUint16LE(data, 10) / 100f
        val totalDistanceMeters = ByteUtils.getUint32LE(data, 14)
        val tripDistanceMeters = ByteUtils.getUint16LE(data, 18) * 10L
        val temperature = ByteUtils.getInt16LE(data, 22) / 10f
        val voltage = ByteUtils.getUint16LE(data, 24) / 100f
        val current = ByteUtils.getInt16LE(data, 26) / 100f

        return WheelData(
            speed = speed,
            voltage = voltage,
            current = current,
            batteryPercent = batteryPct.coerceIn(0, 100),
            temperatures = listOf(temperature),
            maxTemperature = temperature,
            tripDistance = tripDistanceMeters / 1000f,
            totalDistance = totalDistanceMeters / 1000f,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Z settings response. Each setting is its own param (0x70 lock,
     * 0x74 speed limit, 0x7D..0x7F alarms, 0xC6 LED, 0xD3 drive flags,
     * 0xF5 volume), so the adapter is responsible for keeping a running
     * snapshot and merging incoming param replies into it. We expose
     * single-param helpers here and let the adapter do the merge.
     */
    fun parseZLockState(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        return data[0].toInt() and 0xFF
    }

    fun parseZSpeedLimit(data: ByteArray): Float? {
        if (data.size < 2) return null
        return ByteUtils.getUint16LE(data, 0) / 100f
    }

    fun parseZAlarmSpeed(data: ByteArray): Float? {
        if (data.size < 2) return null
        return ByteUtils.getUint16LE(data, 0) / 100f
    }

    /** LED mode 0..7. */
    fun parseZLedMode(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        return data[0].toInt() and 0xFF
    }

    /**
     * Drive flags bitfield (0xD3). Spec section 7 marks bit 3 as
     * "Strain gauge / pedal pressure" with a "?" — the WheelLog comment
     * the spec is paraphrased from has a question mark on that bit, and
     * we have not toggled it on a real Z10 to confirm. See open question 3.
     */
    fun parseZDriveFlags(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        return data[0].toInt() and 0xFF
    }

    /**
     * Speaker volume. The wheel returns the raw byte from 0xF5; the encoded
     * form is `volume << 3` so 0..7 maps to bytes 0x00 / 0x08 / 0x10 / ... /
     * 0x38. Caller divides by 8 to get the 0..7 step.
     */
    fun parseZVolume(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val raw = data[0].toInt() and 0xFF
        return (raw ushr 3) and 0x1F
    }

    fun parseZSerial(data: ByteArray): String? {
        if (data.isEmpty()) return null
        return String(data, Charsets.US_ASCII)
            .trimEnd { it == ' ' || it.code == 0 }
            .ifBlank { null }
    }

    fun parseZFirmware(data: ByteArray): String? {
        if (data.size < 2) return null
        // Two-byte version word. WheelLog's reference treats this as a
        // packed BCD-ish hex, e.g. 0x010A => "1.10". Without a labelled
        // capture we keep the raw hex pair joined by a dot — caller can
        // re-format later.
        val hi = data[1].toInt() and 0xFF
        val lo = data[0].toInt() and 0xFF
        return "$hi.$lo"
    }

    // ---- Legacy protocol decoders ---------------------------------------------

    /**
     * Legacy live-data telemetry, param 0xB0 (spec section 15). Speed
     * offset and scale depend on the variant: Mini and One E/E+ at offset
     * 10..11 (div 10), S2 at offset 28..29 (div 100). Late-firmware One E+
     * may have moved to the S2 layout — see spec open question 6.
     */
    fun parseLegacyTelemetry(data: ByteArray, model: NinebotModel?): WheelData? {
        if (data.size < 28) return null

        val batteryPct = ByteUtils.getUint16LE(data, 8)
        val totalDistanceMeters = ByteUtils.getUint32LE(data, 14)
        val temperature = ByteUtils.getInt16LE(data, 22) / 10f
        val voltage = ByteUtils.getUint16LE(data, 24) / 100f
        val current = ByteUtils.getInt16LE(data, 26) / 100f

        // Speed offset is variant-dependent. WheelLog's adapter conditions on
        // the BleVersion ASCII tag; we condition on the model registry which
        // already encodes the variant. S2 needs at least 30 bytes for the
        // late-frame slot.
        val speed: Float = when (model?.legacyVariant) {
            NinebotLegacyVariant.S2 -> {
                if (data.size < 30) 0f
                else ByteUtils.getInt16LE(data, 28) / 100f
            }
            NinebotLegacyVariant.MINI -> ByteUtils.getInt16LE(data, 10) / 10f
            NinebotLegacyVariant.DEFAULT, null -> ByteUtils.getInt16LE(data, 10) / 10f
        }

        return WheelData(
            speed = speed,
            voltage = voltage,
            current = current,
            batteryPercent = batteryPct.coerceIn(0, 100),
            temperatures = listOf(temperature),
            maxTemperature = temperature,
            totalDistance = totalDistanceMeters / 1000f,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * BleVersion reply on legacy. The wheel returns an ASCII tag that
     * selects the variant ("S2", "Mini", empty for default). We surface
     * just the trimmed string and let the adapter re-resolve the model.
     */
    fun parseLegacyBleVersionTag(data: ByteArray): String? {
        if (data.isEmpty()) return null
        return String(data, Charsets.US_ASCII)
            .trimEnd { it == ' ' || it.code == 0 }
    }

    fun parseLegacySerial(data: ByteArray): String? {
        if (data.isEmpty()) return null
        return String(data, Charsets.US_ASCII)
            .trimEnd { it == ' ' || it.code == 0 }
            .ifBlank { null }
    }

    companion object {
        private const val TAG = "NinebotParser"

        // Z magic bytes. Order on the wire is 5A then A5 (spec section 3).
        private const val Z_MAGIC_0: Byte = 0x5A
        private const val Z_MAGIC_1: Byte = 0xA5.toByte()

        // Legacy magic bytes. Order on the wire is 55 then AA.
        private const val LEG_MAGIC_0: Byte = 0x55
        private const val LEG_MAGIC_1: Byte = 0xAA.toByte()
    }
}
