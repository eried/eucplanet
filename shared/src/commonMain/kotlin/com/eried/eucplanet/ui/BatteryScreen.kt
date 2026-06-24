package com.eried.eucplanet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.ChargeEstimator
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.UnitFormat
import kotlin.math.roundToInt

/**
 * Battery / charging monitor — the iOS take on Android's Charging screen, driven by
 * the SHARED [ChargeEstimator]. Shows the live %, charge state, smoothed rate, ETA
 * to the 80 % target and to 100 %, session energy, voltage and temperature, plus a
 * battery-% history graph.
 */
@Composable
internal fun BatteryScreen(
    state: ChargeEstimator.State,
    voltage: Float,
    maxTempC: Float,
    batteryHistory: List<Float>,
    unitTemp: String,
    connected: Boolean,
    onBack: () -> Unit,
    bms: com.eried.eucplanet.data.BmsState = com.eried.eucplanet.data.BmsState(),
) {
    val c = MaterialTheme.appColors
    val pct = state.percent.coerceIn(0f, 100f)
    val full = pct >= 99.5f
    val statusText = when {
        !connected -> "Not connected"
        full -> "Charged"
        state.charging -> "Charging"
        else -> "Not charging"
    }
    val statusColor = when {
        !connected -> c.textDisabled
        state.charging || full -> c.statusGood
        else -> c.textSecondary
    }

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Battery", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${pct.roundToInt()}", color = c.textPrimary, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Text("%", color = c.textSecondary, fontSize = 24.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
                Spacer(Modifier.weight(1f))
                Text(statusText, color = statusColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
            }
            // Battery bar.
            Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceVariant)) {
                Box(
                    Modifier.fillMaxWidth(pct / 100f).height(22.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (state.charging || full) c.statusGood else c.gaugeFill),
                )
            }
            if (state.charging && state.addedPercent > 0.1f) {
                Spacer(Modifier.height(6.dp))
                Text("+${state.addedPercent.roundToInt()}% this session", color = c.statusGood, fontSize = 12.sp)
            }

            if (batteryHistory.size >= 2) {
                Spacer(Modifier.height(16.dp))
                Text("BATTERY HISTORY", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(10.dp)).background(c.tileBackground).padding(8.dp)) {
                    BatterySpark(batteryHistory, c.gaugeFill, Modifier.fillMaxSize())
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("CHARGING", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            StatRow(c, "Charge rate", if (state.charging && state.warmedUp) "${oneDp(state.ratePctPerMin)} %/min" else "--")
            StatRow(c, "Time to 80%", etaText(state.minutesToTarget))
            StatRow(c, "Time to 100%", etaText(state.minutesToFull))
            if (state.powerW != null) StatRow(c, "Charge power", "${state.powerW} W")
            StatRow(c, "Energy this session", "${state.energyWh.roundToInt()} Wh")

            Spacer(Modifier.height(12.dp))
            Text("PACK", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            StatRow(c, "Voltage", "${oneDp(voltage)} V")
            if (state.current != 0f) StatRow(c, "Current", "${oneDp(state.current)} A")
            StatRow(c, "Temperature", "${UnitFormat.temperature(maxTempC, unitTemp).roundToInt()} ${UnitFormat.tempLabel(unitTemp)}")
            if (state.hasPacks) {
                StatRow(c, "Pack 1", "${state.battery1.roundToInt()} %")
                StatRow(c, "Pack 2", "${state.battery2.roundToInt()} %")
            }

            // Voltage + temperature session curves (Android's Voltage / Temp tabs).
            if (state.voltageHistory.size >= 2) {
                Spacer(Modifier.height(16.dp))
                Text("VOLTAGE", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(c.tileBackground).padding(8.dp)) {
                    BatterySpark(state.voltageHistory, c.metricVoltage, Modifier.fillMaxSize())
                }
            }
            if (state.tempHistory.size >= 2) {
                Spacer(Modifier.height(12.dp))
                Text("TEMPERATURE", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(c.tileBackground).padding(8.dp)) {
                    BatterySpark(state.tempHistory, c.gaugeWarn, Modifier.fillMaxSize())
                }
            }

            // Smart-BMS per-cell view (Veteran/Leaperkim families) — Android's Cells tab.
            if (bms.hasCells) {
                Spacer(Modifier.height(16.dp))
                Text("CELLS", color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                bms.packs.forEach { pack -> CellsPack(c, pack, unitTemp) }
            }

            if (!connected) {
                Spacer(Modifier.height(12.dp))
                Text("Connect a wheel to see live charging.", color = c.textDisabled, fontSize = 11.sp)
            } else if (!state.warmedUp && state.charging) {
                Spacer(Modifier.height(12.dp))
                Text("Estimating… give it about a minute for a steady prediction.", color = c.textDisabled, fontSize = 11.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun oneDp(v: Float): String {
    val r = (v * 10).roundToInt()
    val neg = r < 0
    val a = if (neg) -r else r
    return "${if (neg) "-" else ""}${a / 10}.${a % 10}"
}

private fun etaText(minutes: Float?): String {
    if (minutes == null || minutes <= 0f || minutes > 6000f) return "--"
    val m = minutes.roundToInt()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min"
}

private fun twoDp(v: Float): String {
    val r = (v * 100).roundToInt()
    return "${r / 100}.${(r % 100).toString().padStart(2, '0')}"
}

/** One smart-BMS pack: balance summary + per-cell voltage grid (deviation-colored) + temps. */
@Composable
private fun CellsPack(c: AppThemeColors, pack: com.eried.eucplanet.data.BmsState.PackState, unitTemp: String) {
    val cells = pack.knownCells
    if (cells.isEmpty()) return
    val mean = cells.map { it.second }.sum() / cells.size
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Pack ${pack.packIndex + 1}", color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        pack.cellDeltaMv?.let { d -> Text("Δ $d mV", color = if (d > 50) c.gaugeWarn else c.statusGood, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
    }
    Text(
        "${pack.cellCount} cells · ${twoDp(pack.minCellV ?: 0f)}–${twoDp(pack.maxCellV ?: 0f)} V" + (pack.currentA?.let { " · ${oneDp(it)} A" } ?: ""),
        color = c.textSecondary, fontSize = 11.sp,
    )
    Spacer(Modifier.height(6.dp))
    cells.chunked(6).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { (idx, v) ->
                val dev = kotlin.math.abs(v - mean)
                val col = when { dev > 0.05f -> c.statusDanger; dev > 0.02f -> c.gaugeWarn; else -> c.statusGood }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(c.tileBackground).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("#${idx + 1}", color = c.textDisabled, fontSize = 8.sp)
                    Text(twoDp(v), color = col, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (pack.temperaturesC.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "BMS temps: " + pack.temperaturesC.joinToString(" ") { "${UnitFormat.temperature(it, unitTemp).roundToInt()}°" },
            color = c.textSecondary, fontSize = 11.sp,
        )
    }
}

@Composable
private fun StatRow(c: AppThemeColors, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(1.dp))
}

@Composable
private fun BatterySpark(series: List<Float>, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Canvas(modifier) {
        val lo = (series.minOrNull() ?: 0f)
        val hi = (series.maxOrNull() ?: 100f)
        val span = (hi - lo).takeIf { it > 0.5f } ?: 1f
        val dx = if (series.size > 1) size.width / (series.size - 1) else size.width
        val path = Path()
        series.forEachIndexed { i, v ->
            val x = dx * i
            val y = size.height - ((v - lo) / span) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}
