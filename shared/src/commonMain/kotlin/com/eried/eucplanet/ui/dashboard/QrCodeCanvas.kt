package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.util.QrEncoder

/**
 * A QR code drawn from the shared [QrEncoder], for a phone without ZXing (iOS).
 * Laid out as Android's `QrCodeImage`: black modules on white with a small white
 * border, at the whole-module scale ZXing renders its bitmap at, centred, then
 * scaled to the box. Fixed colors on purpose: a scanner needs black on white
 * whatever the theme.
 */
@Composable
fun QrCodeCanvas(content: String, sizeDp: Int, ecc: QrEncoder.Ecc = QrEncoder.Ecc.Q) {
    val density = LocalDensity.current
    val encodePx = remember(sizeDp, density) { with(density) { sizeDp.dp.roundToPx() } }.coerceIn(64, 1024)
    val matrix = remember(content, ecc) { runCatching { QrEncoder.encode(content, ecc) }.getOrNull() }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (matrix == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val n = matrix.size
            // ZXing's renderResult: whole pixels per module, the rest split as padding.
            val multiple = (maxOf(encodePx, n) / n).coerceAtLeast(1)
            val pad = (maxOf(encodePx, n) - n * multiple) / 2f
            val scale = size.minDimension / maxOf(encodePx, n)
            // Each edge on a whole pixel, shared with the neighbour, so the
            // modules meet with no anti-aliased seam between them.
            fun edge(i: Int) = kotlin.math.round((pad + i * multiple) * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (!matrix[x, y]) continue
                val left = edge(x)
                val top = edge(y)
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(left, top),
                    size = Size(edge(x + 1) - left, edge(y + 1) - top),
                )
            }
        }
    }
}
