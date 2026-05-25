package com.eried.eucplanet.ui.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Helpers for the user-image overlay element. Picked images are downscaled and
 * embedded base64 inside the preset `.json` so a preset is fully portable.
 * An optional colour key turns a chosen colour transparent, handy for
 * clipart on a flat background.
 */
object StudioImages {

    private const val TAG = "StudioImages"

    /** Longest edge an embedded image is scaled to, keeping the preset small. */
    private const val MAX_EDGE = 1024

    /**
     * Read [uri], downscale it and return PNG bytes as a base64 string ready to
     * store in [com.eried.eucplanet.data.model.OverlayElement.imageData].
     * PNG keeps any alpha channel the source image already has.
     */
    suspend fun encodeForPreset(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return@withContext null
                val scaled = downscale(source)
                if (scaled != source) source.recycle()
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                scaled.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.getOrElse {
                Log.e(TAG, "Could not encode picked image", it)
                null
            }
        }

    /** Decode a base64 image string back into a mutable ARGB bitmap. */
    fun decode(data: String): Bitmap? = runCatching {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@runCatching null
        val mutable = raw.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable != null && mutable != raw) raw.recycle()
        mutable
    }.getOrNull()

    /**
     * Return a copy of [src] with every pixel within [tolerance] of [key]
     * turned fully transparent. [tolerance] is 0..1 over the RGB cube diagonal.
     */
    fun applyChromaKey(src: Bitmap, key: Color, tolerance: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        val kr = (key.red * 255f).toInt()
        val kg = (key.green * 255f).toInt()
        val kb = (key.blue * 255f).toInt()
        // 441.67 is the RGB cube diagonal length (sqrt(3) * 255).
        val limit = (tolerance.coerceIn(0f, 1f) * 441.673f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val dr = (r - kr).toFloat()
            val dg = (g - kg).toFloat()
            val db = (b - kb).toFloat()
            val dist = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
            if (dist <= limit) {
                pixels[i] = p and 0x00FFFFFF // zero the alpha byte
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_EDGE) return src
        val scale = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /** True when two colours are visually close, used to dedupe key picks. */
    fun colorsClose(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < 0.02f &&
            abs(a.green - b.green) < 0.02f &&
            abs(a.blue - b.blue) < 0.02f
}
