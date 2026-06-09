package com.eried.eucplanet.util

/**
 * Minimal growable byte buffer — the commonMain stand-in for the handful of
 * `java.io.ByteArrayOutputStream` uses in shared code (append a byte, collect to
 * a ByteArray). Not thread-safe; intended for short-lived BLE frame assembly.
 */
class ByteArrayBuilder(capacity: Int = 32) {
    private var buf = ByteArray(if (capacity > 0) capacity else 32)
    private var count = 0

    /** Append the low 8 bits of [b]; mirrors ByteArrayOutputStream.write(Int). */
    fun write(b: Int) {
        if (count == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[count++] = b.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(count)
}
