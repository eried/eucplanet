package com.eried.eucplanet.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

actual class ByteArrayOutputStream actual constructor(initialSize: Int) {
    actual constructor() : this(32)

    private val lock = SynchronizedObject()
    private val buf = ByteBuffer(initialSize)

    actual fun write(b: Int) = synchronized(lock) { buf.write(b) }
    actual fun write(b: ByteArray) = synchronized(lock) { buf.write(b, 0, b.size) }
    actual fun write(b: ByteArray, off: Int, len: Int) = synchronized(lock) { buf.write(b, off, len) }
    actual fun toByteArray(): ByteArray = synchronized(lock) { buf.toByteArray() }
    actual fun size(): Int = synchronized(lock) { buf.size() }
    actual fun reset() = synchronized(lock) { buf.reset() }
}
