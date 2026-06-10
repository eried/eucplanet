package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.ble.WheelSession
import com.eried.eucplanet.ble.transport.BleDevice
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A demo wheel for the Simulator (no Bluetooth), driving the simulated dashboard. */
private data class Wheel(val name: String, val brand: String, val rssi: Int)

private val sampleWheels = listOf(
    Wheel("Adventure-V14-50S", "InMotion", -52),
    Wheel("KS-S22-8F3A", "KingSong", -61),
    Wheel("Sherman-S-LK19", "Veteran", -67),
    Wheel("Begode_Master_4C", "Begode", -74),
)

private enum class Route { Dashboard, Settings, Recording }

/** Screenshot harness: maps the `EUC_DEMO_SCREEN` env var (see [debugStartScreen])
 *  to a screen, so a headless Simulator run can auto-open a demo ride on any
 *  screen. Null (the default) means the normal Scan-first flow. */
private fun debugAutoDemoRoute(): Route? = when (debugStartScreen()?.lowercase()) {
    "dashboard" -> Route.Dashboard
    "settings" -> Route.Settings
    "recording", "recordings" -> Route.Recording
    else -> null
}

/**
 * Shared EUC Planet app shell — a multi-screen port of the Android app: Scan →
 * Dashboard (live telemetry, metric grid, action grid) ↔ Settings ↔ Recordings.
 * On a real iPhone the Scan screen lists nearby wheels from the CoreBluetooth
 * transport and tapping one opens a live [WheelSession]; the Simulator (no
 * Bluetooth) offers demo wheels driven by a simulated telemetry flow. Identical
 * on iOS and Android.
 */
@Composable
fun App() {
    EucPlanetTheme(colors = BuiltInThemes.dark.colors) {
        val scope = rememberCoroutineScope()
        val connectModel = remember { ConnectModel(scope) }
        var session by remember { mutableStateOf<WheelSession?>(null) }
        var demoModel by remember { mutableStateOf<DashboardModel?>(null) }
        var demoWheel by remember { mutableStateOf<Wheel?>(null) }
        var connectingName by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var route by remember { mutableStateOf(Route.Dashboard) }

        LaunchedEffect(Unit) {
            debugAutoDemoRoute()?.let { r ->
                demoWheel = sampleWheels[0]
                demoModel = DashboardModel(scope)
                route = r
            }
        }

        fun leaveRide() {
            session?.stop()
            session = null
            demoModel = null
            demoWheel = null
            route = Route.Dashboard
        }

        val inRide = session != null || demoModel != null

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.appColors.appBackground) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when {
                !inRide -> ScanScreen(
                    connectModel = connectModel,
                    connectingName = connectingName,
                    error = error,
                    onConnectReal = { dev ->
                        error = null
                        connectingName = dev.name ?: dev.address
                        scope.launch {
                            try {
                                session = connectModel.connect(dev)
                                route = Route.Dashboard
                            } catch (e: Throwable) {
                                error = e.message ?: "connection failed"
                            } finally {
                                connectingName = null
                            }
                        }
                    },
                    onConnectDemo = { w ->
                        demoWheel = w
                        demoModel = DashboardModel(scope)
                        route = Route.Dashboard
                    },
                )
                route == Route.Settings -> SettingsScreen(
                    connected = session != null,
                    onApplyMaxSpeed = { tiltback, alarm ->
                        session?.let { s -> scope.launch { s.setMaxSpeed(tiltback, alarm) } }
                    },
                    onBack = { route = Route.Dashboard },
                )
                route == Route.Recording -> RecordingScreen(onBack = { route = Route.Dashboard })
                else -> DashboardRoute(
                    session = session,
                    demoModel = demoModel,
                    demoTitle = demoWheel?.name ?: "EUC Planet",
                    demoBrand = demoWheel?.brand ?: "demo",
                    onScan = { leaveRide() },
                    onSettings = { route = Route.Settings },
                    onRecording = { route = Route.Recording },
                )
            }
            }
        }
    }
}

@Composable
private fun DashboardRoute(
    session: WheelSession?,
    demoModel: DashboardModel?,
    demoTitle: String,
    demoBrand: String,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onRecording: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val live = session != null
    val dataFlow = session?.data ?: demoModel!!.data
    val d by dataFlow.collectAsState()
    val fallbackModel = remember { MutableStateFlow<String?>(null) }
    val liveModel by (session?.modelName ?: fallbackModel).collectAsState()

    // Recent telemetry history backing the metric-tile sparklines.
    val history = remember { mutableStateListOf<WheelData>() }
    LaunchedEffect(d) {
        history.add(d)
        if (history.size > 48) history.removeAt(0)
    }

    var lightOn by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var legalMode by remember { mutableStateOf(false) }
    var voiceOn by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }

    val title = if (live) (liveModel ?: session!!.brand) else demoTitle
    val subtitle = if (live) "${session!!.brand} · live" else "$demoBrand · demo"

    DashboardScreen(
        d = d,
        history = history,
        title = title,
        subtitle = subtitle,
        connected = live,
        lightOn = lightOn,
        locked = locked,
        legalMode = legalMode,
        voiceOn = voiceOn,
        recording = recording,
        onHorn = { session?.let { s -> scope.launch { s.horn() } } },
        onToggleLight = {
            lightOn = !lightOn
            session?.let { s -> scope.launch { s.setLight(lightOn) } }
        },
        onToggleVoice = { voiceOn = !voiceOn },
        onToggleLegal = { legalMode = !legalMode },
        onToggleLock = {
            locked = !locked
            session?.let { s -> scope.launch { s.setLock(locked) } }
        },
        onToggleRecord = { recording = !recording },
        onScan = onScan,
        onSettings = onSettings,
        onRecordingScreen = onRecording,
    )
}

@Composable
private fun ScanScreen(
    connectModel: ConnectModel,
    connectingName: String?,
    error: String?,
    onConnectReal: (BleDevice) -> Unit,
    onConnectDemo: (Wheel) -> Unit,
) {
    val c = MaterialTheme.appColors
    val devices by connectModel.devices.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
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

        if (devices.isNotEmpty()) {
            Text("NEARBY", color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            devices.forEach { dev ->
                WheelRow(c, dev.name ?: "(unnamed)", dev.address, dev.rssi) { onConnectReal(dev) }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("DEMO", color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        sampleWheels.forEach { w ->
            WheelRow(c, w.name, w.brand, w.rssi) { onConnectDemo(w) }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Simulator has no Bluetooth — real scan uses the shared CoreBluetooth transport on device.",
            color = c.textDisabled, fontSize = 10.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WheelRow(
    c: com.eried.eucplanet.ui.theme.AppThemeColors,
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
