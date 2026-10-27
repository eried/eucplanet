package com.eried.eucplanet.platform

import kotlin.time.TimeSource
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual object System {
    private val origin = TimeSource.Monotonic.markNow()

    actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
    actual fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds
    actual fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) {
        if (srcPos < 0 || destPos < 0 || length < 0 ||
            srcPos + length > src.size || destPos + length > dest.size
        ) throw IndexOutOfBoundsException("arraycopy: last source index ${srcPos + length} out of bounds")
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }
}
