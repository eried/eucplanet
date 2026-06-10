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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.RideAlarm
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
internal class RideAction(
    val label: String,
    val icon: ImageVector,
    val activeColor: (AppThemeColors) -> Color,
)

/**
 * The shared EUC Planet dashboard — a faithful port of the Android live-ride
 * screen: status top bar (connection dot + name + Bluetooth/Settings/History
 * icons), the speed gauge, the 6-tile metric grid with background sparklines,
 * the 6-button Material-icon action grid, and the odometer / About / brand
 * bottom row. Drives off a single [WheelData] (live or simulated).
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
    alarms: List<RideAlarm>,
    gaugeBand: Boolean,
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
    var showAbout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        DashboardTopBar(c, title, subtitle, connected, alarms.isNotEmpty(), onScan, onSettings, onRecordingScreen)

        if (alarms.isNotEmpty()) AlarmBanner(c, alarms)

        // Speed gauge — fills the upper flexible area.
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            SpeedGauge(d.speed, max = 60f, pwm = d.pwm, charging = d.charging, band = gaugeBand, c = c)
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

        // 6-button Material-icon action grid (3 columns x 2 rows).
        val actions = listOf(
            ActionSpec(RideAction("Horn", Icons.Filled.Campaign) { it.primary }, false, true, onHorn),
            ActionSpec(RideAction("Light", Icons.Filled.FlashlightOn) { it.statusWarn }, lightOn, true, onToggleLight),
            ActionSpec(RideAction("Voice", Icons.Filled.RecordVoiceOver) { it.primary }, voiceOn, true, onToggleVoice),
            ActionSpec(RideAction("Legal", Icons.Filled.Shield) { it.primary }, legalMode, true, onToggleLegal),
            ActionSpec(RideAction("Lock", if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen) { it.statusDanger }, locked, true, onToggleLock),
            ActionSpec(RideAction("Rec", Icons.Filled.FiberManualRecord) { it.statusDanger }, recording, true, onToggleRecord),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            actions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { spec ->
                        Box(Modifier.weight(1f)) {
                            ActionButton(c, spec.action, spec.active, spec.enabled, spec.onClick)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Bottom info row: odometer · About (version) · brand — matches Android.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ODO ${d.totalDistance.f1()} km", color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                if (connected) "live" else "demo",
                color = if (connected) c.statusGood else c.textDisabled, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text(
                "EUC Planet 0.1", color = c.primary, fontSize = 11.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { showAbout = true }.padding(vertical = 2.dp),
            )
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close", color = c.primary) } },
            title = { Text("EUC Planet", color = c.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Version 0.1 — shared Compose Multiplatform (iOS / Android)", color = c.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Wheel: $title", color = c.textSecondary, fontSize = 13.sp)
                    Text(subtitle, color = c.textDisabled, fontSize = 12.sp)
                }
            },
            containerColor = c.dialog,
        )
    }
}

private class ActionSpec(
    val action: RideAction,
    val active: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun DashboardTopBar(
    c: AppThemeColors,
    title: String,
    subtitle: String,
    connected: Boolean,
    hasAlarm: Boolean,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onRecordings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(c.topBar).padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
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
        if (hasAlarm) IconBtn(Icons.Filled.Warning, "Alarm", c.statusDanger) {}
        IconBtn(Icons.Filled.History, "Recordings", c.textSecondary, onRecordings)
        IconBtn(
            if (connected) Icons.Filled.Bluetooth else Icons.Filled.BluetoothSearching,
            if (connected) "Disconnect" else "Scan",
            if (connected) c.statusGood else c.primary,
            onScan,
        )
        IconBtn(Icons.Filled.Settings, "Settings", c.primary, onSettings)
    }
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).clickable { onClick() }.padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MetricTile(c: AppThemeColors, m: Metric, d: WheelData, history: List<WheelData>, onClick: () -> Unit) {
    val color = m.color(c)
    val series = history.map { m.value(it) }
    val mx = series.maxOrNull()
    val mn = series.minOrNull()
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).background(c.tileBackground).clickable { onClick() },
    ) {
        if (series.size >= 2) {
            Sparkline(series, color.copy(alpha = 0.35f), Modifier.fillMaxSize().padding(top = 22.dp))
        }
        Text(
            m.label, color = c.tileLabel, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 7.dp),
        )
        // MIN/MAX corner stats over the visible history, like the Android tiles.
        if (mx != null && mn != null && series.size >= 3) {
            Text(
                "max ${statText(mx, m.unit)}", color = c.cornerStatLabel, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 7.dp),
            )
            Text(
                "min ${statText(mn, m.unit)}", color = c.cornerStatLabel, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
            )
        }
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

private fun statText(v: Float, unit: String): String = if (unit == "%" || unit == "°C") v.f0() else v.f1()

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
        Icon(action.icon, contentDescription = action.label, tint = fg, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(action.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun SpeedGauge(speed: Float, max: Float, pwm: Float, charging: Boolean, band: Boolean, c: AppThemeColors) {
    val frac = (speed / max).coerceIn(0f, 1f)
    val arcColor = when {
        !band -> c.gaugeFill
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
            // Threshold color band on the dial (warn 70-85%, danger 85-100%),
            // like the Android gauge, when enabled in Display settings.
            if (band) {
                val warnStart = 135f + 270f * 0.70f
                val dangerStart = 135f + 270f * 0.85f
                drawArc(c.gaugeWarn.copy(alpha = 0.5f), warnStart, dangerStart - warnStart, false, topLeft = topLeft, size = arcSize, style = stroke)
                drawArc(c.gaugeDanger.copy(alpha = 0.6f), dangerStart, (135f + 270f) - dangerStart, false, topLeft = topLeft, size = arcSize, style = stroke)
            }
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

@Composable
private fun AlarmBanner(c: AppThemeColors, alarms: List<RideAlarm>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(c.statusDanger.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = "Alarm", tint = c.statusDanger, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            alarms.joinToString("   ·   ") { it.label },
            color = c.statusDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
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
