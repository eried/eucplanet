package com.eried.eucplanet.util

/**
 * Minimal growable byte buffer — the commonMain stand-in for the handful of
 * `java.io.ByteArrayOutputStream` uses in shared code (append a byte / array,
 * collect to a ByteArray, reset). Not thread-safe; intended for short-lived BLE
 * frame assembly.
 */
class ByteArrayBuilder(capacity: Int = 32) {
    private var buf = ByteArray(if (capacity > 0) capacity else 32)
    private var count = 0

    /** Number of bytes currently buffered; mirrors ByteArrayOutputStream.size(). */
    val size: Int get() = count

    private fun ensure(extra: Int) {
        if (count + extra <= buf.size) return
        var n = buf.size
        while (count + extra > n) n *= 2
        buf = buf.copyOf(n)
    }

    /** Append the low 8 bits of [b]; mirrors ByteArrayOutputStream.write(Int). */
    fun write(b: Int) {
        ensure(1)
        buf[count++] = b.toByte()
    }

    /** Append all of [bytes]; mirrors ByteArrayOutputStream.write(ByteArray). */
    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buf, count)
        count += bytes.size
    }

    /** Append [length] bytes of [bytes] starting at [offset]; mirrors
     *  ByteArrayOutputStream.write(byte[], int, int). */
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        ensure(length)
        bytes.copyInto(buf, count, offset, offset + length)
        count += length
    }

    /** Discard all buffered bytes; mirrors ByteArrayOutputStream.reset(). */
    fun reset() { count = 0 }

    fun toByteArray(): ByteArray = buf.copyOf(count)
}
