package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.audio.createHaptics
import com.eried.eucplanet.audio.createSpeaker
import com.eried.eucplanet.ble.WheelSession
import com.eried.eucplanet.ble.transport.BleDevice
import com.eried.eucplanet.data.DiagnosticsLog
import com.eried.eucplanet.data.RideAlarm
import com.eried.eucplanet.data.AlarmEngine
import com.eried.eucplanet.data.SettingsStore
import com.eried.eucplanet.data.TripRecorder
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.activeAlarms
import com.eried.eucplanet.data.model.TripSummary
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.ThemeTokens
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.UnitFormat
import com.eried.eucplanet.util.nowEpochMillis
import com.eried.eucplanet.util.setKeepScreenOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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

private enum class Route { Dashboard, Settings, Recording, ServiceMode, ThemeEditor, AlarmEditor }

/** Ride actions that can be spoken aloud the moment they happen (gated by the
 *  matching per-event toggle in Voice settings). */
private enum class RideEvent { LockOn, LockOff, LightsOn, LightsOff, LegalOn, LegalOff, RecStart, RecStop }

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
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore() }
    val settings by settingsStore.settings.collectAsState()
    val baseColors = when (settings.theme) {
        0 -> BuiltInThemes.light.colors
        2 -> BuiltInThemes.pureBlack.colors
        else -> BuiltInThemes.dark.colors
    }
    // Theme = the selected built-in + any per-token theme-editor overrides. There
    // is NO separate accent picker — like current Android, the accent simply IS the
    // active theme's `primary` token (Android removed the standalone accent picker).
    val themeColors = if (!settings.customThemeEnabled || settings.customThemeColors.isEmpty()) baseColors
        else ThemeTokens.specs.fold(baseColors) { acc, spec ->
            settings.customThemeColors[spec.key]?.let { spec.set(acc, it.argbToColor()) } ?: acc
        }
    EucPlanetTheme(colors = themeColors) {
        val connectModel = remember { ConnectModel(scope) }
        val recorder = remember { TripRecorder() }
        val speaker = remember { createSpeaker() }
        val haptics = remember { createHaptics() }
        val alarmEngine = remember { AlarmEngine() }
        remember { DiagnosticsLog.install() } // tee adapter inspect notes into the Service Mode log
        val recording by recorder.recording.collectAsState()
        val trips by recorder.trips.collectAsState()
        var session by remember { mutableStateOf<WheelSession?>(null) }
        var demoModel by remember { mutableStateOf<DashboardModel?>(null) }
        var demoWheel by remember { mutableStateOf<Wheel?>(null) }
        var connectingName by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var route by remember { mutableStateOf(Route.Dashboard) }
        var selectedMetric by remember { mutableStateOf<String?>(null) }
        var selectedTrip by remember { mutableStateOf<TripSummary?>(null) }
        var editingAlarm by remember { mutableStateOf<AlarmRule?>(null) }

        // Screenshot harness: EUC_DEMO_SCREEN auto-opens a demo ride on a screen
        // (see debugStartScreen). Unset in normal use → Scan-first flow.
        LaunchedEffect(Unit) {
            val screen = debugStartScreen()?.lowercase() ?: return@LaunchedEffect
            demoWheel = sampleWheels[0]
            demoModel = DashboardModel(scope)
            if (settings.autoStartRecording && !settings.autoRecordStartInMotion) recorder.start() // mirror the real connect path
            when (screen) {
                "settings" -> route = Route.Settings
                "recording", "recordings" -> route = Route.Recording
                "servicemode", "service" -> route = Route.ServiceMode
                "themeeditor", "theme" -> route = Route.ThemeEditor
                "alarmeditor", "alarm" -> { editingAlarm = AlarmRule(id = 1, name = "Overspeed", threshold = 45f); route = Route.AlarmEditor }
                "metric", "metricdetail" -> { route = Route.Dashboard; selectedMetric = "voltage" }
                "tripdetail", "trip" -> {
                    val s = (0 until 48).map { i ->
                        WheelData(
                            speed = 18f + 12f * kotlin.math.sin(i * 0.25).toFloat(),
                            voltage = 92f + 3f * kotlin.math.sin(i * 0.18).toFloat(),
                            timestamp = i * 1000L,
                        )
                    }
                    selectedTrip = TripSummary("Demo ride · just now", 12.4f, 31, 24.1f, 41.6f, gpsLock = true, synced = false, csvPath = "Documents/euc_trip_1.csv", samples = s)
                    route = Route.Recording
                }
                else -> route = Route.Dashboard
            }
        }

        // Rolling telemetry history (hoisted here so it survives Dashboard ↔
        // MetricDetail navigation) collected from whichever source is active.
        val activeFlow = session?.data ?: demoModel?.data
        val history = remember { mutableStateListOf<WheelData>() }
        LaunchedEffect(activeFlow) {
            history.clear()
            alarmEngine.reset() // fresh alarm firing state per ride (no cross-ride cooldown leak)
            var lastMotionMs = 0L
            activeFlow?.collect { wd ->
                history.add(wd)
                if (history.size > 150) history.removeAt(0)
                recorder.sample(wd)
                val s = settingsStore.current
                // Alarm engine: evaluate the rules each frame and fire the actions of
                // any rule that's due (honouring per-rule cooldown / repeat-while-active).
                val toFire = alarmEngine.step(activeAlarms(wd, s.alarmRules), s.alarmRules, nowEpochMillis())
                if (toFire.isNotEmpty()) {
                    if (toFire.any { it.vibrateEnabled }) haptics.warning()
                    val spoken = toFire.filter { it.voiceEnabled }.map { it.spoken }
                    if (s.ttsEnabled && spoken.isNotEmpty()) {
                        speaker.stop()
                        speaker.rate = 0.3f + (s.speechRate / 100f) * 0.3f
                        speaker.speak(spoken.joinToString(", "))
                    }
                }
                // Auto-record motion gating (matches Android): in start-in-motion mode,
                // begin on first motion and auto-stop after autoRecordStopIdleSeconds of
                // idle. (On-connect start is handled at connect time.) 0.1 km/h = "moving".
                if (s.autoStartRecording && s.autoRecordStartInMotion) {
                    val moving = kotlin.math.abs(wd.speed) > 0.1f
                    if (moving) {
                        lastMotionMs = nowEpochMillis()
                        if (!recorder.recording.value) recorder.start()
                    } else if (recorder.recording.value) {
                        if (lastMotionMs == 0L) lastMotionMs = nowEpochMillis()
                        if (nowEpochMillis() - lastMotionMs >= s.autoRecordStopIdleSeconds * 1000L) recorder.stop()
                    }
                }
            }
        }
        val current = history.lastOrNull() ?: WheelData()
        val alarms = activeAlarms(current, settings.alarmRules)

        fun ttsRate() = 0.3f + (settings.speechRate / 100f) * 0.3f
        fun announce() {
            if (!settings.ttsEnabled) return
            val parts = mutableListOf<String>()
            if (settings.announceSpeed) parts += "Speed ${UnitFormat.speed(current.speed, settings.unitSpeed).roundToInt()}"
            if (settings.announceBattery) parts += "Battery ${current.batteryPercent} percent"
            if (settings.announceTemp) parts += "Temperature ${UnitFormat.temperature(current.maxTemperature, settings.unitTemp).roundToInt()} degrees"
            if (parts.isEmpty()) return // all periodic-report toggles off = the rider wants silence
            speaker.rate = ttsRate()
            speaker.speak(parts.joinToString(", "))
        }

        // Speak a ride action the instant it happens, if its per-event toggle is on.
        fun announceEvent(e: RideEvent) {
            if (!settings.ttsEnabled) return
            val (enabled, phrase) = when (e) {
                RideEvent.LockOn -> settings.announceWheelLock to "Wheel locked"
                RideEvent.LockOff -> settings.announceWheelLock to "Wheel unlocked"
                RideEvent.LightsOn -> settings.announceLights to "Lights on"
                RideEvent.LightsOff -> settings.announceLights to "Lights off"
                RideEvent.LegalOn -> settings.announceLegalMode to "Legal mode on"
                RideEvent.LegalOff -> settings.announceLegalMode to "Legal mode off"
                RideEvent.RecStart -> settings.announceRecording to "Recording started"
                RideEvent.RecStop -> settings.announceRecording to "Recording finished"
            }
            if (enabled) {
                speaker.rate = ttsRate()
                speaker.speak(phrase)
            }
        }

        // Insert or update a rule by id; persists through SettingsStore so the list
        // + the running alarm engine pick it up live.
        fun upsertAlarm(rule: AlarmRule) = settingsStore.update { s ->
            val exists = s.alarmRules.any { it.id == rule.id }
            s.copy(alarmRules = if (exists) s.alarmRules.map { if (it.id == rule.id) rule else it } else s.alarmRules + rule)
        }
        fun deleteAlarm(id: Long) = settingsStore.update { s -> s.copy(alarmRules = s.alarmRules.filter { it.id != id }) }

        fun leaveRide() {
            recorder.stop() // finalize + save any in-progress recording (no-op if not recording)
            session?.stop()
            demoModel?.stop()
            speaker.stop()
            session = null
            demoModel = null
            demoWheel = null
            selectedMetric = null
            selectedTrip = null
            route = Route.Dashboard
        }

        // Connect to a real wheel, remember it for auto-reconnect, and (optionally)
        // start recording. Shared by the manual Scan tap and the auto-connect below.
        fun connectReal(dev: BleDevice) {
            error = null
            connectingName = dev.name ?: dev.address
            scope.launch {
                try {
                    session = connectModel.connect(dev)
                    settingsStore.update { it.copy(lastWheelAddress = dev.address) }
                    // Start now only if auto-record is on AND not gated on motion (else the
                    // telemetry loop starts it on first movement). Read latest, not the closure.
                    if (settingsStore.current.autoStartRecording && !settingsStore.current.autoRecordStartInMotion) recorder.start()
                    route = Route.Dashboard
                } catch (e: Throwable) {
                    error = e.message ?: "connection failed"
                } finally {
                    connectingName = null
                }
            }
        }

        val inRide = session != null || demoModel != null

        // Keep the screen awake during a ride when the General setting is on
        // (iOS idleTimerDisabled); reset when the ride ends or the toggle flips.
        LaunchedEffect(inRide, settings.keepScreenOn) {
            setKeepScreenOn(inRide && settings.keepScreenOn)
        }

        // Auto-reconnect: on launch (not in a ride), if enabled and a wheel was
        // remembered, wait for it to reappear in the scan, then connect. Device-only
        // — the Simulator has no radio, so the match never arrives. The keys restart
        // the wait on state change; a successful connect flips inRide and cancels it.
        LaunchedEffect(inRide, settings.autoConnectLastWheel, settings.lastWheelAddress) {
            if (inRide || !settings.autoConnectLastWheel || settings.lastWheelAddress.isBlank()) return@LaunchedEffect
            val addr = settings.lastWheelAddress
            val match = connectModel.devices.first { devs -> devs.any { it.address == addr } }
                .first { it.address == addr }
            if (connectingName == null) connectReal(match)
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.appColors.appBackground) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when {
                !inRide -> ScanScreen(
                    connectModel = connectModel,
                    connectingName = connectingName,
                    error = error,
                    onConnectReal = { dev -> connectReal(dev) },
                    onConnectDemo = { w ->
                        demoWheel = w
                        demoModel = DashboardModel(scope)
                        if (settings.autoStartRecording && !settings.autoRecordStartInMotion) recorder.start()
                        route = Route.Dashboard
                    },
                )
                route == Route.Settings -> SettingsScreen(
                    settings = settings,
                    connected = session != null,
                    onUpdate = { transform -> settingsStore.update(transform) },
                    onApplyMaxSpeed = { tiltback, alarm ->
                        session?.let { s -> scope.launch { s.setMaxSpeed(tiltback, alarm) } }
                    },
                    onServiceMode = { route = Route.ServiceMode },
                    onThemeEditor = { route = Route.ThemeEditor },
                    onEditAlarm = { editingAlarm = it; route = Route.AlarmEditor },
                    onBack = { route = Route.Dashboard },
                )
                route == Route.ThemeEditor -> ThemeEditorScreen(
                    settings = settings,
                    onUpdate = { transform -> settingsStore.update(transform) },
                    onBack = { route = Route.Settings },
                )
                route == Route.AlarmEditor && editingAlarm != null -> AlarmEditorScreen(
                    initial = editingAlarm!!,
                    onChange = { upsertAlarm(it) },
                    onDelete = { deleteAlarm(editingAlarm!!.id); editingAlarm = null; route = Route.Settings },
                    onBack = { editingAlarm = null; route = Route.Settings },
                )
                route == Route.Recording -> {
                    val st = selectedTrip
                    if (st != null) {
                        TripDetailScreen(st, unitSpeed = settings.unitSpeed, unitDistance = settings.unitDistance, onBack = { selectedTrip = null })
                    } else {
                        RecordingScreen(
                            trips = trips,
                            unitSpeed = settings.unitSpeed,
                            unitDistance = settings.unitDistance,
                            onOpen = { selectedTrip = it },
                            onBack = { route = Route.Dashboard },
                        )
                    }
                }
                route == Route.ServiceMode -> ServiceModeScreen(
                    connected = session != null,
                    onFire = { bytes -> session?.let { s -> scope.launch { s.sendRaw(bytes) } } },
                    onBack = { route = Route.Settings },
                )
                selectedMetric != null -> MetricDetailScreen(
                    metricKey = selectedMetric!!,
                    history = history,
                    current = current,
                    unitDistance = settings.unitDistance,
                    unitTemp = settings.unitTemp,
                    onBack = { selectedMetric = null },
                )
                else -> DashboardRoute(
                    session = session,
                    demoTitle = demoWheel?.name ?: "EUC Planet",
                    demoBrand = demoWheel?.brand ?: "demo",
                    data = current,
                    history = history,
                    alarms = alarms,
                    gaugeBand = settings.gaugeColorBand,
                    unitSpeed = settings.unitSpeed,
                    unitDistance = settings.unitDistance,
                    unitTemp = settings.unitTemp,
                    columns = settings.dashboardColumns,
                    statCorners = settings.statCorners,
                    recording = recording,
                    onToggleRecord = {
                        val wasRecording = recording
                        recorder.toggle()
                        announceEvent(if (wasRecording) RideEvent.RecStop else RideEvent.RecStart)
                    },
                    onAnnounce = { announce() },
                    onRideEvent = { announceEvent(it) },
                    onApplyLegalLimits = { legal ->
                        // Toggling legal mode writes the legal (or normal) tiltback +
                        // alarm to the connected wheel, like Android. Device-only: a
                        // no-op without a live session (demo just flips the visual).
                        session?.let { s ->
                            val tb = if (legal) settings.legalTiltbackKmh else settings.tiltbackKmh
                            val al = if (legal) settings.legalAlarmKmh else settings.alarmKmh
                            scope.launch { s.setMaxSpeed(tb, al) }
                        }
                    },
                    announceIntervalSec = settings.announceIntervalSec,
                    onScan = { leaveRide() },
                    onSettings = { route = Route.Settings },
                    onRecording = { route = Route.Recording },
                    onMetricClick = { selectedMetric = it },
                )
            }
            }
        }
    }
}

@Composable
private fun DashboardRoute(
    session: WheelSession?,
    demoTitle: String,
    demoBrand: String,
    data: WheelData,
    history: List<WheelData>,
    alarms: List<RideAlarm>,
    gaugeBand: Boolean,
    unitSpeed: String,
    unitDistance: String,
    unitTemp: String,
    columns: Int,
    statCorners: Boolean,
    recording: Boolean,
    onToggleRecord: () -> Unit,
    onAnnounce: () -> Unit,
    onRideEvent: (RideEvent) -> Unit,
    onApplyLegalLimits: (Boolean) -> Unit,
    announceIntervalSec: Int,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onRecording: () -> Unit,
    onMetricClick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val live = session != null
    val fallbackModel = remember { MutableStateFlow<String?>(null) }
    val liveModel by (session?.modelName ?: fallbackModel).collectAsState()

    var lightOn by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var legalMode by remember { mutableStateOf(false) }
    var voiceOn by remember { mutableStateOf(false) }

    // Periodic spoken status while the VOICE button is active, on the interval.
    // rememberUpdatedState so the long-lived loop always calls the latest
    // onAnnounce (which closes over live telemetry), not the one captured when
    // voice was first switched on.
    val currentAnnounce by rememberUpdatedState(onAnnounce)
    LaunchedEffect(voiceOn, announceIntervalSec) {
        if (voiceOn) {
            while (isActive) {
                delay(announceIntervalSec.coerceAtLeast(5).toLong() * 1000L)
                currentAnnounce()
            }
        }
    }

    val title = if (live) (liveModel ?: session!!.brand) else demoTitle
    val subtitle = if (live) "${session!!.brand} · live" else "$demoBrand · demo"

    DashboardScreen(
        d = data,
        history = history,
        title = title,
        subtitle = subtitle,
        connected = live,
        lightOn = lightOn,
        locked = locked,
        legalMode = legalMode,
        voiceOn = voiceOn,
        recording = recording,
        alarms = alarms,
        gaugeBand = gaugeBand,
        unitSpeed = unitSpeed,
        unitDistance = unitDistance,
        unitTemp = unitTemp,
        columns = columns,
        statCorners = statCorners,
        onHorn = { session?.let { s -> scope.launch { s.horn() } } },
        onToggleLight = {
            lightOn = !lightOn
            onRideEvent(if (lightOn) RideEvent.LightsOn else RideEvent.LightsOff)
            session?.let { s -> scope.launch { s.setLight(lightOn) } }
        },
        onToggleVoice = { val nowOn = !voiceOn; voiceOn = nowOn; if (nowOn) onAnnounce() },
        onToggleLegal = {
            legalMode = !legalMode
            onRideEvent(if (legalMode) RideEvent.LegalOn else RideEvent.LegalOff)
            onApplyLegalLimits(legalMode)
        },
        onToggleLock = {
            locked = !locked
            onRideEvent(if (locked) RideEvent.LockOn else RideEvent.LockOff)
            session?.let { s -> scope.launch { s.setLock(locked) } }
        },
        onToggleRecord = onToggleRecord,
        onScan = onScan,
        onSettings = onSettings,
        onRecordingScreen = onRecording,
        onMetricClick = onMetricClick,
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
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = c.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                if (connectingName != null) "connecting to $connectingName…" else "scanning for wheels…",
                color = c.textSecondary, fontSize = 12.sp,
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Bluetooth, contentDescription = "wheel", tint = c.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.textSecondary, fontSize = 12.sp)
        }
        SignalBars(c, rssi)
        Spacer(Modifier.width(14.dp))
        Text("Connect ›", color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SignalBars(c: com.eried.eucplanet.ui.theme.AppThemeColors, rssi: Int) {
    val level = when {
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                Modifier.width(4.dp).height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) c.statusGood else c.surfaceVariant),
            )
        }
    }
}
