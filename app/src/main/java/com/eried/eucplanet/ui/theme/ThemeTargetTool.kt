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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

/**
 * Tokens whose value matches a sampled screen pixel — how the target tool answers
 * "which class of color is that". Lists EVERY token that exactly produces the
 * sampled color (so e.g. all four near-identical surfaces show up), falling back
 * to the 3 nearest when nothing matches exactly.
 */
fun nearestTokens(sampled: Color, base: AppThemeColors): List<ThemeTokenSpec> {
    val scored = ThemeTokens.specs.map { spec ->
        val c = spec.get(base)
        val dr = c.red - sampled.red
        val dg = c.green - sampled.green
        val db = c.blue - sampled.blue
        spec to (dr * dr + dg * dg + db * db)
    }.sortedBy { it.second }
    if (scored.isEmpty()) return emptyList()
    val min = scored.first().second
    val eps = 0.0008f
    val tied = scored.filter { it.second <= min + eps }.map { it.first }
    return if (tied.size > 3) tied else scored.take(3).map { it.first }
}

/** factor<1 darkens (multiply); factor>=1 lightens (lerp toward white). For the blink. */
fun Color.adjustLightness(factor: Float): Color = if (factor >= 1f) {
    val t = (factor - 1f).coerceIn(0f, 1f)
    Color(red + (1f - red) * t, green + (1f - green) * t, blue + (1f - blue) * t, alpha)
} else {
    Color(red * factor, green * factor, blue * factor, alpha)
}

/**
 * Full-screen target overlay. The dimmed spotlight + crosshair stay on screen the
 * whole time; the crosshair is draggable only while aiming. After Identify the
 * circle remains (frozen) and a centered chooser lets you tap a candidate to blink
 * it, then Select (which scrolls to it in the widget) or Cancel.
 */
@Composable
fun ThemeTargetOverlay(
    base: AppThemeColors,
    ring: Offset,
    /** True while the finger is still held on the eyedropper button (live aim). */
    fingerDown: Boolean,
    /** True when the finger was released OFF the button — sample immediately,
     *  skipping the Identify/Cancel step. */
    autoIdentify: Boolean,
    onRing: (Offset) -> Unit,
    onPreviewToken: (ThemeTokenSpec) -> Unit,
    onPicked: (ThemeTokenSpec) -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    var boxWin by remember { mutableStateOf(Offset.Zero) }
    var candidates by remember { mutableStateOf<List<ThemeTokenSpec>?>(null) }
    var chosen by remember { mutableStateOf<ThemeTokenSpec?>(null) }
    // If a drag-release auto-Identify resolves nothing, fall back to the aim
    // buttons rather than stranding the user behind a button-less overlay.
    var autoFallback by remember { mutableStateOf(false) }
    val ringState = rememberUpdatedState(ring)
    var dragRing by remember { mutableStateOf(ring) }
    // Center reticle pulses white↔black every 2s so it reads on any sampled color.
    var reticleFlip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2000); reticleFlip = !reticleFlip } }
    // Measured Identify/Cancel row size so it centers on the circle regardless of
    // label widths / locale (a hard-coded width left it off-center).
    var rowWpx by remember { mutableStateOf(0) }
    var rowHpx by remember { mutableStateOf(0) }

    val spotlightDp = 60.dp
    val ringDp = 26.dp

    // Sample the screen pixel under the crosshair → matching tokens. The spotlight
    // hole keeps the dim from tinting the sampled pixel.
    fun sample() {
        val window = (view.context as? Activity)?.window
        val sx = (boxWin.x + ringState.value.x).roundToInt()
        val sy = (boxWin.y + ringState.value.y).roundToInt()
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
    }

    // Drag-off-button release: sample the moment the finger lifts. A short delay
    // lets the spotlight settle on the release point; the fallback re-shows the
    // buttons if PixelCopy returned nothing.
    LaunchedEffect(autoIdentify) {
        if (autoIdentify) {
            autoFallback = false
            delay(40)
            sample()
            delay(300)
            if (candidates == null) autoFallback = true
        }
    }

    val choosing = candidates != null
    // Aim buttons appear only after a tap-release inside the button (or the auto
    // path fell through) — never while the finger is down or mid-sample.
    val showAimButtons = !choosing && !fingerDown && (!autoIdentify || autoFallback)
    val gestureMode = when {
        fingerDown -> "live"      // eyedropper button owns the gesture; stay passive
        showAimButtons -> "aim"   // drag the crosshair to re-aim
        else -> "modal"           // choosing / sampling: swallow touches
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { boxWin = it.positionInWindow() }
            .pointerInput(gestureMode) {
                when (gestureMode) {
                    "aim" -> detectDragGestures(
                        onDragStart = { dragRing = ringState.value },
                        onDrag = { change, drag -> change.consume(); dragRing += drag; onRing(dragRing) }
                    )
                    "modal" -> awaitPointerEventScope {
                        while (true) { awaitPointerEvent().changes.forEach { it.consume() } }
                    }
                    // "live": do nothing so the eyedropper button keeps the pointer.
                }
            }
    ) {
        // Dim + spotlight + crosshair — always visible (kept while choosing too).
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
            // Center reticle: two concentric dots that swap white↔black every 2s,
            // so one is always contrasting against the sampled color behind it.
            drawCircle(if (reticleFlip) Color.White else Color.Black, radius = 4f, center = ring, style = Stroke(width = 1.5f))
            drawCircle(if (reticleFlip) Color.Black else Color.White, radius = 6f, center = ring, style = Stroke(width = 1f))
        }

        if (showAimButtons) {
            // Identify / Cancel sit below the circle, but flip ABOVE it when there
            // isn't room below — never overlapping the circle, always on-screen.
            Row(
                modifier = Modifier
                    .offset {
                        val rowW = if (rowWpx > 0) rowWpx.toFloat() else 220.dp.toPx()
                        val rowH = if (rowHpx > 0) rowHpx.toFloat() else 56.dp.toPx()
                        val margin = 14.dp.toPx()
                        val spot = spotlightDp.toPx()
                        val belowY = boxWin.y + ring.y + spot + margin
                        val aboveY = boxWin.y + ring.y - spot - margin - rowH
                        val y = (if (belowY + rowH <= view.height) belowY else aboveY)
                            .coerceIn(0f, (view.height - rowH).coerceAtLeast(0f))
                        val x = (boxWin.x + ring.x - rowW / 2f)
                            .coerceIn(0f, (view.width - rowW).coerceAtLeast(0f))
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                    .onGloballyPositioned { rowWpx = it.size.width; rowHpx = it.size.height },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Cancel on the left, primary (Identify) on the right — matches the
                // app's dialog button convention (dismiss left, confirm right).
                TextButton(onClick = onCancel) { Text("Cancel") }
                FilledTonalButton(onClick = { sample() }) { Text("Identify") }
            }
        } else if (choosing) {
            // Centered chooser: title + tap-to-blink list (scrolls if many) + buttons.
            val list = candidates ?: emptyList()
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 320.dp),
                // Slightly translucent (80% opaque) so the sampled color/spotlight
                // behind the results window stays faintly visible.
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Color identifier results",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
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
