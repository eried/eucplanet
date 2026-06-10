package com.eried.eucplanet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.roundToInt

/** One metric tile's spec: how to pull its value + unit + accent from [WheelData]. */
internal class Metric(
    val key: String,
    val label: String,
    val unit: String,
    val color: (AppThemeColors) -> Color,
    val value: (WheelData) -> Float,
    val text: (WheelData) -> String,
)

/** The default 6-tile grid, mirroring the Android dashboard's BATTERY / TEMP /
 *  VOLTAGE / CURRENT / LOAD / TRIP layout. Also the lookup for MetricDetail. */
internal val defaultMetrics = listOf(
    Metric("battery", "BATTERY", "%", { it.metricBattery }, { it.batteryPercent.toFloat() }, { it.batteryPercent.toString() }),
    Metric("temp", "TEMP", "°C", { it.metricTemp }, { it.maxTemperature }, { it.maxTemperature.f0() }),
    Metric("voltage", "VOLTAGE", "V", { it.metricVoltage }, { it.voltage }, { it.voltage.f1() }),
    Metric("current", "CURRENT", "A", { it.metricAccel }, { it.current }, { it.current.f1() }),
    Metric("load", "LOAD", "%", { it.metricPosition }, { it.pwm }, { it.pwm.f0() }),
    Metric("trip", "TRIP", "km", { it.statusGood }, { it.tripDistance }, { it.tripDistance.f1() }),
)

/** One dashboard control button. [activeColor] is used when [active] is true. */
private class RideAction(
    val label: String,
    val glyph: String,
    val activeColor: (AppThemeColors) -> Color,
)

/**
 * The shared EUC Planet dashboard — a faithful port of the Android live-ride
 * screen: status top bar, speed gauge filling the upper area, the 6-tile metric
 * grid with background sparklines, and the 6-button action grid. Drives off a
 * single [WheelData] (live from a WheelSession, or simulated in demo mode).
 */
@Composable
internal fun DashboardScreen(
    d: WheelData,
    history: List<WheelData>,
    title: String,
    subtitle: String,
    connected: Boolean,
    lightOn: Boolean,
    locked: Boolean,
    legalMode: Boolean,
    voiceOn: Boolean,
    recording: Boolean,
    onHorn: () -> Unit,
    onToggleLight: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleLegal: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleRecord: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onRecordingScreen: () -> Unit,
    onMetricClick: (String) -> Unit,
) {
    val c = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        DashboardTopBar(c, title, subtitle, connected, onScan, onSettings)

        // Speed gauge — fills the upper flexible area.
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            SpeedGauge(d.speed, max = 60f, pwm = d.pwm, charging = d.charging, c = c)
        }

        // 6-tile metric grid (2 columns x 3 rows).
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            defaultMetrics.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        Box(Modifier.weight(1f)) {
                            MetricTile(c, m, d, history) { onMetricClick(m.key) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // 6-button action grid (3 columns x 2 rows).
        val actions = listOf(
            Triple(RideAction("HORN", "►", { cc: AppThemeColors -> cc.primary }), false) { onHorn() },
            Triple(RideAction("LIGHT", "☀", { cc: AppThemeColors -> cc.statusWarn }), lightOn) { onToggleLight() },
            Triple(RideAction("VOICE", "♪", { cc: AppThemeColors -> cc.primary }), voiceOn) { onToggleVoice() },
            Triple(RideAction("LEGAL", "◈", { cc: AppThemeColors -> cc.primary }), legalMode) { onToggleLegal() },
            Triple(RideAction("LOCK", "⚿", { cc: AppThemeColors -> cc.statusDanger }), locked) { onToggleLock() },
            Triple(RideAction("REC", "●", { cc: AppThemeColors -> cc.statusDanger }), recording) { onToggleRecord() },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            actions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (action, active, onClick) ->
                        Box(Modifier.weight(1f)) {
                            ActionButton(c, action, active, connected, onClick)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Bottom info row: odometer · firmware/about · trips.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ODO ${d.totalDistance.f1()} km", color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                if (connected) subtitle else "demo · shared Compose",
                color = c.textDisabled, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text(
                "Trips ›", color = c.primary, fontSize = 11.sp, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).clickable { onRecordingScreen() },
            )
        }
    }
}

@Composable
private fun DashboardTopBar(
    c: AppThemeColors,
    title: String,
    subtitle: String,
    connected: Boolean,
    onScan: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(c.topBar).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (connected) c.connectionActive else c.connectionIdle)
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, color = c.textSecondary, fontSize = 11.sp, maxLines = 1)
        }
        TopBarAction(if (connected) "Disconnect" else "Scan", if (connected) c.statusDanger else c.primary, onScan)
        Spacer(Modifier.size(8.dp))
        TopBarAction("Settings", c.primary, onSettings)
    }
}

@Composable
private fun TopBarAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun MetricTile(c: AppThemeColors, m: Metric, d: WheelData, history: List<WheelData>, onClick: () -> Unit) {
    val color = m.color(c)
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).background(c.tileBackground).clickable { onClick() },
    ) {
        // Background sparkline of this metric's recent history.
        val series = history.map { m.value(it) }
        if (series.size >= 2) {
            Sparkline(series, color.copy(alpha = 0.35f), Modifier.fillMaxSize().padding(top = 22.dp))
        }
        Text(
            m.label, color = c.tileLabel, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 7.dp),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(m.text(d), color = color, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(3.dp))
            Text(m.unit, color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun ActionButton(
    c: AppThemeColors,
    action: RideAction,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = action.activeColor(c)
    val bg = if (active) accent.copy(alpha = 0.18f) else c.tileBackground
    val fg = when {
        !enabled -> c.textDisabled
        active -> accent
        else -> c.textSecondary
    }
    Column(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .clickable(enabled = enabled) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(action.glyph, color = fg, fontSize = 20.sp)
        Spacer(Modifier.height(3.dp))
        Text(action.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun SpeedGauge(speed: Float, max: Float, pwm: Float, charging: Boolean, c: AppThemeColors) {
    val frac = (speed / max).coerceIn(0f, 1f)
    val arcColor = when {
        pwm > 85f -> c.gaugeDanger
        pwm > 65f -> c.gaugeWarn
        else -> c.gaugeFill
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1.15f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(24.dp)) {
            val stroke = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round)
            val inset = stroke.width
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(c.gaugeTrack, 135f, 270f, false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(arcColor, 135f, 270f * frac, false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speed.f1(), color = c.textPrimary, fontSize = 58.sp, fontWeight = FontWeight.Bold)
            Text("km/h", color = c.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                if (charging) "CHARGING" else "PWM ${pwm.f0()}%",
                color = if (charging) c.statusGood else arcColor,
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Minimal area/line sparkline normalised to its own min/max. */
@Composable
private fun Sparkline(series: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val lo = series.minOrNull() ?: return@Canvas
        val hi = series.maxOrNull() ?: return@Canvas
        val span = (hi - lo).takeIf { it > 0.0001f } ?: 1f
        val dx = if (series.size > 1) size.width / (series.size - 1) else size.width
        val path = Path()
        series.forEachIndexed { i, v ->
            val x = dx * i
            val y = size.height - ((v - lo) / span) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
}

internal fun Float.f1(): String {
    val r = (this * 10).roundToInt()
    val neg = r < 0
    val a = if (neg) -r else r
    return "${if (neg) "-" else ""}${a / 10}.${a % 10}"
}

internal fun Float.f0(): String = this.roundToInt().toString()
