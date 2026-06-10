package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * [DashboardModel] telemetry flow (simulated until the BLE transport lands) and
 * the real EUC Planet theme tokens.
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
                Text("EUC Planet", color = c.primary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("shared Compose · live telemetry · iOS", color = c.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(c, "SPEED", d.speed.f1(), "km/h", c.textPrimary)
                    Tile(c, "BATTERY", d.batteryPercent.toString(), "%", c.metricBattery)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(c, "VOLTAGE", d.voltage.f1(), "V", c.metricVoltage)
                    Tile(c, "TEMP", d.maxTemperature.f0(), "°C", c.metricTemp)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(c, "CURRENT", d.current.f1(), "A", c.metricAccel)
                    Tile(c, "PWM", d.pwm.f0(), "%", if (d.pwm > 80f) c.statusDanger else c.statusGood)
                }
                Spacer(Modifier.height(20.dp))
                Text("trip ${d.tripDistance.f1()} km", color = c.textDisabled, fontSize = 11.sp)
            }
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
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = valueColor, fontSize = 30.sp, fontWeight = FontWeight.Bold)
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
