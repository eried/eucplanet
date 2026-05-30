package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
 * Uses the SAME element-by-element renderer the phone studio does
 * (see com.eried.eucplanet.hud.overlay.OverlayElementRenderer), so a
 * preset reads pixel-identical between phone and HUD.
 *
 * Per-element rotation is intentionally NOT applied: the bundled
 * "landscape" presets pre-rotate their elements 90° expecting the
 * studio canvas to also rotate. The Motoeye panel is already landscape;
 * doubling the rotation flips text sideways.
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Pick a preset in EUC Planet → Settings / Integration",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
                return@BoxWithConstraints
            }
            val containerW = maxWidth
            val containerH = maxHeight
            // The studio's "landscape" bundled presets were composed in
            // its portrait canvas with each element pre-rotated 90° so
            // they read upright when the studio rotates the canvas for
            // landscape video output. Our HUD panel is already landscape
            // and we don't apply per-element rotation, so a landscape
            // preset's (x, y) coordinates -- still stored in
            // portrait-canvas frame -- need to be counter-rotated 90°
            // clockwise to land in the right place on our display.
            val isLandscapePreset =
                preset.elements.count { it.rotationDeg != 0f } * 2 > preset.elements.size
            preset.elements.forEach { el ->
                // Effective (x, y, width, height) in the HUD's landscape
                // frame. For a landscape preset, swap width<->height and
                // rotate the corner: (px, py) -> (1 - py - phEff, px).
                val effW: Float
                val effH: Float
                val effX: Float
                val effY: Float
                if (isLandscapePreset) {
                    // For an element that didn't have a rider-set height,
                    // pick a sensible default in the portrait frame
                    // first (width * 0.5), then swap.
                    val phEff = if (el.height > 0f) el.height else el.width * 0.5f
                    val pwEff = el.width
                    effW = phEff
                    effH = pwEff
                    effX = (1f - el.y - phEff).coerceIn(0f, 1f)
                    effY = el.x.coerceIn(0f, 1f)
                } else {
                    effW = el.width
                    effH = el.height
                    effX = el.x
                    effY = el.y
                }
                // Mirror the studio's sizing rule: widthIn(max = ...) +
                // heightIn only when explicitly set. Lets each element
                // take its natural aspect ratio if height was left auto.
                Box(
                    modifier = Modifier
                        .offset(x = containerW * effX, y = containerH * effY)
                        .widthIn(max = containerW * effW)
                        .then(
                            if (effH > 0f)
                                Modifier.heightIn(max = containerH * effH)
                            else Modifier
                        )
                        .alpha(el.opacity)
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
        unitPosition = o.optString("unitPosition", d.unitPosition)
    )
}
