package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudUnits
import com.eried.eucplanet.hud.ui.parseHexColor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/**
 * The HUD's "Custom" screen: renders an Overlay Studio preset the rider
 * picked on the phone, ignoring viewport backgrounds (the HUD panel is
 * transparent like a video output's lower-third).
 *
 * Element types supported on the HUD: DATA_VALUE, DATA_DIAL, DATA_BAR,
 * DATA_GRAPH, TEXT, G_FORCE, WHEEL_NAME. Others (IMAGE, MAP, CLOCK,
 * FLOATING_CAMERA, APP_BADGE) are dropped silently -- the HUD has its
 * own Map/Camera/Nav screens and the rider can put live data there. This
 * keeps the renderer compact.
 */
@Composable
fun CustomOverlayScreen(hud: HudState) {
    val elements = remember(hud.customOverlayJson) {
        parseElements(hud.customOverlayJson)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (elements.isEmpty()) {
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
        elements.forEach { el ->
            val xDp = (el.x * w).dp
            val yDp = (el.y * h).dp
            val widthDp = (el.width * w).dp
            val heightDp = (if (el.height > 0f) el.height else el.width * 0.5f) * h
            Box(
                modifier = Modifier
                    .offset(x = xDp, y = yDp)
                    .size(widthDp, heightDp.dp)
            ) {
                RenderElement(el, hud)
            }
        }
    }
}

@Composable
private fun RenderElement(el: SimpleElement, hud: HudState) {
    when (el.type) {
        "TEXT" -> RenderText(el, hud)
        "WHEEL_NAME" -> RenderWheelName(el, hud)
        "DATA_VALUE" -> RenderDataValue(el, hud)
        "DATA_DIAL" -> RenderDial(el, hud)
        "DATA_BAR" -> RenderBar(el, hud)
        "DATA_GRAPH" -> RenderGraph(el, hud)
        "G_FORCE" -> RenderGForce(el, hud)
        else -> Unit
    }
}

@Composable
private fun RenderText(el: SimpleElement, hud: HudState) {
    val resolved = remember(el.text, hud.timestampMs) {
        // {speed} / {battery} / etc. substitution. Keep the set narrow;
        // unknown tokens stay as the literal "{name}" so the rider sees
        // their typo.
        el.text
            .replace("{speed}", "%.0f".format(hud.speedKmh))
            .replace("{battery}", "%d".format(hud.batteryPercent))
            .replace("{voltage}", "%.1f".format(hud.voltage))
            .replace("{current}", "%.1f".format(hud.current))
            .replace("{pwm}", "%.0f".format(hud.pwm))
            .replace("{temp}", "%.0f".format(hud.temperatureC))
            .replace("{trip}", "%.1f".format(hud.tripKm))
            .replace("{wheel_name}", hud.wheelName)
    }
    val fontSize = (el.heightFraction(0.5f) * 60f).coerceAtLeast(10f).sp
    Text(
        text = resolved,
        color = Color(el.foreground.toInt()),
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxSize()
            .alignBy(el.textAlign)
    )
}

@Composable
private fun RenderWheelName(el: SimpleElement, hud: HudState) {
    Text(
        text = hud.wheelName.ifBlank { "—" },
        color = Color(el.foreground.toInt()),
        fontSize = (el.heightFraction(0.5f) * 60f).coerceAtLeast(10f).sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun RenderDataValue(el: SimpleElement, hud: HudState) {
    val (value, unit) = metricValueAndUnit(el.metric, hud)
    val combined = if (el.showLabel) "$value $unit" else value
    Text(
        text = combined,
        color = Color(el.foreground.toInt()),
        fontSize = (el.heightFraction(0.5f) * 60f).coerceAtLeast(10f).sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun RenderDial(el: SimpleElement, hud: HudState) {
    val frac = remember(hud.timestampMs) {
        val (v, _) = metricNumericAndUnit(el.metric, hud)
        (v / el.gaugeMax).coerceIn(0f, 1f)
    }
    val (label, _) = metricNumericAndUnit(el.metric, hud)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = min(size.width, size.height) * 0.10f
        val diameter = min(size.width, size.height) - stroke
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = Size(diameter, diameter)
        val startAngle = 135f
        val sweepFull = 270f
        // Track
        drawArc(
            color = Color.White.copy(alpha = 0.15f),
            startAngle = startAngle,
            sweepAngle = sweepFull,
            useCenter = false,
            style = Stroke(width = stroke),
            topLeft = topLeft, size = arcSize
        )
        // Value
        drawArc(
            color = Color(el.foreground.toInt()),
            startAngle = startAngle,
            sweepAngle = sweepFull * frac,
            useCenter = false,
            style = Stroke(width = stroke),
            topLeft = topLeft, size = arcSize
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "%.0f".format(label),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RenderBar(el: SimpleElement, hud: HudState) {
    val (numeric, unit) = metricNumericAndUnit(el.metric, hud)
    val frac = (numeric / el.gaugeMax).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
        val barH = size.height * 0.4f
        val cy = size.height / 2f
        drawRect(
            color = Color.White.copy(alpha = 0.15f),
            topLeft = Offset(0f, cy - barH / 2f),
            size = Size(size.width, barH)
        )
        drawRect(
            color = Color(el.foreground.toInt()),
            topLeft = Offset(0f, cy - barH / 2f),
            size = Size(size.width * frac, barH)
        )
    }
    if (el.barShowValue) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text(
                text = "%.0f %s".format(numeric, unit),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RenderGraph(el: SimpleElement, hud: HudState) {
    // Per-element history buffer keyed off the element id (which the
    // preset already supplies).
    val buf = remember(el.id) { java.util.ArrayDeque<Float>() }
    val (numeric, _) = metricNumericAndUnit(el.metric, hud)
    LaunchedEffect(hud.timestampMs) {
        buf.addLast(numeric)
        val cap = (el.graphWindowSec * 5).coerceAtLeast(20) // 5 Hz feed
        while (buf.size > cap) buf.removeFirst()
    }
    Canvas(Modifier.fillMaxSize()) {
        if (buf.size < 2) return@Canvas
        val sMin = buf.min()
        val sMax = buf.max()
        val range = (sMax - sMin).coerceAtLeast(0.0001f)
        val step = size.width / (buf.size - 1)
        val path = Path()
        buf.forEachIndexed { i, v ->
            val x = i * step
            val norm = ((v - sMin) / range).coerceIn(0f, 1f)
            val y = size.height * (1f - norm)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Color(el.foreground.toInt()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun RenderGForce(el: SimpleElement, hud: HudState) {
    val gx = remember(hud.timestampMs) { lateralG(hud.wheelRollDeg) }
    val gy = 0f
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 2f - 2f
        drawCircle(
            color = Color.White.copy(alpha = 0.2f),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )
        val tip = Offset(
            cx + (gx / el.gForceScale).coerceIn(-1f, 1f) * r,
            cy + (gy / el.gForceScale).coerceIn(-1f, 1f) * r
        )
        drawCircle(
            color = Color(el.foreground.toInt()),
            radius = 3.dp.toPx(),
            center = tip
        )
    }
}

/** Read a metric value formatted for display + unit suffix. */
private fun metricValueAndUnit(metric: String, hud: HudState): Pair<String, String> {
    return when (metric) {
        "SPEED" -> {
            val v = HudUnits.speed(hud.speedKmh, hud.unitSpeed)
            "%.0f".format(v) to HudUnits.speedSuffix(hud.unitSpeed)
        }
        "BATTERY" -> "%d".format(hud.batteryPercent) to "%"
        "VOLTAGE" -> "%.1f".format(hud.voltage) to "V"
        "CURRENT" -> "%.1f".format(hud.current) to "A"
        "PWM" -> "%.0f".format(hud.pwm) to "%"
        "TEMP", "TEMP_C" -> {
            val v = HudUnits.temperature(hud.temperatureC, hud.unitTemp)
            "%.0f".format(v) to HudUnits.temperatureSuffix(hud.unitTemp)
        }
        "TRIP", "TRIP_DIST" -> {
            val v = HudUnits.distance(hud.tripKm, hud.unitDistance)
            "%.1f".format(v) to HudUnits.distanceSuffix(hud.unitDistance)
        }
        else -> "—" to ""
    }
}

/** Same metrics but as a raw Float for gauge/graph rendering. */
private fun metricNumericAndUnit(metric: String, hud: HudState): Pair<Float, String> {
    return when (metric) {
        "SPEED" -> HudUnits.speed(hud.speedKmh, hud.unitSpeed) to HudUnits.speedSuffix(hud.unitSpeed)
        "BATTERY" -> hud.batteryPercent.toFloat() to "%"
        "VOLTAGE" -> hud.voltage to "V"
        "CURRENT" -> hud.current to "A"
        "PWM" -> hud.pwm to "%"
        "TEMP", "TEMP_C" ->
            HudUnits.temperature(hud.temperatureC, hud.unitTemp) to HudUnits.temperatureSuffix(hud.unitTemp)
        "TRIP", "TRIP_DIST" ->
            HudUnits.distance(hud.tripKm, hud.unitDistance) to HudUnits.distanceSuffix(hud.unitDistance)
        else -> 0f to ""
    }
}

private fun lateralG(rollDeg: Float): Float {
    if (rollDeg == 0f) return 0f
    val rad = rollDeg * PI.toFloat() / 180f
    return kotlin.math.tan(rad).coerceIn(-1.5f, 1.5f)
}

private fun Modifier.alignBy(textAlign: String): Modifier = this

private fun SimpleElement.heightFraction(default: Float): Float =
    if (this.height > 0f) this.height else default

/**
 * Minimal element model parsed straight off the wire. Only the fields
 * the HUD renderer uses are captured; everything else is dropped.
 */
private data class SimpleElement(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val textAlign: String,
    val metric: String,
    val showLabel: Boolean,
    val gaugeMax: Float,
    val foreground: Long,
    val background: Long,
    val graphWindowSec: Int,
    val barShowValue: Boolean,
    val gForceScale: Float
)

private fun parseElements(json: String): List<SimpleElement> {
    if (json.isBlank()) return emptyList()
    return try {
        val root = JSONObject(json)
        val arr: JSONArray = root.optJSONArray("elements") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseElement(it) }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun parseElement(o: JSONObject): SimpleElement? {
    val type = o.optString("type", "") ?: return null
    if (type.isBlank()) return null
    return SimpleElement(
        id = o.optString("id", java.util.UUID.randomUUID().toString()),
        type = type,
        x = o.optDouble("x", 0.0).toFloat(),
        y = o.optDouble("y", 0.0).toFloat(),
        width = o.optDouble("width", 0.3).toFloat(),
        height = o.optDouble("height", 0.0).toFloat(),
        text = o.optString("text", ""),
        textAlign = o.optString("textAlign", "START"),
        metric = o.optString("metric", "SPEED"),
        showLabel = o.optBoolean("showLabel", true),
        gaugeMax = o.optDouble("gaugeMax", 100.0).toFloat(),
        foreground = o.optLong("foreground", 0xFFFFFFFFL),
        background = o.optLong("background", 0x66000000L),
        graphWindowSec = o.optInt("graphWindowSec", 10),
        barShowValue = o.optBoolean("barShowValue", true),
        gForceScale = o.optDouble("gForceScale", 1.0).toFloat()
    )
}
