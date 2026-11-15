package com.eried.eucplanet.ui.studio.recording

/**
 * Where the GIF and APNG encoders write their bytes: the part of
 * `java.io.OutputStream` they call. Android wraps the gallery file's stream
 * ([asStudioByteSink] in the app); iOS will wrap its file handle.
 */
interface StudioByteSink {
    fun write(b: Int)
    fun write(b: ByteArray) = write(b, 0, b.size)
    fun write(b: ByteArray, off: Int, len: Int)
    fun flush()
}
