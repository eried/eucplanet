package com.eried.eucplanet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

private enum class Screen { Connect, Dashboard }

/** Discovered wheels. On real hardware this list comes from the shared BleScanner
 *  (CoreBluetooth on iOS); the Simulator has no Bluetooth, so we show a sample
 *  of the supported families to exercise the connect → dashboard flow. */
private data class Wheel(val name: String, val brand: String, val rssi: Int)

private val sampleWheels = listOf(
    Wheel("Adventure-V14-50S", "InMotion", -52),
    Wheel("KS-S22-8F3A", "KingSong", -61),
    Wheel("Sherman-S-LK19", "Veteran", -67),
    Wheel("Begode_Master_4C", "Begode", -74),
)

/** Shared EUC Planet app shell — state-based navigation (no nav lib for v1),
 *  the real theme, and shared Compose screens. Renders identically on iOS/Android. */
@Composable
fun App() {
    EucPlanetTheme(colors = BuiltInThemes.dark.colors) {
        var screen by remember { mutableStateOf(Screen.Connect) }
        var connected by remember { mutableStateOf<Wheel?>(null) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.appColors.appBackground) {
            when (screen) {
                Screen.Connect -> ConnectScreen(onConnect = { connected = it; screen = Screen.Dashboard })
                Screen.Dashboard -> DashboardScreen(
                    wheel = connected,
                    onDisconnect = { screen = Screen.Connect },
                )
            }
        }
    }
}

@Composable
private fun ConnectScreen(onConnect: (Wheel) -> Unit) {
    val c = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("EUC Planet", color = c.primary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Select a wheel", color = c.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.statusGood))
            Spacer(Modifier.width(6.dp))
            Text("scanning…", color = c.textDisabled, fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        sampleWheels.forEach { w ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.tileBackground)
                    .clickable { onConnect(w) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(w.brand, color = c.textSecondary, fontSize = 12.sp)
                }
                Text("${w.rssi} dBm", color = c.textDisabled, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text("Connect ›", color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Simulator has no Bluetooth — real scan uses the shared CoreBluetooth transport on device.",
            color = c.textDisabled, fontSize = 10.sp,
        )
    }
}

@Composable
private fun DashboardScreen(wheel: Wheel?, onDisconnect: () -> Unit) {
    val c = MaterialTheme.appColors
    val scope = rememberCoroutineScope()
    val model = remember { DashboardModel(scope) }
    val d by model.data.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(wheel?.name ?: "EUC Planet", color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(wheel?.brand ?: "demo", color = c.primary, fontSize = 12.sp)
            }
            Text("Disconnect", color = c.statusDanger, fontSize = 13.sp,
                modifier = Modifier.clickable { onDisconnect() })
        }
        Spacer(Modifier.height(8.dp))
        SpeedGauge(d.speed, max = 60f, pwm = d.pwm, c = c)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(c, "BATTERY", d.batteryPercent.toString(), "%", c.metricBattery)
            Tile(c, "VOLTAGE", d.voltage.f1(), "V", c.metricVoltage)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(c, "TEMP", d.maxTemperature.f0(), "°C", c.metricTemp)
            Tile(c, "CURRENT", d.current.f1(), "A", c.metricAccel)
        }
        Spacer(Modifier.height(14.dp))
        Text("trip ${d.tripDistance.f1()} km · shared Compose · iOS", color = c.textDisabled, fontSize = 11.sp)
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
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 20f, cap = StrokeCap.Round)
            val inset = 18f
            val sz = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val off = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(c.gaugeTrack, 135f, 270f, false, topLeft = off, size = sz, style = stroke)
            drawArc(arcColor, 135f, 270f * frac, false, topLeft = off, size = sz, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speed.f1(), color = c.textPrimary, fontSize = 50.sp, fontWeight = FontWeight.Bold)
            Text("km/h", color = c.textSecondary, fontSize = 14.sp)
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
