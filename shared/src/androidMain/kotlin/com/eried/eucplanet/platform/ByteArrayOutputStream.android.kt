package com.eried.eucplanet.platform

actual class ByteArrayOutputStream actual constructor(initialSize: Int) {
    actual constructor() : this(32)

    private val out = java.io.ByteArrayOutputStream(initialSize)

    actual fun write(b: Int) = out.write(b)
    actual fun write(b: ByteArray) = out.write(b, 0, b.size)
    actual fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    actual fun toByteArray(): ByteArray = out.toByteArray()
    actual fun size(): Int = out.size()
    actual fun reset() = out.reset()
}
