package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
            val w = maxWidth.value
            val h = maxHeight.value
            preset.elements.forEach { el ->
                val xDp = (el.x * w).dp
                val yDp = (el.y * h).dp
                val widthDp = (el.width * w).dp
                val heightDp = (if (el.height > 0f) el.height else el.width * 0.5f) * h
                Box(
                    modifier = Modifier
                        .offset(x = xDp, y = yDp)
                        .size(widthDp, heightDp.dp)
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
