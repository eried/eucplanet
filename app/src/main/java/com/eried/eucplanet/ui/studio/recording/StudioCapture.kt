package com.eried.eucplanet.ui.studio.recording

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves Overlay Studio photo snapshots to the gallery.
 *
 * The studio captures its own composed frame (camera viewports + overlays)
 * into a bitmap via the recording GraphicsLayer; this just writes that bitmap
 * out as a JPEG under `Pictures/EUC Planet`.
 */
object StudioCapture {

    private const val TAG = "StudioCapture"

    /** Save [bitmap] as a JPEG in the gallery. Returns its URI, or null. */
    suspend fun saveJpeg(context: Context, bitmap: Bitmap): Uri? =
        withContext(Dispatchers.IO) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EUC_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EUC Planet")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext null
            try {
                val wrote = resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: false
                if (!wrote) {
                    runCatching { resolver.delete(uri, null, null) }
                    return@withContext null
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                Log.e(TAG, "Saving snapshot failed", e)
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
}
