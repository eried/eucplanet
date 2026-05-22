package com.eried.eucplanet.ui.studio.recording

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves Overlay Studio output to the gallery under `Pictures/EUC Planet`.
 *
 * Live snapshots go out as JPEG; replay snapshots as PNG so the transparent
 * background survives. [newPendingImage] backs the streaming APNG export.
 */
object StudioCapture {

    private const val TAG = "StudioCapture"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Save [bitmap] as a JPEG. Returns its URI, or null. */
    suspend fun saveJpeg(context: Context, bitmap: Bitmap): Uri? =
        saveBitmap(
            context, bitmap, "EUC_${stamp()}.jpg", "image/jpeg",
            Bitmap.CompressFormat.JPEG, 95
        )

    /** Save [bitmap] as a PNG — keeps transparency for replay snapshots. */
    suspend fun savePng(context: Context, bitmap: Bitmap): Uri? =
        saveBitmap(
            context, bitmap, "EUC_${stamp()}.png", "image/png",
            Bitmap.CompressFormat.PNG, 100
        )

    /** Save [bitmap] as a lossless WebP — keeps transparency, smaller than PNG. */
    @Suppress("DEPRECATION")
    suspend fun saveWebp(context: Context, bitmap: Bitmap): Uri? {
        // WEBP_LOSSLESS is API 30+; on API 29 fall back to the deprecated WEBP.
        val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.WEBP
        }
        return saveBitmap(
            context, bitmap, "EUC_${stamp()}.webp", "image/webp", format, 100
        )
    }

    /** A unique base file name (no extension) for an export, e.g. an APNG clip. */
    fun timestampedName(): String = "EUC_${stamp()}"

    private suspend fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        name: String,
        mime: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): Uri? = withContext(Dispatchers.IO) {
        val pending = newPendingImage(context, name, mime) ?: return@withContext null
        val ok = try {
            pending.openStream()?.use { bitmap.compress(format, quality, it) } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Saving image failed", e)
            false
        }
        pending.finalize(ok)
        if (ok) pending.uri else null
    }

    /** A gallery image the caller streams bytes into, then finalizes. */
    class PendingImage(
        val uri: Uri,
        private val resolver: ContentResolver
    ) {
        fun openStream(): OutputStream? = resolver.openOutputStream(uri)

        fun finalize(ok: Boolean) {
            if (ok) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null
                )
            } else {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    /** Create a pending gallery image the caller writes [mime] bytes into. */
    fun newPendingImage(context: Context, displayName: String, mime: String): PendingImage? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EUC Planet")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return PendingImage(uri, resolver)
    }
}
