package com.eried.eucplanet.ble

import java.io.ByteArrayOutputStream

/**
 * Wire format for the InMotion V1 family (V5 / V8 / V10 / L6 / R-series / V3).
 * Frames look like `AA AA <escaped CAN frame> <escaped checksum> 55 55` where
 * the 16-byte CAN prefix may be followed by an extended payload; see
 * docs/protocols/inmotion_v1.md sections 3 and 3.2.
 *
 * Bytes `0xAA`, `0x55` and `0xA5` cannot appear unescaped inside the body or
 * checksum; each is prefixed with `0xA5`. The header `AA AA` and trailer
 * `55 55` pairs are always literal; they are NOT escaped.
 *
 * Checksum is `sum(unescaped CAN bytes) mod 256` and is itself escaped on the
 * same rules.
 *
 * Protocol research credit: the WheelLog community (
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
object InMotionV1Protocol {

    const val HEADER: Byte = 0xAA.toByte()
    const val TRAILER: Byte = 0x55.toByte()
    const val ESCAPE: Byte = 0xA5.toByte()

    /** Standard CAN frame metadata for phone-to-wheel data frames. */
    private const val LEN_NORMAL: Byte = 0x08
    private const val CHANNEL_PHONE: Byte = 0x05
    private const val FORMAT_STANDARD: Byte = 0x00
    private const val TYPE_DATA: Byte = 0x00
    private const val TYPE_REMOTE: Byte = 0x01

    /**
     * Build a 16-byte CAN frame, then wrap it in the BLE envelope (header,
     * escape, checksum, trailer). [canId] is written little-endian at offset 0;
     * [data] must be exactly 8 bytes.
     */
    fun buildFrame(canId: Int, data: ByteArray, remote: Boolean = false): ByteArray {
        require(data.size == 8) { "V1 CAN data must be 8 bytes (got ${data.size})" }
        val can = ByteArray(16)
        can[0] = (canId and 0xFF).toByte()
        can[1] = ((canId ushr 8) and 0xFF).toByte()
        can[2] = ((canId ushr 16) and 0xFF).toByte()
        can[3] = ((canId ushr 24) and 0xFF).toByte()
        data.copyInto(can, 4)
        can[12] = LEN_NORMAL
        can[13] = CHANNEL_PHONE
        can[14] = FORMAT_STANDARD
        can[15] = if (remote) TYPE_REMOTE else TYPE_DATA
        return wrap(can)
    }

    /** Wrap an unescaped CAN frame in `AA AA … <ck> 55 55` with byte stuffing. */
    fun wrap(frame: ByteArray): ByteArray {
        val checksum = checksum(frame)
        val body = ByteArrayOutputStream(frame.size + 4)
        body.write(0xAA); body.write(0xAA)
        for (b in frame) writeEscaped(body, b)
        writeEscaped(body, checksum)
        body.write(0x55); body.write(0x55)
        return body.toByteArray()
    }

    /**
     * Unwrap a complete `AA AA … 55 55` frame: strip framing, reverse byte
     * stuffing, validate checksum. Returns the unescaped CAN bytes (without
     * the trailing checksum) or null when the frame is malformed.
     */
    fun unwrap(framed: ByteArray): ByteArray? {
        if (framed.size < 6) return null
        if (framed[0] != HEADER || framed[1] != HEADER) return null
        val n = framed.size
        if (framed[n - 2] != TRAILER || framed[n - 1] != TRAILER) return null
        val unescaped = ByteArrayOutputStream(n)
        var i = 2
        while (i < n - 2) {
            val b = framed[i]
            if (b == ESCAPE) {
                if (i + 1 >= n - 2) return null
                unescaped.write(framed[i + 1].toInt() and 0xFF)
                i += 2
            } else {
                unescaped.write(b.toInt() and 0xFF)
                i++
            }
        }
        val raw = unescaped.toByteArray()
        if (raw.size < 17) return null // 16-byte CAN prefix + checksum
        val payload = raw.copyOfRange(0, raw.size - 1)
        val expected = raw[raw.size - 1]
        if (checksum(payload) != expected) return null
        return payload
    }

    /** Sum-mod-256 over the given bytes. */
    fun checksum(bytes: ByteArray): Byte {
        var sum = 0
        for (b in bytes) sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }

    private fun writeEscaped(out: ByteArrayOutputStream, b: Byte) {
        when (b) {
            HEADER, TRAILER, ESCAPE -> {
                out.write(0xA5)
                out.write(b.toInt() and 0xFF)
            }
            else -> out.write(b.toInt() and 0xFF)
        }
    }

    /**
     * 32-bit CAN IDs the V1 protocol exchanges in normal operation. See spec
     * section 3.4. IDs are written LE at offset 0 of every frame.
     */
    object CanId {
        const val FAST_INFO     = 0x0F550113
        const val SLOW_INFO     = 0x0F550114
        const val RIDE_MODE     = 0x0F550115
        const val REMOTE_CTRL   = 0x0F550116
        const val CALIBRATE     = 0x0F550119
        const val HEADLIGHT     = 0x0F55010D
        const val HANDLE_BTN    = 0x0F55012E
        const val PLAY_SOUND    = 0x0F550609
        const val VOLUME        = 0x0F55060A
        const val PIN           = 0x0F550307
        const val ALERT         = 0x0F780101
    }
}
