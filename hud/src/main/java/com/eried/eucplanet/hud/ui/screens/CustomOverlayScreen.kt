package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.overlay.OverlayElementRenderer
import com.eried.eucplanet.hud.overlay.StudioElementData
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.hud.protocol.OverlayElementType
import com.eried.eucplanet.hud.protocol.ViewportLayout
import com.eried.eucplanet.hud.protocol.ViewportConfig
import com.eried.eucplanet.hud.protocol.ViewportSourceType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders the rider's selected Overlay Studio preset on the HUD's Custom
 * screen, optionally with the rear-camera preview as the background.
 *
 * Two layout modes, picked from the dominant element rotationDeg in the
 * preset (see OverlayStudioScreen.kt:1539: rotationDeg = (360 - deviceRotation)
 * % 360 -- the studio stamps each new element with this on placement):
 *
 *   - Portrait preset (rotationDeg = 0): coords are in the rider's view
 *     frame already (they were holding the phone portrait). We render
 *     directly on the HUD's full landscape panel at raw coords. The
 *     layout will read wider than the studio shows it (HUD landscape is
 *     wider than phone portrait) but element positions are 1:1 with the
 *     preset JSON.
 *
 *   - Landscape preset (rotationDeg = 90 or 270): coords are in the
 *     studio's portrait-fixed canvas, with each element pre-rotated so
 *     it reads upright when the rider tilts the phone to landscape. We
 *     render onto a portrait sub-canvas using the EXACT same widthIn /
 *     heightIn / per-element graphicsLayer rules the studio uses, then
 *     rotate the whole sub-canvas (-rotationDeg) to fit the HUD's
 *     landscape panel. The element rotation cancels with the canvas
 *     rotation -- content ends up upright and the layout reads the same
 *     way the rider sees it on their landscape phone in the studio.
 *
 * Sizing inside the rotated landscape mode tracks the studio: the
 * portrait sub-canvas is sized so its post-rotation width fills the HUD
 * width, with a 9:19.5 aspect (modern-phone default -- we don't store
 * the rider's actual screen aspect in the preset). Vertical letterbox
 * appears top/bottom on the HUD if the panel is taller than 9:19.5.
 */
@Composable
fun CustomOverlayScreen(hud: HudState, withCamera: Boolean = false) {
    val preset = remember(hud.customOverlayJson) { parsePreset(hud.customOverlayJson) }
    val data = remember(hud) { StudioElementData.from(hud) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (withCamera) {
            RearCameraPreview(Modifier.fillMaxSize())
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (preset == null || preset.elements.isEmpty()) {
                // Drop the empty-state message to ~75% of the screen height so
                // it sits below the visual centre — riders glancing up at the
                // HUD instinctively scan the upper half for live data, so the
                // unhelpful "no preset yet" line shouldn't squat in their
                // primary attention zone.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.BiasAlignment(0f, 0.5f)
                ) {
                    Text(
                        text = "Pick a preset in EUC Planet → Settings / Integration",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
                return@BoxWithConstraints
            }
            val dominantRot = dominantRotation(preset)
            if (dominantRot == 90 || dominantRot == 270) {
                LandscapeRotatedCanvas(
                    preset = preset,
                    data = data,
                    // Studio's formula: rotationDeg = (360 - deviceRotation)
                    // % 360. OrientationEventListener's deviceRotation
                    // increases CW from natural portrait, so deviceRotation
                    // = 270 (left-landscape, phone's top edge to rider's
                    // LEFT) maps to stored rotationDeg = 90. Mapping that
                    // back to the rider's view: portrait (x_p, y_p) lands
                    // at landscape (y_p, 1 - x_p), which is what canvas
                    // rotation -dominantRot produces. (+dominantRot would
                    // land at (1-y_p, x_p) -- the OTHER landscape
                    // orientation, opposite of what the rider authored.)
                    canvasRot = -dominantRot.toFloat(),
                    hudW = maxWidth,
                    hudH = maxHeight
                )
            } else {
                PortraitRawCanvas(
                    preset = preset,
                    data = data,
                    containerW = maxWidth,
                    containerH = maxHeight
                )
            }
        }
    }
}

/** Most common rotationDeg across the preset's elements, snapped to one
 *  of 0 / 90 / 180 / 270. Returns 0 if the preset has no elements. */
private fun dominantRotation(preset: OverlayPreset): Int {
    val buckets = preset.elements
        .groupingBy { ((it.rotationDeg.toInt() % 360) + 360) % 360 }
        .eachCount()
    return buckets.maxByOrNull { it.value }?.key ?: 0
}

/** Render the preset at raw portrait coords directly on the HUD's
 *  landscape panel. Used for rotationDeg=0 presets where the coords
 *  are already in the rider's view frame. */
@Composable
private fun PortraitRawCanvas(
    preset: OverlayPreset,
    data: StudioElementData,
    containerW: Dp,
    containerH: Dp
) {
    preset.elements.forEach { el ->
        Box(
            modifier = Modifier
                .offset(x = containerW * el.x, y = containerH * el.y)
                .widthIn(max = containerW * el.width)
                .then(
                    if (el.height > 0f)
                        Modifier.heightIn(max = containerH * el.height)
                    else Modifier
                )
                .graphicsLayer(alpha = el.opacity)
        ) {
            OverlayElementRenderer(el, data)
        }
    }
}

/** Render the preset on a portrait sub-canvas, then rotate the canvas
 *  to fit the HUD's landscape panel. Used for landscape presets
 *  (dominant rotationDeg = 90 or 270) where the rider authored on a
 *  portrait-fixed canvas while holding the phone sideways. The
 *  rotation cancels each element's rotationDeg, leaving content upright
 *  and the layout matching the rider's landscape phone view 1:1. */
@Composable
private fun BoxScope.LandscapeRotatedCanvas(
    preset: OverlayPreset,
    data: StudioElementData,
    canvasRot: Float,
    hudW: Dp,
    hudH: Dp
) {
    // Portrait sub-canvas at the 9:19.5 modern-phone aspect. This is
    // the aspect element coords are normalized against. Sized so the
    // post-rotation width fills the HUD width; post-rotation height
    // ends up being hudW * 9/19.5 = ~46% of hudW. On a 16:9-ish HUD
    // that leaves a vertical letterbox above and below the overlay.
    //
    // The letterbox is the price of preserving the rider's layout
    // exactly. A previous attempt at HUD-native canvas dimensions
    // (480x800) eliminated the letterbox but reinterpreted every
    // y-coord on a 19% shorter vertical axis -- the layout drifted
    // and the rider called it out. A second attempt scaled the
    // rotated canvas to fill the HUD vertically -- that worked
    // arithmetically but the canvas became wider than the HUD and
    // edge-positioned elements got clipped off the sides.
    val portraitAspect = 9f / 19.5f
    val portraitH = hudW
    val portraitW = portraitH * portraitAspect

    // Parameter-overload graphicsLayer instead of the lambda form: with
    // stable Float properties Compose can reuse the same RenderNode
    // across recompositions, where the lambda form re-evaluates each
    // frame and invalidates the layer. At 5 Hz across 8 layers (canvas +
    // 7 elements) the lambda form was the dominant per-frame cost and
    // pushed the Custom screen into ANR territory.
    // Fill-up scaling is baked into the canvas DIMENSIONS rather than
    // a graphicsLayer scaleX/scaleY. graphicsLayer-based scaling under
    // rotation forces Skia to re-rasterise the layer at the new scale
    // every frame, which under the 5 Hz wire stream + debug-build
    // unoptimised Compose runtime tipped the Custom screen straight
    // into ANR. Scaling the LAYOUT (resizing the requiredSize box)
    // instead lets Compose lay everything out once at the larger size;
    // the graphicsLayer only rotates, no extra rasterisation pass.
    //
    // Scale factor chosen so the rotated canvas's short side equals
    // HUD height (fills vertically). The long side overflows the HUD
    // horizontally by ~30 percent, but the rider's elements sit in
    // the inner portion of the canvas so the overflow is empty
    // padding -- nothing visible is clipped.
    val fillScale = hudH.value / portraitW.value
    val scaledW = portraitW * fillScale
    val scaledH = portraitH * fillScale
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .requiredSize(width = scaledW, height = scaledH)
            .graphicsLayer(
                rotationZ = canvasRot,
                transformOrigin = TransformOrigin.Center
            )
    ) {
        preset.elements.forEach { el ->
            // key(el.id) preserves element identity across the 5 Hz
            // wire-frame recompositions: without it, Compose treats
            // each forEach iteration as a fresh slot and re-runs the
            // entire element renderer + layout from scratch instead
            // of skipping when the layout-affecting params are stable
            // (only the metric value inside changes per frame).
            key(el.id) {
                // Per-element rotation matches the stored rotationDeg,
                // so element + canvas (= -dominantRot) net to
                // (rotationDeg - dominantRot). For elements matching
                // the dominant rotation that's 0 (upright); off-axis
                // elements stay at their intended off-axis angle.
                //
                // Offsets/constraints reference scaledW/scaledH so
                // element sizes scale with the canvas.
                Box(
                    modifier = Modifier
                        .offset(x = scaledW * el.x, y = scaledH * el.y)
                        .widthIn(max = scaledW * el.width)
                        .then(
                            if (el.height > 0f)
                                Modifier.heightIn(max = scaledH * el.height)
                            else Modifier
                        )
                        .graphicsLayer(
                            rotationZ = el.rotationDeg,
                            alpha = el.opacity
                        )
                ) {
                    OverlayElementRenderer(el, data)
                }
            }
        }
    }
}

/** Parse the wire-format preset JSON into an OverlayPreset using the
 *  shared model. Returns null on any failure so the rider sees the
 *  empty-state hint rather than a crash. */
private fun parsePreset(json: String): OverlayPreset? {
    if (json.isBlank()) return null
    return try {
        val root = JSONObject(json)
        val elementsArr = root.optJSONArray("elements") ?: JSONArray()
        val elements = (0 until elementsArr.length()).mapNotNull { i ->
            elementsArr.optJSONObject(i)?.let(::parseElement)
        }
        // We don't need viewports / dividers on the HUD (no panes); pass
        // defaults so the OverlayPreset constructor is happy.
        OverlayPreset(
            name = root.optString("name", ""),
            elements = elements
        )
    } catch (_: Throwable) {
        null
    }
}

private fun parseElement(o: JSONObject): OverlayElement? {
    val typeStr = o.optString("type", "")
    val type = runCatching { OverlayElementType.valueOf(typeStr) }.getOrNull() ?: return null
    val d = OverlayElement(type = type)
    return OverlayElement(
        id = o.optString("id", d.id),
        type = type,
        x = o.optDouble("x", d.x.toDouble()).toFloat(),
        y = o.optDouble("y", d.y.toDouble()).toFloat(),
        width = o.optDouble("width", d.width.toDouble()).toFloat(),
        height = o.optDouble("height", d.height.toDouble()).toFloat(),
        rotationDeg = o.optDouble("rotationDeg", d.rotationDeg.toDouble()).toFloat(),
        opacity = o.optDouble("opacity", d.opacity.toDouble()).toFloat(),
        shadow = o.optBoolean("shadow", d.shadow),
        shadowColor = o.optLong("shadowColor", d.shadowColor),
        shadowStrength = o.optDouble("shadowStrength", d.shadowStrength.toDouble()).toFloat(),
        shadowDistance = o.optDouble("shadowDistance", d.shadowDistance.toDouble()).toFloat(),
        shadowAngle = o.optDouble("shadowAngle", d.shadowAngle.toDouble()).toFloat(),
        metric = o.optString("metric", d.metric),
        showLabel = o.optBoolean("showLabel", d.showLabel),
        text = o.optString("text", d.text),
        textAlign = o.optString("textAlign", d.textAlign),
        badgeStacked = o.optBoolean("badgeStacked", d.badgeStacked),
        badgeShowVersion = o.optBoolean("badgeShowVersion", d.badgeShowVersion),
        graphWindowSec = o.optInt("graphWindowSec", d.graphWindowSec),
        gaugeMax = o.optDouble("gaugeMax", d.gaugeMax.toDouble()).toFloat(),
        foreground = o.optLong("foreground", d.foreground),
        background = o.optLong("background", d.background),
        cameraKey = o.optString("cameraKey", d.cameraKey),
        clockStyle = o.optString("clockStyle", d.clockStyle),
        clockShowDate = o.optBoolean("clockShowDate", d.clockShowDate),
        mapStyle = o.optString("mapStyle", d.mapStyle),
        mapZoom = o.optInt("mapZoom", d.mapZoom),
        mapRotateWithHeading = o.optBoolean("mapRotateWithHeading", d.mapRotateWithHeading),
        mapTrace = o.optBoolean("mapTrace", d.mapTrace),
        mapBorderWidth = o.optDouble("mapBorderWidth", d.mapBorderWidth.toDouble()).toFloat(),
        gForceScale = o.optDouble("gForceScale", d.gForceScale.toDouble()).toFloat(),
        gForceSmoothing = o.optDouble("gForceSmoothing", d.gForceSmoothing.toDouble()).toFloat(),
        barShowValue = o.optBoolean("barShowValue", d.barShowValue),
        dialStyle = o.optString("dialStyle", d.dialStyle),
        unitPosition = o.optString("unitPosition", d.unitPosition),
        dialShowColorBand = o.optBoolean("dialShowColorBand", d.dialShowColorBand),
        dialOrangeThresholdPct = o.optInt("dialOrangeThresholdPct", d.dialOrangeThresholdPct),
        dialRedThresholdPct = o.optInt("dialRedThresholdPct", d.dialRedThresholdPct)
    )
}
