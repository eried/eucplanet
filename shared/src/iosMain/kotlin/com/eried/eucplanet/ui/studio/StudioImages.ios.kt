package com.eried.eucplanet.ui.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

// Skia decodes the embedded images; the colour key is the same maths as
// Android's, over the pixels Compose reads back.
@OptIn(ExperimentalEncodingApi::class)
actual object StudioImages {

    actual fun decode(data: String): ImageBitmap? = runCatching {
        Image.makeFromEncoded(Base64.decode(data)).toComposeImageBitmap()
    }.getOrNull()

    actual fun applyChromaKey(src: ImageBitmap, key: Color, tolerance: Float): ImageBitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.readPixels(pixels)
        val kr = (key.red * 255f).toInt()
        val kg = (key.green * 255f).toInt()
        val kb = (key.blue * 255f).toInt()
        // 441.67 is the RGB cube diagonal length (sqrt(3) * 255).
        val limit = (tolerance.coerceIn(0f, 1f) * 441.673f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val dr = (((p shr 16) and 0xFF) - kr).toFloat()
            val dg = (((p shr 8) and 0xFF) - kg).toFloat()
            val db = ((p and 0xFF) - kb).toFloat()
            if (kotlin.math.sqrt(dr * dr + dg * dg + db * db) <= limit) {
                pixels[i] = p and 0x00FFFFFF // zero the alpha byte
            }
        }
        // readPixels hands back ARGB ints; Skia's N32 on iOS is BGRA in memory,
        // which is the same little-endian int.
        val bytes = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            bytes[i * 4] = (p and 0xFF).toByte()
            bytes[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((p shr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
        }
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.UNPREMUL))
        bitmap.installPixels(bytes)
        return bitmap.asComposeImageBitmapOrSrc(src)
    }

    actual fun decodeDataUrl(url: String): ImageBitmap? {
        val comma = url.indexOf(',')
        if (comma <= 0) return null
        return decode(url.substring(comma + 1))
    }

    private fun Bitmap.asComposeImageBitmapOrSrc(src: ImageBitmap): ImageBitmap =
        runCatching { Image.makeFromBitmap(this).toComposeImageBitmap() }.getOrDefault(src)
}
