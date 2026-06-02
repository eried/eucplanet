package com.eried.eucplanet.ui.theme

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The token(s) whose current value is closest to a sampled screen pixel — how
 * the target tool answers "which class of color is that". Returns the top [n] by
 * RGB distance so we can ask the user when several are close.
 */
fun nearestTokens(sampled: Color, base: AppThemeColors, n: Int = 3): List<ThemeTokenSpec> =
    ThemeTokens.specs.sortedBy { spec ->
        val c = spec.get(base)
        val dr = c.red - sampled.red
        val dg = c.green - sampled.green
        val db = c.blue - sampled.blue
        dr * dr + dg * dg + db * db
    }.take(n)

/** factor<1 darkens (multiply); factor>=1 lightens (lerp toward white). For the blink. */
fun Color.adjustLightness(factor: Float): Color = if (factor >= 1f) {
    val t = (factor - 1f).coerceIn(0f, 1f)
    Color(red + (1f - red) * t, green + (1f - green) * t, blue + (1f - blue) * t, alpha)
} else {
    Color(red * factor, green * factor, blue * factor, alpha)
}

/**
 * Full-screen target overlay with two phases:
 *  - **Aiming** (no candidates yet): dim + spotlight + draggable crosshair, with
 *    Identify / Cancel clamped on-screen beneath the ring.
 *  - **Choosing** (after Identify): dragging is disabled and the dim is lifted so
 *    the blink preview is visible. A centered list lets you tap a candidate to
 *    blink it; Select confirms (and scrolls to it in the widget), Cancel exits.
 */
@Composable
fun ThemeTargetOverlay(
    base: AppThemeColors,
    ring: Offset,
    onRing: (Offset) -> Unit,
    onPreviewToken: (ThemeTokenSpec) -> Unit,
    onPicked: (ThemeTokenSpec) -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    var boxWin by remember { mutableStateOf(Offset.Zero) }
    var candidates by remember { mutableStateOf<List<ThemeTokenSpec>?>(null) }
    var chosen by remember { mutableStateOf<ThemeTokenSpec?>(null) }
    val ringState = rememberUpdatedState(ring)
    var dragRing by remember { mutableStateOf(ring) }

    val spotlightDp = 60.dp
    val ringDp = 26.dp
    val aiming = candidates == null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { boxWin = it.positionInWindow() }
            // Drag only while aiming; once Identify is pressed, dragging is off.
            .then(
                if (aiming) Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragRing = ringState.value },
                        onDrag = { change, drag -> change.consume(); dragRing += drag; onRing(dragRing) }
                    )
                } else Modifier.pointerInput(Unit) {
                    // Choosing: swallow all gestures so dragging is fully disabled
                    // and the app behind the chooser isn't touchable.
                    awaitPointerEventScope {
                        while (true) { awaitPointerEvent().changes.forEach { it.consume() } }
                    }
                }
            )
    ) {
        if (aiming) {
            // Dim everything, clear a spotlight hole around the ring, draw crosshair.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            ) {
                val spotR = spotlightDp.toPx()
                val ringR = ringDp.toPx()
                drawRect(Color.Black.copy(alpha = 0.74f))
                drawCircle(Color.Transparent, radius = spotR, center = ring, blendMode = BlendMode.Clear)
                drawCircle(Color.Black, radius = ringR + 2f, center = ring, style = Stroke(width = 5f))
                drawCircle(Color.White, radius = ringR, center = ring, style = Stroke(width = 3f))
                drawCircle(Color.White, radius = 4f, center = ring, style = Stroke(width = 1.5f))
            }

            // Identify / Cancel beneath the crosshair, clamped to stay on-screen.
            Row(
                modifier = Modifier.offset {
                    val rowW = 220.dp.toPx()
                    val rowH = 56.dp.toPx()
                    val x = (boxWin.x + ring.x - rowW / 2f)
                        .coerceIn(0f, (view.width - rowW).coerceAtLeast(0f))
                    val y = (boxWin.y + ring.y + spotlightDp.toPx() + 14.dp.toPx())
                        .coerceIn(0f, (view.height - rowH).coerceAtLeast(0f))
                    IntOffset(x.roundToInt(), y.roundToInt())
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = {
                    val window = (view.context as? Activity)?.window
                    val sx = (boxWin.x + ring.x).roundToInt()
                    val sy = (boxWin.y + ring.y).roundToInt()
                    if (window != null && view.width > 0 && view.height > 0 &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ) {
                        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        runCatching {
                            PixelCopy.request(window, bmp, { result ->
                                if (result == PixelCopy.SUCCESS &&
                                    sx in 0 until bmp.width && sy in 0 until bmp.height
                                ) {
                                    chosen = null
                                    candidates = nearestTokens(Color(bmp.getPixel(sx, sy)), base)
                                }
                                bmp.recycle()
                            }, Handler(Looper.getMainLooper()))
                        }.onFailure { bmp.recycle() }
                    }
                }) { Text("Identify") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        } else {
            // Choosing: no dim (so the blink is visible), centered, drag disabled.
            // Tapping a candidate blinks it; Select confirms, Cancel exits.
            val list = candidates ?: emptyList()
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 320.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    list.forEach { spec ->
                        val selected = chosen?.key == spec.key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else Color.Transparent
                                )
                                .clickable { chosen = spec; onPreviewToken(spec) }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(spec.get(base), RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${spec.group} • ${spec.label}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = { candidates = null; chosen = null; onCancel() }) {
                            Text("Cancel")
                        }
                        TextButton(
                            enabled = chosen != null,
                            onClick = { chosen?.let { candidates = null; onPicked(it) } }
                        ) { Text("Select") }
                    }
                }
            }
        }
    }
}
