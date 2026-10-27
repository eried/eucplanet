package com.eried.eucplanet.platform

/**
 * The `java.lang.System` calls shared code makes, under the same name, so a
 * moved file keeps `System.currentTimeMillis()` and only adds an import.
 * Android calls `java.lang.System` itself.
 */
expect object System {
    fun currentTimeMillis(): Long
    fun nanoTime(): Long
    fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int)
}
