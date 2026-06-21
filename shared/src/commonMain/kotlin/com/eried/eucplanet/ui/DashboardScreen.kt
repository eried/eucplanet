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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.RideAlarm
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.about.AboutDialog
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.welcome.coachmark
import com.eried.eucplanet.util.UnitFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One metric tile's spec: how to pull its value + unit + accent from [WheelData]. */
internal class Metric(
    val key: String,
    val label: String,
    val unit: String,
    val color: (AppThemeColors) -> Color,
    val value: (WheelData) -> Float,
    val text: (WheelData) -> String,
)

/** The 6-tile grid, mirroring the Android dashboard's BATTERY / TEMP / VOLTAGE /
 *  CURRENT / LOAD / TRIP layout. Also the lookup for MetricDetail. TEMP and TRIP
 *  are unit-aware (converted to the rider's chosen display unit); the rest are
 *  unit-agnostic. The value/text lambdas convert too, so sparklines + MIN/MAX
 *  read in the displayed unit. */
internal fun metricsFor(unitDistance: String, unitTemp: String): List<Metric> = listOf(
    Metric("battery", "BATTERY", "%", { it.metricBattery }, { it.batteryPercent.toFloat() }, { it.batteryPercent.toString() }),
    Metric("temp", "TEMP", UnitFormat.tempLabel(unitTemp), { it.metricTemp }, { UnitFormat.temperature(it.maxTemperature, unitTemp) }, { UnitFormat.temperature(it.maxTemperature, unitTemp).f0() }),
    Metric("voltage", "VOLTAGE", "V", { it.metricVoltage }, { it.voltage }, { it.voltage.f1() }),
    Metric("current", "CURRENT", "A", { it.metricAccel }, { it.current }, { it.current.f1() }),
    Metric("load", "LOAD", "%", { it.metricPosition }, { it.pwm }, { it.pwm.f0() }),
    Metric("trip", "TRIP", UnitFormat.distanceLabel(unitDistance), { it.statusGood }, { UnitFormat.distance(it.tripDistance, unitDistance) }, { UnitFormat.distance(it.tripDistance, unitDistance).f1() }),
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
    gaugeMax: Float,
    orangeThresholdPct: Int,
    redThresholdPct: Int,
    unitSpeed: String,
    unitDistance: String,
    unitTemp: String,
    columns: Int,
    statCorners: Boolean,
    disconnected: Boolean = false,
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
    onStudio: () -> Unit = {},
    onMap: () -> Unit = {},
) {
    val c = MaterialTheme.appColors
    var showAbout by remember { mutableStateOf(debugStartScreen()?.lowercase() == "about") }

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        DashboardTopBar(c, title, subtitle, connected, alarms.isNotEmpty(), onScan, onSettings, onRecordingScreen, onStudio, onMap)

        if (alarms.isNotEmpty()) AlarmBanner(c, alarms)

        // Speed gauge — fills the upper flexible area. Tap to open the speed
        // history / detail, exactly like Android (onNavigateToMetric("SPEED")).
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).coachmark("speed").clickable { onMetricClick("speed") },
            contentAlignment = Alignment.Center,
        ) {
            SpeedGauge(d.speed, max = gaugeMax, unitSpeed = unitSpeed, pwm = d.pwm, charging = d.charging, band = gaugeBand, orangeThresholdPct = orangeThresholdPct, redThresholdPct = redThresholdPct, c = c)
        }

        // Metric grid — honours the rider's column count (2 or 3) from Dashboard
        // settings. With a non-full last row, the trailing Spacer keeps tiles the
        // same width as full rows instead of stretching them.
        val cols = columns.coerceIn(1, 3)
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).coachmark("metrics")) {
            metricsFor(unitDistance, unitTemp).chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        Box(Modifier.weight(1f)) {
                            MetricTile(c, m, d, history, statCorners) { onMetricClick(m.key) }
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // 6-button Material-icon action grid (3 columns x 2 rows).
        // Wheel-bound controls (Horn / Light / Legal / Lock) need a connected wheel
        // (or demo) — dim them when disconnected, exactly like Android. Voice (speak
        // current stats) and Rec (trip recording) work without a wheel, so Android
        // leaves them enabled at all times — match that here.
        val ctl = !disconnected
        val actions = listOf(
            ActionSpec(RideAction("Horn", Icons.Filled.Campaign) { it.primary }, false, ctl, onHorn),
            ActionSpec(RideAction("Light", Icons.Filled.FlashlightOn) { it.statusWarn }, lightOn, ctl, onToggleLight),
            ActionSpec(RideAction("Voice", Icons.Filled.RecordVoiceOver) { it.primary }, voiceOn, true, onToggleVoice),
            ActionSpec(RideAction("Legal", Icons.Filled.Shield) { it.primary }, legalMode, ctl, onToggleLegal),
            ActionSpec(RideAction("Lock", if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen) { it.statusDanger }, locked, ctl, onToggleLock),
            ActionSpec(RideAction("Rec", Icons.Filled.FiberManualRecord) { it.statusDanger }, recording, true, onToggleRecord),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).coachmark("actions")) {
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
            Text("ODO ${UnitFormat.distance(d.totalDistance, unitDistance).f1()} ${UnitFormat.distanceLabel(unitDistance)}", color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                if (disconnected) "offline" else if (connected) "live" else "demo",
                color = if (connected) c.statusGood else c.textDisabled, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text(
                "EUC Planet 0.1", color = c.primary, fontSize = 11.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).coachmark("version").clip(RoundedCornerShape(6.dp)).clickable { showAbout = true }.padding(vertical = 2.dp),
            )
        }
    }

    if (showAbout) {
        AboutDialog(connected = connected, connectedTitle = title, onDismiss = { showAbout = false })
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
    onStudio: () -> Unit = {},
    onMap: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(c.topBar).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 12.dp),
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
        if (hasAlarm) IconBtn(Icons.Filled.Warning, "Alarm", c.statusDanger, {})
        IconBtn(Icons.Filled.Place, "Map", c.textSecondary, onMap, Modifier.coachmark("map"))
        IconBtn(Icons.Filled.Videocam, "Overlay Studio", c.textSecondary, onStudio, Modifier.coachmark("studio"))
        IconBtn(Icons.Filled.History, "Recordings", c.textSecondary, onRecordings)
        IconBtn(
            if (connected) Icons.Filled.Bluetooth else Icons.Filled.BluetoothSearching,
            if (connected) "Disconnect" else "Scan",
            if (connected) c.statusGood else c.primary,
            onScan,
            Modifier.coachmark("bluetooth"),
        )
        IconBtn(Icons.Filled.Settings, "Settings", c.primary, onSettings)
    }
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(CircleShape).clickable { onClick() }.padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MetricTile(c: AppThemeColors, m: Metric, d: WheelData, history: List<WheelData>, showCorners: Boolean, onClick: () -> Unit) {
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
        // MIN/MAX corner stats over the visible history, like the Android tiles
        // (gated by the Dashboard "Show MIN / MAX corner stats" toggle).
        if (showCorners && mx != null && mn != null && series.size >= 3) {
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

private fun statText(v: Float, unit: String): String =
    if (unit == "%" || unit.startsWith("°") || unit == "K") v.f0() else v.f1()

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
internal fun SpeedGauge(speed: Float, max: Float, unitSpeed: String, pwm: Float, charging: Boolean, band: Boolean, orangeThresholdPct: Int, redThresholdPct: Int, c: AppThemeColors) {
    // frac is a ratio, so it's unit-invariant — only the readout + label convert.
    val frac = (speed / max).coerceIn(0f, 1f)
    // Colour rule matches Android: when the band is on, the tier is driven by how
    // close speed is to max (orange/red at the configured % of the arc) — NOT by PWM.
    val orangeFrac = (orangeThresholdPct / 100f).coerceIn(0.05f, 0.95f)
    val redFrac = (redThresholdPct / 100f).coerceIn(orangeFrac + 0.04f, 0.95f)
    val arcColor = when {
        band && frac >= redFrac -> c.gaugeDanger
        band && frac >= orangeFrac -> c.gaugeWarn
        else -> c.gaugeFill
    }
    val tickColor = c.textDisabled
    val measurer = rememberTextMeasurer()
    // Numeric scale labels around the dial (0 .. max in the display unit), like Android.
    val displayMax = UnitFormat.speed(max, unitSpeed).roundToInt()
    val step = (displayMax / 3).coerceAtLeast(5)
    val scaleLabels = listOf(0, step, step * 2, displayMax)
    val startAngle = 140f
    val sweepTotal = 260f
    val rad = (PI / 180f).toFloat()

    // fillMaxSize + a minDimension-based circle means the gauge can't get squished
    // when the column squeezes it — it just scales to the available square.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val dim = size.minDimension
            val arcThickness = dim * 0.07f
            val arcRadius = dim / 2f - arcThickness - dim * 0.13f
            val center = Offset(size.width / 2f, size.height / 2f)
            val tl = Offset(center.x - arcRadius, center.y - arcRadius)
            val sz = Size(arcRadius * 2, arcRadius * 2)
            val stroke = Stroke(width = arcThickness, cap = StrokeCap.Round)

            drawArc(c.gaugeTrack, startAngle, sweepTotal, false, topLeft = tl, size = sz, style = stroke)
            // Thin threshold colour band behind the arc (safe → warn → danger).
            if (band) {
                val bandTh = arcThickness * 0.4f
                val bandR = arcRadius + arcThickness * 0.55f + bandTh * 0.5f
                val bandTl = Offset(center.x - bandR, center.y - bandR)
                val bandSz = Size(bandR * 2, bandR * 2)
                drawArc(c.gaugeWarn.copy(alpha = 0.6f), startAngle + sweepTotal * orangeFrac, sweepTotal * (redFrac - orangeFrac), false, topLeft = bandTl, size = bandSz, style = Stroke(bandTh))
                drawArc(c.gaugeDanger.copy(alpha = 0.6f), startAngle + sweepTotal * redFrac, sweepTotal * (1f - redFrac), false, topLeft = bandTl, size = bandSz, style = Stroke(bandTh))
            }
            if (frac > 0.001f) drawArc(arcColor, startAngle, sweepTotal * frac, false, topLeft = tl, size = sz, style = stroke)

            // Tick marks just outside the arc (major every 8th, minor every 4th of 24).
            val tickOuter = arcRadius + arcThickness * 0.75f
            val tickInner = arcRadius + arcThickness * 0.1f
            for (i in 0..24) {
                val major = i % 8 == 0
                if (!major && i % 4 != 0) continue
                val a = (startAngle + sweepTotal * i / 24f) * rad
                drawLine(
                    if (major) tickColor else tickColor.copy(alpha = 0.4f),
                    Offset(center.x + tickInner * cos(a), center.y + tickInner * sin(a)),
                    Offset(center.x + tickOuter * cos(a), center.y + tickOuter * sin(a)),
                    strokeWidth = if (major) 2.5f else 1.2f,
                )
            }
            // Numeric scale labels outside the ticks.
            val labelR = arcRadius + arcThickness + dim * 0.075f
            scaleLabels.forEachIndexed { idx, label ->
                val a = (startAngle + sweepTotal * idx / (scaleLabels.size - 1)) * rad
                val m = measurer.measure("$label", style = TextStyle(fontSize = (dim * 0.045f).sp, color = tickColor))
                drawText(m, topLeft = Offset(center.x + labelR * cos(a) - m.size.width / 2f, center.y + labelR * sin(a) - m.size.height / 2f))
            }
        }
        // Centre readout — number / unit / PWM, overlaid on the dial centre.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(UnitFormat.speed(speed, unitSpeed).f1(), color = c.textPrimary, fontSize = 54.sp, fontWeight = FontWeight.Bold)
            Text(UnitFormat.speedLabel(unitSpeed), color = c.textSecondary, fontSize = 13.sp)
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
