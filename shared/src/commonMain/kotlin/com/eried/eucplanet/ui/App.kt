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
import com.eried.eucplanet.ble.WheelSession
import com.eried.eucplanet.ble.transport.BleDevice
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A demo wheel for the Simulator (no Bluetooth), driving the simulated dashboard. */
private data class Wheel(val name: String, val brand: String, val rssi: Int)

private val sampleWheels = listOf(
    Wheel("Adventure-V14-50S", "InMotion", -52),
    Wheel("KS-S22-8F3A", "KingSong", -61),
    Wheel("Sherman-S-LK19", "Veteran", -67),
    Wheel("Begode_Master_4C", "Begode", -74),
)

/**
 * Shared EUC Planet app shell. State-based navigation (no nav lib for v1), the
 * real theme, and shared Compose screens. On a real iPhone the Connect screen
 * lists nearby wheels from the CoreBluetooth transport and tapping one opens a
 * live [WheelSession] dashboard; the Simulator (no Bluetooth) shows demo wheels
 * driven by a simulated telemetry flow. Renders identically on iOS/Android.
 */
@Composable
fun App() {
    EucPlanetTheme(colors = BuiltInThemes.dark.colors) {
        val scope = rememberCoroutineScope()
        val connectModel = remember { ConnectModel(scope) }
        var session by remember { mutableStateOf<WheelSession?>(null) }
        var demo by remember { mutableStateOf<Wheel?>(null) }
        var connectingName by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.appColors.appBackground) {
            val s = session
            val d = demo
            when {
                s != null -> LiveDashboardScreen(s, onDisconnect = { s.stop(); session = null })
                d != null -> DemoDashboardScreen(d, onDisconnect = { demo = null })
                else -> ConnectScreen(
                    connectModel = connectModel,
                    connectingName = connectingName,
                    error = error,
                    onConnectReal = { dev ->
                        error = null
                        connectingName = dev.name ?: dev.address
                        scope.launch {
                            try {
                                session = connectModel.connect(dev)
                            } catch (e: Throwable) {
                                error = e.message ?: "connection failed"
                            } finally {
                                connectingName = null
                            }
                        }
                    },
                    onConnectDemo = { demo = it },
                )
            }
        }
    }
}

@Composable
private fun ConnectScreen(
    connectModel: ConnectModel,
    connectingName: String?,
    error: String?,
    onConnectReal: (BleDevice) -> Unit,
    onConnectDemo: (Wheel) -> Unit,
) {
    val c = MaterialTheme.appColors
    val devices by connectModel.devices.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("EUC Planet", color = c.primary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Select a wheel", color = c.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.statusGood))
            Spacer(Modifier.width(6.dp))
            Text(
                if (connectingName != null) "connecting to $connectingName…" else "scanning…",
                color = c.textDisabled, fontSize = 12.sp,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, color = c.statusDanger, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))

        // Real peripherals from the CoreBluetooth transport (empty on Simulator).
        if (devices.isNotEmpty()) {
            Text("NEARBY", color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            devices.forEach { dev ->
                WheelRow(
                    c = c,
                    name = dev.name ?: "(unnamed)",
                    subtitle = dev.address,
                    rssi = dev.rssi,
                    onClick = { onConnectReal(dev) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("DEMO", color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        sampleWheels.forEach { w ->
            WheelRow(
                c = c,
                name = w.name,
                subtitle = w.brand,
                rssi = w.rssi,
                onClick = { onConnectDemo(w) },
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Simulator has no Bluetooth — real scan uses the shared CoreBluetooth transport on device.",
            color = c.textDisabled, fontSize = 10.sp,
        )
    }
}

@Composable
private fun WheelRow(
    c: AppThemeColors,
    name: String,
    subtitle: String,
    rssi: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.tileBackground)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.textSecondary, fontSize = 12.sp)
        }
        Text("$rssi dBm", color = c.textDisabled, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text("Connect ›", color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LiveDashboardScreen(session: WheelSession, onDisconnect: () -> Unit) {
    val d by session.data.collectAsState()
    val model by session.modelName.collectAsState()
    DashboardBody(
        d = d,
        title = model ?: session.brand,
        subtitle = "${session.brand} · live",
        onDisconnect = onDisconnect,
    )
}

@Composable
private fun DemoDashboardScreen(wheel: Wheel, onDisconnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember { DashboardModel(scope) }
    val d by model.data.collectAsState()
    DashboardBody(
        d = d,
        title = wheel.name,
        subtitle = "${wheel.brand} · demo",
        onDisconnect = onDisconnect,
    )
}

@Composable
private fun DashboardBody(d: WheelData, title: String, subtitle: String, onDisconnect: () -> Unit) {
    val c = MaterialTheme.appColors
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = c.primary, fontSize = 12.sp)
            }
            Text(
                "Disconnect", color = c.statusDanger, fontSize = 13.sp,
                modifier = Modifier.clickable { onDisconnect() },
            )
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
