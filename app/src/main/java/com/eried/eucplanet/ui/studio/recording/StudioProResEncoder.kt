package com.eried.eucplanet.ui.studio.recording

/*
 * DISABLED app-wide. This is the ffmpeg-based ProRes 4444 / QuickTime RLE `.mov`
 * (alpha) export path for Overlay Studio replay clips. It works, but the alpha
 * `.mov` files it produces are handled inconsistently across NLEs, so the entire
 * MOV path is commented out: the ffmpeg-kit dependency (app/build.gradle.kts),
 * the MOV video format (ReplayVideoFormat.MOV), the codec chooser, and the
 * render branch in OverlayStudioScreen. To bring it back: uncomment this file,
 * re-add the ffmpeg-kit dependency, re-add ReplayVideoFormat.MOV + the codec
 * chips, and the MOV render branch.
 *
 * Kept verbatim below (inside this block comment) so it can be revived as-is.
 *
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object StudioProResEncoder {
    private const val TAG = "StudioProRes"

    fun frameName(i: Int): String = "frame_%05d.png".format(i)

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
            "-y -framerate $fps -i \"$pattern\" " +
                "-c:v qtrle -pix_fmt argb -r $fps \"${mov.absolutePath}\""
        } else {
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
 */
