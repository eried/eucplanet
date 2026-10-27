package com.eried.eucplanet.platform

actual object System {
    actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()
    actual fun nanoTime(): Long = java.lang.System.nanoTime()
    actual fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) =
        java.lang.System.arraycopy(src, srcPos, dest, destPos, length)
}
