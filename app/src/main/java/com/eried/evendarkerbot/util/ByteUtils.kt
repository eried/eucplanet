package com.eried.evendarkerbot.util

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
