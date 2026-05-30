package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
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
 * Canvas frame: the studio canvas is ALWAYS portrait-fixed (see comment
 * in OverlayStudioScreen.kt:802 -- "the layout itself never rotates").
 * The rider's 0..1 coords are normalized to that portrait box. To
 * preserve the layout exactly as authored we render inside a centred
 * portrait viewport on the HUD's landscape panel: black letterbox bars
 * on the sides, the preset rendered inside the central portrait region.
 * No coord rotation, no horizontal stretching -- the preset reads the
 * same shape it does in the studio editor.
 *
 * Per-element rotation IS applied here (matches the studio's
 * graphicsLayer { rotationZ = element.rotationDeg } at the equivalent
 * site in StudioOverlayElements.kt:324). That way a landscape-authored
 * preset (rider held phone sideways, element.rotationDeg = 90 or 270)
 * draws on the HUD exactly the same way the studio drew it: rotated
 * inside the portrait canvas, ready for the rider to compare 1:1.
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
            // Portrait viewport sized to fit the HUD's height, with the
            // typical modern-phone aspect (9:19.5). We don't know the
            // rider's actual screen aspect because OverlayPreset doesn't
            // store it -- 9:19.5 is the most common, and being slightly
            // off here just changes how much side letterbox the rider
            // sees, not where elements land.
            val portraitAspect = 9f / 19.5f
            val containerH = maxHeight
            val containerW = min(maxWidth.value, (maxHeight.value * portraitAspect)).dp
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(containerW)
                    .fillMaxHeight()
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
                            .graphicsLayer { rotationZ = el.rotationDeg }
                            .alpha(el.opacity)
                    ) {
                        OverlayElementRenderer(el, data)
                    }
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
