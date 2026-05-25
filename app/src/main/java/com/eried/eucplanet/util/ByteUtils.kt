package com.eried.eucplanet.util

object ByteUtils {

    fun getUint16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun getInt16LE(data: ByteArray, offset: Int): Int {
        val value = getUint16LE(data, offset)
        return if (value >= 0x8000) value - 0x10000 else value
    }

    fun getUint32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** Signed 32-bit LE read used by InMotion V1 fast-info pitch / speed / current. */
    fun getInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // Big-endian readers used by Begode (55 AA frames) and Veteran
    // (DC 5A 5C 20 frames). InMotion and KingSong stay little-endian.
    fun getUint16BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
    }

    fun getInt16BE(data: ByteArray, offset: Int): Int {
        val value = getUint16BE(data, offset)
        return if (value >= 0x8000) value - 0x10000 else value
    }

    fun getUint32BE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    /**
     * Veteran's distance fields are two big-endian u16s with the high word
     * stored second, a serializer quirk specific to the LeaperKim firmware.
     * Given bytes b0 b1 b2 b3 at the offset, the value is
     *   (b2 << 24) | (b3 << 16) | (b0 << 8) | b1.
     * Spec: docs/protocols/veteran.md section 4 ("Word-swapped 32-bit fields").
     */
    fun getWordSwappedUint32(data: ByteArray, offset: Int): Long {
        return ((data[offset + 2].toLong() and 0xFF) shl 24) or
                ((data[offset + 3].toLong() and 0xFF) shl 16) or
                ((data[offset].toLong() and 0xFF) shl 8) or
                (data[offset + 1].toLong() and 0xFF)
    }

    fun putUint16LE(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02x".format(it) }

    fun parseTemperature(raw: Byte): Float {
        return (raw.toInt() and 0xFF) - 176f
    }
}
