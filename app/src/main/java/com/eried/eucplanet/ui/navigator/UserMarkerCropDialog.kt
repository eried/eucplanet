package com.eried.eucplanet.ui.navigator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Crop / zoom / position dialog used by the rider to turn a picked photo
 * into a small circular avatar for their map marker. The visual model is
 * "the round head of the marker stays put in the centre of the screen, the
 * rider drags / pinches the photo behind it until the part they want is
 * inside the circle". A dimmed overlay with a transparent hole shows the
 * crop region, and `Apply` renders the visible disk to a 64×64 PNG which
 * the caller saves to settings.
 *
 * Constraints we enforce while the user pans / pinches:
 *   * The image must always cover the crop circle — we never let the rider
 *     pan an edge into the circle or zoom out below the cover threshold,
 *     because a marker with a transparent wedge is just weird.
 *   * The minimum scale auto-adjusts so an image of any aspect ratio can
 *     cover the circle: `minScale = max(diameter / srcW, diameter / srcH)`.
 *   * Pan is clamped so the smaller-dimension edge never enters the circle.
 *
 * No external dependencies. Output is a Bitmap with a circular alpha mask
 * already burned in — the caller just encodes it to base64 PNG.
 */
@Composable
fun UserMarkerCropDialog(
    source: Bitmap,
    onCancel: () -> Unit,
    onApply: (Bitmap) -> Unit
) {
    // Circle diameter on screen, in px — derived from the dialog box width
    // captured below so the math doesn't fight different screen sizes.
    val density = LocalDensity.current
    val circleRadiusDp = 120.dp
    val circleRadiusPx = with(density) { circleRadiusDp.toPx() }

    // Display size for the picked image: shrink-fit into the visible area
    // by the LONGER dimension, so the rider sees the whole photo at a sane
    // starting size and zooms IN from there.
    val displayBaseW: Float
    val displayBaseH: Float
    run {
        // Use a generous reference width — actual position math uses
        // current center / scale, not these base values directly.
        val refMaxW = with(density) { 360.dp.toPx() }
        val refMaxH = with(density) { 480.dp.toPx() }
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val fitScale = minOf(refMaxW / srcW, refMaxH / srcH)
        displayBaseW = srcW * fitScale
        displayBaseH = srcH * fitScale
    }

    // Smallest scale that still covers the circle — see class doc.
    val minScale = remember(source) {
        max(
            (circleRadiusPx * 2f) / displayBaseW,
            (circleRadiusPx * 2f) / displayBaseH
        ).coerceAtLeast(1f)
    }

    // Current zoom (1.0 = displayBaseW × displayBaseH) and pan offset, in
    // *screen px*, relative to the centre of the dialog box.
    var scale by remember { mutableFloatStateOf(minScale) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Clamp pan so the image's smaller edge never enters the crop circle.
    fun clampPan() {
        val halfW = displayBaseW * scale / 2f
        val halfH = displayBaseH * scale / 2f
        val limX = halfW - circleRadiusPx
        val limY = halfH - circleRadiusPx
        offsetX = offsetX.coerceIn(-limX, limX)
        offsetY = offsetY.coerceIn(-limY, limY)
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        // Full-screen layout: the dim and the punch-out circle now cover the
        // entire screen, the image sits behind the dim and is freely panned
        // / pinched, and the Cancel / Apply buttons are anchored at the
        // centre of the screen and offset down by exactly the circle radius
        // so they always land just below the visible crop circle. Using
        // absolute positioning here so the buttons can never be clipped off
        // the bottom of the dialog window (the Column / weight version
        // could end up off-screen on some screen sizes).
        Box(modifier = Modifier.fillMaxSize()) {
            // The image, drawn FIRST so the dim overlay covers it.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(
                            with(density) { displayBaseW.toDp() },
                            with(density) { displayBaseH.toDp() }
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                )
            }
            // Dim overlay covering the WHOLE screen with a transparent
            // circular hole at the centre. Compositing offscreen so the
            // DstOut blend only erases inside this layer (the screen below
            // stays opaque). The pointer-input sits on this layer so pan /
            // pinch gestures land anywhere on the dim area.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = 0.7f))
                        drawCircle(
                            color = Color.Black,
                            radius = circleRadiusPx,
                            blendMode = BlendMode.DstOut
                        )
                    }
                    .pointerInput(source) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceAtLeast(minScale)
                            offsetX += pan.x
                            offsetY += pan.y
                            clampPan()
                        }
                    }
            )
            // Buttons anchored at the centre and offset down by the circle
            // radius + a small gap, so they sit JUST BELOW the visible crop
            // circle on every screen size.
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = circleRadiusDp + 28.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel), color = Color.White)
                }
                TextButton(onClick = {
                    val out = renderCircleCrop(
                        source, scale, offsetX, offsetY,
                        displayBaseW, displayBaseH, circleRadiusPx
                    )
                    onApply(out)
                }) {
                    Text(stringResource(R.string.action_apply), color = Color.White)
                }
            }
        }
    }
}

/**
 * Renders a 64×64 PNG-ready Bitmap of the circular region the rider chose,
 * with the alpha mask already burned in so the marker JS can drop the image
 * straight into a CSS background without further compositing.
 *
 * Math: a source-bitmap pixel at (sx, sy) is shown at screen position
 *
 *     screenX = displayCenterX + (sx − srcW/2) · displayScale · scale + offsetX
 *     screenY = displayCenterY + (sy − srcH/2) · displayScale · scale + offsetY
 *
 * where displayScale = displayBaseW / srcW. Inverting that for the centre
 * of the crop circle (which sits at displayCenterX, displayCenterY) gives
 * the source-bitmap coordinates of the disc to copy out.
 */
private fun renderCircleCrop(
    source: Bitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    displayBaseW: Float,
    displayBaseH: Float,
    circleRadiusPx: Float
): Bitmap {
    val srcW = source.width.toFloat()
    val srcH = source.height.toFloat()
    val displayScale = displayBaseW / srcW          // base-fit scale before user zoom
    val effectiveScale = displayScale * scale       // px per source-pixel right now

    // Where the crop-circle centre lands in SOURCE-bitmap coordinates.
    val srcCx = srcW / 2f - offsetX / effectiveScale
    val srcCy = srcH / 2f - offsetY / effectiveScale
    val srcR = circleRadiusPx / effectiveScale

    val outSize = 64
    val output = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    // Draw the photo region into the 64×64 output and apply a circular
    // alpha mask via DstIn so the result is a proper round avatar.
    val srcRect = Rect(
        (srcCx - srcR).toInt().coerceAtLeast(0),
        (srcCy - srcR).toInt().coerceAtLeast(0),
        (srcCx + srcR).toInt().coerceAtMost(source.width),
        (srcCy + srcR).toInt().coerceAtMost(source.height)
    )
    val dstRect = RectF(0f, 0f, outSize.toFloat(), outSize.toFloat())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(source, srcRect, dstRect, paint)

    // Apply circular alpha mask.
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    val maskPath = Path().apply {
        addCircle(outSize / 2f, outSize / 2f, outSize / 2f, Path.Direction.CW)
    }
    // Easier than building a separate mask bitmap: clipPath does the same
    // visually for our case (anti-aliased edges), and DST_IN ensures we
    // really only keep the circular area in the alpha channel.
    val maskBitmap = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ALPHA_8)
    Canvas(maskBitmap).drawPath(maskPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
    })
    canvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)

    return output
}

/**
 * Convenience for callers: encode a Bitmap as a `data:image/png;base64,...`
 * URL suitable for stuffing into a CSS `background-image` (the map JS reads
 * it that way) and for persisting in `AppSettings` JSON.
 */
fun Bitmap.toBase64DataUrl(): String {
    val baos = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, baos)
    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    return "data:image/png;base64,$b64"
}
