package com.eried.eucplanet.ui.studio.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Encodes a rendered PNG frame sequence to a ProRes 4444 `.mov` (alpha-preserving)
 * via ffmpeg-kit, then publishes it to the gallery.
 *
 * Android's MediaCodec can't encode ProRes and MediaMuxer can't write the `.mov`
 * container, so ffmpeg is the only path for an alpha video the rider can drop
 * straight onto an editing timeline. The frames are written as PNG (lossless,
 * with alpha) by the caller into [dir]; this turns them into the clip.
 */
object StudioProResEncoder {
    private const val TAG = "StudioProRes"

    /** PNG frame name for index [i] (matches the ffmpeg `frame_%05d.png` glob). */
    fun frameName(i: Int): String = "frame_%05d.png".format(i)

    /**
     * Encode `dir/frame_%05d.png` (0 until [frameCount]) at [fps] to ProRes 4444
     * and publish the result. Returns the gallery Uri, or null on failure.
     */
    fun encodeAndPublish(
        context: Context,
        dir: File,
        frameCount: Int,
        fps: Double,
        useQtrle: Boolean = false
    ): Uri? {
        if (frameCount < 1) return null
        val mov = File(dir, "out.mov")
        val pattern = File(dir, "frame_%05d.png").absolutePath
        val cmd = if (useQtrle) {
            // QuickTime Animation (qtrle): lossless RLE, packed ARGB straight alpha.
            // Universally recognised by NLEs (the reliable fallback when an editor
            // won't read ffmpeg's ProRes 4444 alpha). Bigger files.
            "-y -framerate $fps -i \"$pattern\" " +
                "-c:v qtrle -pix_fmt argb -r $fps \"${mov.absolutePath}\""
        } else {
            // ProRes 4444 with alpha NLEs recognise: yuva444p10le carries alpha,
            // premultiply (NLEs expect premultiplied for ProRes 4444), full-depth
            // -alpha_bits 16, and the Apple -vendor apl0 tag so the alpha track is
            // enabled.
            "-y -framerate $fps -i \"$pattern\" " +
                "-vf premultiply=inplace=1 " +
                "-c:v prores_ks -profile:v 4444 -pix_fmt yuva444p10le " +
                "-alpha_bits 16 -vendor apl0 -qscale:v 9 " +
                "-r $fps \"${mov.absolutePath}\""
        }
        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode) || !mov.exists() || mov.length() == 0L) {
            Log.e(TAG, ".mov encode failed rc=${session.returnCode}: ${session.failStackTrace ?: session.output}")
            return null
        }
        Log.i(TAG, "${if (useQtrle) "qtrle" else "ProRes"} .mov encoded (${mov.length()} bytes, $frameCount frames @ $fps fps)")
        return publish(context, mov)
    }

    private fun publish(context: Context, mov: File): Uri? {
        val name = "EUC_" + System.currentTimeMillis() + ".mov"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/quicktime")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EUC Planet")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> mov.inputStream().use { it.copyTo(out) } }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null, null
            )
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Publishing .mov failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
