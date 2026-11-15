package com.eried.eucplanet.ui.studio.recording

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * The Android half of the shared GIF / APNG encoders: a captured frame's ARGB
 * pixels at the clip's size, and the gallery stream as their byte sink.
 */
object StudioFramePixels {

    /**
     * [bitmap] scaled to [width] x [height] and read into an ARGB array, or
     * null when a hardware bitmap could not be copied.
     */
    fun of(bitmap: Bitmap, width: Int, height: Int): IntArray? {
        // GraphicsLayer.toImageBitmap() hands back a HARDWARE bitmap; getPixels()
        // only works on a software bitmap, so copy when needed.
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
        val src = if (software.width == width && software.height == height) {
            software
        } else {
            Bitmap.createScaledBitmap(software, width, height, true)
        }
        val argb = IntArray(width * height)
        src.getPixels(argb, 0, width, 0, 0, width, height)
        if (src !== software) src.recycle()
        if (software !== bitmap) software.recycle()
        return argb
    }
}

/** This stream as the encoders' [StudioByteSink]. */
fun OutputStream.asStudioByteSink(): StudioByteSink = object : StudioByteSink {
    override fun write(b: Int) = this@asStudioByteSink.write(b)
    override fun write(b: ByteArray) = this@asStudioByteSink.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = this@asStudioByteSink.write(b, off, len)
    override fun flush() = this@asStudioByteSink.flush()
    override fun close() = this@asStudioByteSink.close()
}
