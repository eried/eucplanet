package com.eried.eucplanet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.roundToInt

/**
 * Shared dashboard — renders identically on Android and iOS, driven by a live
 * [DashboardModel] telemetry flow and the real EUC Planet theme. A Canvas speed
 * gauge + metric tiles, all in commonMain Compose.
 */
@Composable
fun App() {
    EucPlanetTheme(colors = BuiltInThemes.dark.colors) {
        val c = MaterialTheme.appColors
        val scope = rememberCoroutineScope()
        val model = remember { DashboardModel(scope) }
        val d by model.data.collectAsState()
        Surface(Modifier.fillMaxSize(), color = c.appBackground) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("EUC Planet", color = c.primary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("shared Compose · live telemetry · iOS", color = c.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                SpeedGauge(d.speed, max = 60f, pwm = d.pwm, c = c)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(c, "BATTERY", d.batteryPercent.toString(), "%", c.metricBattery)
                    Tile(c, "VOLTAGE", d.voltage.f1(), "V", c.metricVoltage)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(c, "TEMP", d.maxTemperature.f0(), "°C", c.metricTemp)
                    Tile(c, "CURRENT", d.current.f1(), "A", c.metricAccel)
                }
                Spacer(Modifier.height(16.dp))
                Text("trip ${d.tripDistance.f1()} km", color = c.textDisabled, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SpeedGauge(speed: Float, max: Float, pwm: Float, c: AppThemeColors) {
    val frac = (speed / max).coerceIn(0f, 1f)
    val arcColor = when {
        pwm > 85f -> c.gaugeDanger
        pwm > 65f -> c.gaugeWarn
        else -> c.gaugeFill
    }
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 20f, cap = StrokeCap.Round)
            val inset = 18f
            val sz = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val off = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(c.gaugeTrack, 135f, 270f, false, topLeft = off, size = sz, style = stroke)
            drawArc(arcColor, 135f, 270f * frac, false, topLeft = off, size = sz, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speed.f1(), color = c.textPrimary, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text("km/h", color = c.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text("PWM ${pwm.f0()}%", color = arcColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Tile(c: AppThemeColors, label: String, value: String, unit: String, valueColor: Color) {
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.tileBackground)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(value, color = valueColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = c.textSecondary, fontSize = 12.sp)
    }
}

private fun Float.f1(): String {
    val r = (this * 10).roundToInt()
    val neg = r < 0
    val a = if (neg) -r else r
    return "${if (neg) "-" else ""}${a / 10}.${a % 10}"
}

private fun Float.f0(): String = this.roundToInt().toString()
