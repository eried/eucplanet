package com.eried.eucplanet.ui.dashboard

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code at the requested square size. Uses ZXing's
 * pure-Java encoder; the bitmap is computed once per (content, size) pair
 * and remembered for the lifetime of the composition. Always on white with
 * a small white border so the code stays scannable regardless of the
 * surrounding theme (dark / black mode).
 */
@Composable
fun QrCodeImage(content: String, sizeDp: Int) {
    val pxSize = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.toPx().toInt() }
    val bitmap = remember(content, pxSize) { encodeQr(content, pxSize) }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size((sizeDp - 16).dp)
        )
    }
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
