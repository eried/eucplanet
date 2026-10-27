package com.eried.eucplanet.platform

/**
 * The part of `java.io.ByteArrayOutputStream` shared code uses, same names, so a
 * moved file only swaps the import. Android wraps the JVM class itself, which
 * keeps its synchronized methods.
 */
expect class ByteArrayOutputStream(initialSize: Int) {
    constructor()
    fun write(b: Int)
    fun write(b: ByteArray)
    fun write(b: ByteArray, off: Int, len: Int)
    fun toByteArray(): ByteArray
    fun size(): Int
    fun reset()
}

/** Growable byte buffer with `ByteArrayOutputStream` semantics, for platforms without one. */
class ByteBuffer(initialSize: Int = 32) {
    init { require(initialSize >= 0) { "Negative initial size: $initialSize" } }

    private var buf = ByteArray(maxOf(initialSize, 1))
    private var count = 0

    private fun ensure(min: Int) {
        if (min > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, min))
    }

    fun write(b: Int) {
        ensure(count + 1)
        buf[count++] = b.toByte()
    }

    fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        ensure(count + len)
        b.copyInto(buf, count, off, off + len)
        count += len
    }

    fun toByteArray(): ByteArray = buf.copyOf(count)

    fun size(): Int = count

    fun reset() { count = 0 }
}
