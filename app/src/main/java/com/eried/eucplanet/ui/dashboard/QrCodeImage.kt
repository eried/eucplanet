package com.eried.eucplanet.ui.dashboard

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The widest QR bitmap we will ever encode, in pixels.
 *
 * A QR is a grid of black and white modules, so past a certain resolution the
 * extra pixels carry nothing: 1024 px is already about 25 px per module for a
 * short URL, sharp on any phone or tablet, and the [Image] scales it up to
 * whatever the layout asked for. Without the cap the size follows the view
 * width, and a landscape phone or a tablet would allocate an ARGB_8888 bitmap
 * plus an IntArray of the same size, roughly 19 MB each at 2186 px, next to the
 * map and the WebView.
 */
const val QR_MAX_PX = 1024

/**
 * Renders [content] as a QR code that fills the space [modifier] gives it.
 *
 * [sizePx] is the encoded bitmap resolution, not the layout size: pass
 * `min(widthPx, QR_MAX_PX)`. The bitmap is computed once per (content, sizePx)
 * pair and remembered, so a recomposing caller (a ticking clock, a peer list
 * update) never re-encodes and never allocates a second one. Always on white
 * with a small white border so the code stays scannable regardless of the
 * surrounding theme (dark / black mode).
 */
@Composable
fun QrCodeImage(content: String, sizePx: Int, modifier: Modifier = Modifier) {
    val encodePx = sizePx.coerceIn(64, QR_MAX_PX)
    val bitmap = remember(content, encodePx) { encodeQr(content, encodePx) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            // Nearest neighbour: the modules are hard squares, so a smoothed
            // upscale only softens the edges a scanner is looking for.
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Fixed-size variant for callers that lay the code out in dp. */
@Composable
fun QrCodeImage(content: String, sizeDp: Int) {
    val density = LocalDensity.current
    val sizePx = remember(sizeDp, density) { with(density) { sizeDp.dp.roundToPx() } }
    QrCodeImage(content = content, sizePx = sizePx, modifier = Modifier.size(sizeDp.dp))
}

private fun encodeQr(content: String, size: Int): Bitmap {
    val side = size.coerceAtLeast(64)
    // Q-level (~25% recoverable) is a good balance for short URLs scanned
    // at arm's length on a phone screen.
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
        EncodeHintType.MARGIN to 0
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, side, side, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val rowOffset = y * w
        for (x in 0 until w) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    return bmp
}
