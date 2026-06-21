package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.eried.eucplanet.ble.isLikelyWheel
import com.eried.eucplanet.ble.transport.BleConnState
import com.eried.eucplanet.ble.transport.BleDevice
import com.eried.eucplanet.cloud.EditResult
import com.eried.eucplanet.cloud.EucStatsRepository
import com.eried.eucplanet.cloud.RegisterResult
import com.eried.eucplanet.cloud.RiderCard
import com.eried.eucplanet.cloud.RiderProfile
import com.eried.eucplanet.cloud.UploadResult
import com.eried.eucplanet.cloud.uuid4
import com.eried.eucplanet.data.createFileStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.eried.eucplanet.data.DiagnosticsLog
import com.eried.eucplanet.data.RideAlarm
import com.eried.eucplanet.data.AlarmEngine
import com.eried.eucplanet.data.AutoLightsEngine
import com.eried.eucplanet.data.ChargeEstimator
import com.eried.eucplanet.data.CrashLog
import com.eried.eucplanet.data.SettingsStore
import com.eried.eucplanet.data.TripRecorder
import com.eried.eucplanet.data.AlarmRule
import com.eried.eucplanet.data.activeAlarms
import com.eried.eucplanet.data.model.TripBackup
import com.eried.eucplanet.data.model.TripSummary
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import com.eried.eucplanet.ui.theme.ThemeTokens
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.hudlink.HudClient
import com.eried.eucplanet.hudlink.HudCommand
import com.eried.eucplanet.hudlink.HudState
import com.eried.eucplanet.hudlink.OverlayElement
import com.eried.eucplanet.hudlink.OverlayElementType
import com.eried.eucplanet.hudlink.OverlayPreset
import com.eried.eucplanet.hudlink.OverlayPresetCodec
import com.eried.eucplanet.location.LocationService
import com.eried.eucplanet.nav.CurrentRouteStore
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.ui.navigator.RouteBuilderScreen
import com.eried.eucplanet.ui.navigator.RouteBuilderViewModel
import com.eried.eucplanet.ui.eucstats.ManageProfileDialog
import com.eried.eucplanet.ui.eucstats.OnlineOnboardingDialog
import com.eried.eucplanet.ui.studio.OverlayStudioScreen
import com.eried.eucplanet.ui.welcome.WelcomeWizard
import com.eried.eucplanet.util.AvatarPicker
import com.eried.eucplanet.util.UnitFormat
import com.eried.eucplanet.watch.WatchControl
import com.eried.eucplanet.watch.WatchLink
import com.eried.eucplanet.watch.WatchState
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

private enum class Route { Dashboard, Settings, Recording, ServiceMode, ThemeEditor, AlarmEditor, VoicePicker, OverlayStudio, Map, Scan, Battery }

/** Ride actions that can be spoken aloud the moment they happen (gated by the
 *  matching per-event toggle in Voice settings). */
private enum class RideEvent { LockOn, LockOff, LightsOn, LightsOff, LegalOn, LegalOff, RecStart, RecStop }

/** HUD carousel screens the iOS app feeds. Map + Compass now work (phone GPS);
 *  Nav is still omitted (no nav engine on iOS). "Custom" renders the Overlay
 *  Studio preset, "Camera" is drawn HUD-side regardless of phone data. */
private val HUD_SCREENS = listOf(
    "Dashboard", "Camera", "Telemetry", "Custom", "CustomCam",
    "Map", "Power", "TripStats", "Compass", "Safety", "BigClock",
)

/**
 * Map the "Speech rate" multiplier (1.0× = normal, like Android's
 * voiceSpeechRate) to an AVSpeech utterance rate (0..1, where 0.5 is normal and
 * ~1.0 is roughly 2×). So 1.0×→0.50, 1.2×→0.60, 2.0×→1.0 (the iOS ceiling).
 * Legacy 0–100 values from before the multiplier rework fall back to 1.2×.
 */
private fun ttsRateOf(speechRate: Float): Float {
    val mult = if (speechRate in 0.3f..3.0f) speechRate else 1.1f
    return (0.5f * mult).coerceIn(0.1f, 1.0f)
}

/** App name spelled out so TTS says "E-U-C Planet", not the mangled "E Planet"
 *  (synthesizers misread the "EUC" acronym). Display text stays "EUC Planet". */
private const val SPOKEN_APP_NAME = "E U C Planet"

/** Format a [Color] as "#AARRGGBB" for the Apple Watch wire (parsed in SwiftUI). */
private fun Color.toArgbHex(): String {
    fun comp(f: Float) = (f * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${comp(alpha)}${comp(red)}${comp(green)}${comp(blue)}"
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
    // Accent the Apple Watch dial follows (the gauge needle / fill color),
    // streamed to the watch so it stays on-theme. Recomputed only on theme change.
    val watchAccentHex = remember(themeColors) { themeColors.gaugeFill.toArgbHex() }
    EucPlanetTheme(colors = themeColors) {
        val connectModel = remember { ConnectModel(scope) }
        val recorder = remember { TripRecorder(seedDemo = isSimulator()) }

        // EucStats online (trip backup + leaderboards). Registration + rank live
        // here; auto-upload is wired to the recorder below.
        val eucStats = remember { EucStatsRepository() }
        val eucFileStore = remember { createFileStore() }
        remember { CrashLog.init(eucFileStore) } // install crash handler + back the About crash-log tab
        var riderCard by remember { mutableStateOf<RiderCard?>(null) }
        var eucStatsBusy by remember { mutableStateOf(false) }
        // EucStats profile flow (onboarding join + manage profile).
        var showOnboarding by remember { mutableStateOf(false) }
        var showProfileDialog by remember { mutableStateOf(false) }
        var eucProfile by remember { mutableStateOf<RiderProfile?>(null) }

        // Which Settings sections are expanded — hoisted to App so it survives
        // leaving + re-entering the Settings screen (sub-screen navigation).
        var expandedSettings by remember { mutableStateOf(setOf("General")) }

        // Shared battery-charging estimator — fed each telemetry frame below.
        val chargeEstimator = remember { ChargeEstimator() }
        var chargeState by remember { mutableStateOf(ChargeEstimator.State()) }
        // Latest HUD frame, streamed to an external HUD by HudClient at 5 Hz.
        val hudFrame = remember { MutableStateFlow(HudState()) }
        var eucStatsMsg by remember { mutableStateOf<String?>(null) }
        val speaker = remember { createSpeaker() }
        val haptics = remember { createHaptics() }
        val alarmEngine = remember { AlarmEngine() }
        val autoLightsEngine = remember { AutoLightsEngine() }
        remember { DiagnosticsLog.install() } // tee adapter inspect notes into the Service Mode log
        val recording by recorder.recording.collectAsState()
        val trips by recorder.trips.collectAsState()
        var session by remember { mutableStateOf<WheelSession?>(null) }
        var demoModel by remember { mutableStateOf<DashboardModel?>(null) }
        var demoWheel by remember { mutableStateOf<Wheel?>(null) }
        var connectingName by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var route by remember { mutableStateOf(Route.Dashboard) }
        // Retains each screen's saveable UI state (scroll, expanded sections) across
        // navigation, the way Navigation Compose does per destination.
        val screenStateHolder = rememberSaveableStateHolder()
        var selectedMetric by remember { mutableStateOf<String?>(null) }
        var selectedTrip by remember { mutableStateOf<TripSummary?>(null) }
        var editingAlarm by remember { mutableStateOf<AlarmRule?>(null) }

        // Screenshot harness: EUC_DEMO_SCREEN auto-opens a demo ride on a screen
        // (see debugStartScreen). Unset in normal use → Scan-first flow.
        LaunchedEffect(Unit) {
            // EUC_HUD_PEER=ip:port → stream demo telemetry to a test HUD (verify the client).
            debugHudPeer()?.let { peer ->
                val parts = peer.split(":")
                settingsStore.update { it.copy(hudEnabled = true, hudIp = parts.getOrNull(0) ?: "", hudPort = parts.getOrNull(1)?.toIntOrNull() ?: 28080) }
                demoWheel = sampleWheels[0]
                demoModel = DashboardModel(scope)
            }
            val screen = debugStartScreen()?.lowercase() ?: return@LaunchedEffect
            demoWheel = sampleWheels[0]
            demoModel = DashboardModel(scope)
            if (settings.autoStartRecording && !settings.autoRecordStartInMotion) recorder.start() // mirror the real connect path
            when (screen) {
                "settings" -> route = Route.Settings
                "battery", "charging" -> route = Route.Battery
                "map", "navigator" -> route = Route.Map
                "eucstats", "join", "leaderboard" -> { route = Route.Settings; showOnboarding = true }
                "overlaystudio", "studio" -> {
                    val demo = OverlayPreset(elements = listOf(
                        OverlayElement(id = "d1", type = OverlayElementType.DATA_DIAL, metric = "SPEED", x = 0.05f, y = 0.10f, width = 0.34f, gaugeMax = 60f, dialShowColorBand = true),
                        OverlayElement(id = "d2", type = OverlayElementType.DATA_VALUE, metric = "BATTERY", x = 0.62f, y = 0.12f, width = 0.33f),
                        OverlayElement(id = "d3", type = OverlayElementType.DATA_BAR, metric = "PWM", x = 0.05f, y = 0.66f, width = 0.5f, gaugeMax = 100f),
                        OverlayElement(id = "d4", type = OverlayElementType.TEXT, text = "{wheel}", x = 0.62f, y = 0.70f, width = 0.33f),
                    ))
                    settingsStore.update { it.copy(hudCustomOverlayJson = OverlayPresetCodec.encode(demo)) }
                    route = Route.OverlayStudio
                }
                "recording", "recordings" -> route = Route.Recording
                "servicemode", "service" -> route = Route.ServiceMode
                "themeeditor", "theme" -> route = Route.ThemeEditor
                "alarmeditor", "alarm" -> { editingAlarm = AlarmRule(id = 1, name = "Overspeed", threshold = 45f); route = Route.AlarmEditor }
                "voicepicker", "voice" -> route = Route.VoicePicker
                "metric", "metricdetail" -> { route = Route.Dashboard; selectedMetric = "voltage" }
                "tripdetail", "trip" -> {
                    val s = (0 until 48).map { i ->
                        WheelData(
                            speed = 18f + 12f * kotlin.math.sin(i * 0.25).toFloat(),
                            voltage = 92f + 3f * kotlin.math.sin(i * 0.18).toFloat(),
                            timestamp = i * 1000L,
                        )
                    }
                    selectedTrip = TripSummary("Demo ride · just now", 12.4f, 31, 24.1f, 41.6f, gpsLock = true, backup = TripBackup.Off, csvPath = "Documents/euc_trip_1.csv", samples = s)
                    route = Route.Recording
                }
                else -> route = Route.Dashboard
            }
        }

        // Start phone GPS (CoreLocation on iOS; no-op on Android). Held until AFTER
        // the welcome tour so the rider sees onboarding before the iOS permission
        // prompt; demo screens start it immediately (except the welcome screenshot).
        run {
            val demo = debugStartScreen()?.lowercase()
            LaunchedEffect(settings.welcomeTutorialSeen) {
                if (settings.welcomeTutorialSeen || (demo != null && demo != "welcome")) LocationService.start()
            }
        }

        // Rolling telemetry history (hoisted here so it survives Dashboard ↔
        // MetricDetail navigation) collected from whichever source is active.
        val activeFlow = session?.data ?: demoModel?.data
        val history = remember { mutableStateListOf<WheelData>() }
        LaunchedEffect(activeFlow) {
            history.clear()
            alarmEngine.reset() // fresh alarm firing state per ride (no cross-ride cooldown leak)
            chargeEstimator.reset()
            autoLightsEngine.reset() // re-arm auto-lights + clear suspension per ride (like Android)
            var lastMotionMs = 0L
            var hadGpsFix = false
            var lastAutoToggleMs = 0L
            var lastKnownLight: Boolean? = null
            var autoLightsSuspended = false
            activeFlow?.collect { raw ->
                val s = settingsStore.current
                val fix = LocationService.location.value
                // Apply the speed-calibration offset at the source so the gauge, alarms,
                // voice and recorder all see the same calibrated speed (matches Android).
                // Speed is shown as a MAGNITUDE: the wheel reports a signed value that
                // goes negative rolling backward, but Android displays abs(speed).
                val calSpeed = raw.speed * (1f + s.speedCalibrationPct / 100f)
                val wd = raw.copy(
                    speed = kotlin.math.abs(calSpeed),
                    latitude = fix?.lat ?: raw.latitude,
                    longitude = fix?.lng ?: raw.longitude,
                )
                history.add(wd)
                if (history.size > 150) history.removeAt(0)
                chargeState = chargeEstimator.step(wd)
                // Apple Watch: push a compact snapshot each frame. iOS forwards it to
                // the paired watch over WatchConnectivity; Android is a no-op (it has
                // its own Wear bridge). Speed/temp go on the wire in km/h + °C; the
                // watch converts using the unit codes.
                WatchLink.publish(
                    WatchState(
                        connected = session != null,
                        wheelName = session?.modelName?.value ?: demoWheel?.name ?: "",
                        speedKmh = wd.speed,
                        batteryPercent = wd.batteryPercent,
                        pwmPercent = wd.pwm,
                        tempC = wd.maxTemperature,
                        maxSpeedKmh = (((s.tiltbackKmh / 10f).toInt() + 1) * 10f).coerceAtLeast(30f),
                        lightOn = wd.lightOn,
                        unitSpeed = s.unitSpeed,
                        unitTemp = s.unitTemp,
                        accentArgb = watchAccentHex,
                    ),
                )
                // External HUD frame (canonical metric; the HUD converts units).
                hudFrame.value = HudState(
                    connected = session != null,
                    wheelName = session?.modelName?.value ?: demoWheel?.name ?: "",
                    speedKmh = wd.speed,
                    batteryPercent = wd.batteryPercent,
                    voltage = wd.voltage,
                    current = wd.current,
                    pwm = wd.pwm,
                    temperatureC = wd.maxTemperature,
                    tripKm = wd.tripDistance,
                    totalKm = wd.totalDistance,
                    torque = wd.torque,
                    lightOn = wd.lightOn,
                    gaugeMaxKmh = (((s.tiltbackKmh / 10f).toInt() + 1) * 10f).coerceAtLeast(30f),
                    gaugeOrangeThresholdPct = s.gaugeOrangeThresholdPct,
                    gaugeRedThresholdPct = s.gaugeRedThresholdPct,
                    showGaugeColorBand = s.gaugeColorBand,
                    unitSpeed = s.unitSpeed,
                    unitDistance = s.unitDistance,
                    unitTemp = s.unitTemp,
                    accentArgb = watchAccentHex,
                    customOverlayJson = s.hudCustomOverlayJson,
                    // GPS (phone CoreLocation) — drives the HUD Map / Compass + GPS speed.
                    latitude = fix?.lat ?: 0.0,
                    longitude = fix?.lng ?: 0.0,
                    gpsHasFix = fix != null,
                    gpsSpeedKmh = fix?.speedKmh?.takeIf { it >= 0f } ?: Float.NaN,
                    gpsSource = if (fix != null) "PHONE" else "",
                    gpsHeadingDeg = fix?.bearingDeg?.takeIf { it >= 0f } ?: Float.NaN,
                    gpsAltitudeM = fix?.altitudeM ?: Float.NaN,
                    // Non-empty so the HUD renders instead of its "waiting" splash.
                    enabledHudScreens = HUD_SCREENS,
                )
                recorder.sample(wd)
                // Alarm engine: evaluate the rules each frame and fire the actions of
                // any rule that's due (honouring per-rule cooldown / repeat-while-active).
                val toFire = alarmEngine.step(activeAlarms(wd, s.alarmRules), s.alarmRules, nowEpochMillis())
                if (toFire.isNotEmpty()) {
                    if (toFire.any { it.vibrateEnabled }) haptics.warning()
                    val spoken = toFire.filter { it.voiceEnabled }.map { it.spoken }
                    if (s.ttsEnabled && spoken.isNotEmpty()) {
                        speaker.stop()
                        speaker.rate = ttsRateOf(s.speechRate)
                        speaker.voiceId = s.voiceId
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
                // GPS-acquired / lost voice (matches Android's announceGps).
                if (s.announceGps && s.ttsEnabled) {
                    if (fix != null && !hadGpsFix) {
                        hadGpsFix = true
                        speaker.rate = ttsRateOf(s.speechRate); speaker.voiceId = s.voiceId; speaker.speak("GPS signal acquired")
                    } else if (fix == null && hadGpsFix) {
                        hadGpsFix = false
                        speaker.rate = ttsRateOf(s.speechRate); speaker.voiceId = s.voiceId; speaker.speak("GPS signal lost")
                    }
                } else if (fix != null) hadGpsFix = true
                // Sun + GPS auto-lights (faithful port of Android's AutomationManager).
                if (s.autoLightsEnabled && !autoLightsSuspended && session != null) {
                    val want = autoLightsEngine.desiredState(fix, s.autoLightsOnMinutesBefore, s.autoLightsOffMinutesAfter, nowEpochMillis())
                    if (want != null && want != wd.lightOn) {
                        session?.let { sess -> scope.launch { sess.setLight(want) } }
                        lastAutoToggleMs = nowEpochMillis()
                    }
                }
                // A light change outside our own auto-toggle = manual → suspend this ride.
                if (lastKnownLight != null && lastKnownLight != wd.lightOn && lastAutoToggleMs != 0L &&
                    nowEpochMillis() - lastAutoToggleMs > 4_000L) {
                    autoLightsSuspended = true
                }
                lastKnownLight = wd.lightOn
            }
        }
        val current = history.lastOrNull() ?: WheelData()
        val alarms = activeAlarms(current, settings.alarmRules)

        // Route the Apple Watch's horn / light buttons back to the wheel. Registered
        // once; reads the latest session + reported light state through refs so the
        // long-lived handler never fires against stale closure values. Light toggles
        // relative to the wheel's reported state so the watch + dashboard agree.
        val sessionRef = rememberUpdatedState(session)
        val lightRef = rememberUpdatedState(current.lightOn)
        LaunchedEffect(Unit) {
            WatchLink.setControlHandler { action ->
                val s = sessionRef.value ?: return@setControlHandler
                when (action) {
                    WatchControl.HORN -> scope.launch { s.horn() }
                    WatchControl.LIGHT_TOGGLE -> scope.launch { s.setLight(!lightRef.value) }
                }
            }
        }

        // External HUD link: dial the configured HUD and stream the 5 Hz frame;
        // route HUD button intents (light/horn) back to the wheel. nowEpochMillis
        // bumps the timestamp each send so the HUD's freshness signal stays live.
        val hudClient = remember {
            HudClient(
                scope = scope,
                snapshot = { hudFrame.value.copy(timestampMs = nowEpochMillis()) },
                onCommand = { cmd ->
                    val sess = sessionRef.value
                    when (cmd) {
                        is HudCommand.Horn -> sess?.let { scope.launch { it.horn() } }
                        is HudCommand.ToggleLight -> sess?.let { scope.launch { it.setLight(!lightRef.value) } }
                        else -> {} // StopNavigation / Action / Pair: no-op on iOS v1
                    }
                },
            )
        }
        LaunchedEffect(settings.hudEnabled, settings.hudIp, settings.hudPort) {
            if (settings.hudEnabled && settings.hudIp.isNotBlank()) hudClient.start(settings.hudIp, settings.hudPort)
            else hudClient.stop()
        }
        val hudStatus by hudClient.status.collectAsState()
        val hudStatusLabel = when (hudStatus) {
            HudClient.Status.Connected -> "Connected"
            HudClient.Status.Connecting -> "Connecting…"
            HudClient.Status.Error -> "Not reachable — retrying"
            HudClient.Status.Disabled -> "Off"
        }

        fun ttsRate() = ttsRateOf(settings.speechRate)
        fun announce() {
            if (!settings.ttsEnabled) return
            val parts = mutableListOf<String>()
            if (settings.announceSpeed) parts += "Speed ${UnitFormat.speed(current.speed, settings.unitSpeed).roundToInt()}"
            if (settings.announceBattery) parts += "Battery ${current.batteryPercent} percent"
            if (settings.announceTemp) parts += "Temperature ${UnitFormat.temperature(current.maxTemperature, settings.unitTemp).roundToInt()} degrees"
            if (parts.isEmpty()) return // all periodic-report toggles off = the rider wants silence
            speaker.rate = ttsRate()
            speaker.voiceId = settings.voiceId
            speaker.speak(parts.joinToString(", "))
        }

        // Speak a one-off event phrase now (connect/disconnect/welcome), honouring
        // the TTS master toggle + voice/rate. Faithful to Android's announceEvent.
        fun speakNow(text: String) {
            val s = settingsStore.current
            if (!s.ttsEnabled) return
            speaker.rate = ttsRate()
            speaker.voiceId = s.voiceId
            speaker.speak(text)
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
                speaker.voiceId = settings.voiceId
                speaker.speak(phrase)
            }
        }

        // Welcome message, spoken once per app launch (matches Android's welcomeOnce).
        LaunchedEffect(Unit) {
            if (settingsStore.current.announceWelcome) speakNow("Welcome back to $SPOKEN_APP_NAME")
        }

        // --- Navigator (route planner + live turn-by-turn guidance) ---
        // Live wheel speed read lazily so the long-lived engine always sees the
        // latest telemetry (the engine instance is created once via remember).
        val liveWheelSpeed = rememberUpdatedState(current.speed)
        val routingService = remember { RoutingService() }
        val currentRouteStore = remember { CurrentRouteStore() }
        val navigationEngine = remember {
            NavigationEngine(
                location = LocationService.location,
                wheelSpeedKmh = { liveWheelSpeed.value },
                settings = settingsStore.settings,
                routingService = routingService,
                currentRouteStore = currentRouteStore,
                speak = { speakNow(it) },
                setNavCue = { },
                startLocation = { LocationService.start() },
                nowMs = { nowEpochMillis() },
            )
        }
        val routeBuilderVm = remember {
            RouteBuilderViewModel(
                routingService = routingService,
                currentRouteStore = currentRouteStore,
                navigationEngine = navigationEngine,
                location = LocationService.location,
                settings = settingsStore.settings,
                updateSettings = { transform -> settingsStore.update(transform) },
                startLocation = { LocationService.start() },
                scope = scope,
            )
        }

        // Insert or update a rule by id; persists through SettingsStore so the list
        // + the running alarm engine pick it up live.
        fun upsertAlarm(rule: AlarmRule) = settingsStore.update { s ->
            val exists = s.alarmRules.any { it.id == rule.id }
            s.copy(alarmRules = if (exists) s.alarmRules.map { if (it.id == rule.id) rule else it } else s.alarmRules + rule)
        }
        fun deleteAlarm(id: Long) = settingsStore.update { s -> s.copy(alarmRules = s.alarmRules.filter { it.id != id }) }

        // Stop + clear any active ride (real or demo) WITHOUT changing the route, so
        // connecting can switch wheels cleanly. leaveRide adds the route reset.
        fun cleanupRide() {
            recorder.stop() // finalize + save any in-progress recording (no-op if not recording)
            session?.stop()
            demoModel?.stop()
            speaker.stop()
            session = null
            demoModel = null
            demoWheel = null
            selectedMetric = null
            selectedTrip = null
            hudFrame.value = HudState() // HUD shows disconnected, not a frozen frame
        }
        fun leaveRide() {
            cleanupRide()
            route = Route.Dashboard
        }

        // Connect to a real wheel, remember it for auto-reconnect, and (optionally)
        // start recording. Shared by the manual Scan tap and the auto-connect below.
        fun connectReal(dev: BleDevice) {
            cleanupRide() // drop any existing ride before connecting fresh
            error = null
            connectingName = dev.name ?: dev.address
            scope.launch {
                try {
                    session = connectModel.connect(dev)
                    settingsStore.update { it.copy(lastWheelAddress = dev.address) }
                    if (settingsStore.current.announceConnection) speakNow("Wheel connected")
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

        // EucStats: minimal wheel meta + auto-upload each saved ride when enabled.
        fun wheelJson(): String = buildJsonObject {
            session?.brand?.takeIf { it.isNotBlank() }?.let { put("brand", it) }
            session?.modelName?.value?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        }.toString()
        recorder.onTripSaved = { id, st, en, sc, csv ->
            val s = settingsStore.current
            if (s.eucStatsEnabled && s.eucStatsAutoUpload && s.eucStatsStoreId.isNotBlank()) {
                recorder.setBackup(id, TripBackup.Pending)
                val wj = wheelJson()
                scope.launch {
                    val r = eucStats.upload(s.eucStatsStoreId, uuid4(), st, en, sc, csv, wj)
                    recorder.setBackup(id, if (r is UploadResult.Ok) TripBackup.Uploaded else TripBackup.Failed)
                }
            }
        }
        // Manual backup of one trip (also the failed-upload retry path). Rebuilds the
        // CSV + times from the trip's samples, so it works for any in-session ride.
        fun backUpTrip(trip: TripSummary) {
            val s = settingsStore.current
            if (!s.eucStatsEnabled || s.eucStatsStoreId.isBlank() || trip.samples.size < 2) return
            recorder.setBackup(trip.id, TripBackup.Pending)
            val csv = recorder.csvFor(trip)
            val startMs = trip.samples.first().timestamp
            val endMs = trip.samples.last().timestamp
            val wj = wheelJson()
            scope.launch {
                val r = eucStats.upload(s.eucStatsStoreId, uuid4(), startMs, endMs, trip.samples.size, csv, wj)
                recorder.setBackup(trip.id, if (r is UploadResult.Ok) TripBackup.Uploaded else TripBackup.Failed)
            }
        }
        // "Back up now": push every trip that isn't already uploaded / in flight.
        fun syncAllTrips() {
            trips.filter { it.backup == TripBackup.Off || it.backup == TripBackup.Failed }.forEach { backUpTrip(it) }
        }
        fun registerEucStats() {
            scope.launch {
                eucStatsBusy = true; eucStatsMsg = null
                val s = settingsStore.current
                val sid = s.eucStatsStoreId.ifBlank { uuid4() }
                when (val r = eucStats.register(sid, s.eucStatsDisplayName, s.eucStatsFlag)) {
                    is RegisterResult.Ok -> {
                        settingsStore.update { it.copy(eucStatsStoreId = sid, eucStatsEnabled = true) }
                        riderCard = eucStats.card(sid)
                        eucStatsMsg = "Registered"
                    }
                    RegisterResult.RateLimited -> eucStatsMsg = "Rate limited — try again later"
                    is RegisterResult.Failed -> eucStatsMsg = r.detail ?: "Failed (${r.code})"
                }
                eucStatsBusy = false
            }
        }
        fun refreshEucStats() {
            val sid = settingsStore.current.eucStatsStoreId
            if (sid.isNotBlank()) scope.launch { eucStatsBusy = true; riderCard = eucStats.card(sid); eucStatsBusy = false }
        }
        // Onboarding "Join": register with name + flag + optional avatar, persist, fetch card.
        fun joinLeaderboard(name: String, flag: String, avatar: String?) {
            scope.launch {
                eucStatsBusy = true; eucStatsMsg = null
                val sid = settingsStore.current.eucStatsStoreId.ifBlank { uuid4() }
                when (val r = eucStats.register(sid, name, flag, avatar)) {
                    is RegisterResult.Ok -> {
                        settingsStore.update { it.copy(eucStatsStoreId = sid, eucStatsEnabled = true, eucStatsDisplayName = name, eucStatsFlag = flag) }
                        riderCard = eucStats.card(sid)
                        showOnboarding = false
                        eucStatsMsg = "Joined the leaderboard"
                    }
                    RegisterResult.RateLimited -> eucStatsMsg = "Rate limited — try again later"
                    is RegisterResult.Failed -> eucStatsMsg = r.detail ?: "Failed (${r.code})"
                }
                eucStatsBusy = false
            }
        }
        fun openManageProfile() {
            showProfileDialog = true; eucProfile = null; eucStatsMsg = null
            val sid = settingsStore.current.eucStatsStoreId
            if (sid.isNotBlank()) scope.launch { eucProfile = eucStats.profile(sid) }
        }
        fun saveProfile(name: String?, flag: String?, avatar: String?) {
            val sid = settingsStore.current.eucStatsStoreId
            if (sid.isBlank()) return
            scope.launch {
                eucStatsBusy = true; eucStatsMsg = null
                when (val r = eucStats.updateProfile(sid, name, flag, avatar)) {
                    EditResult.Ok -> {
                        settingsStore.update { it.copy(eucStatsDisplayName = name ?: it.eucStatsDisplayName, eucStatsFlag = flag ?: it.eucStatsFlag) }
                        riderCard = eucStats.card(sid)
                        showProfileDialog = false
                        eucStatsMsg = "Profile saved"
                    }
                    EditResult.RateLimited -> eucStatsMsg = "Rate limited — try again later"
                    is EditResult.Failed -> eucStatsMsg = r.detail ?: "Failed (${r.code})"
                }
                eucStatsBusy = false
            }
        }
        fun deleteEucStatsAccount() {
            val sid = settingsStore.current.eucStatsStoreId
            if (sid.isBlank()) return
            scope.launch {
                eucStatsBusy = true
                val ok = eucStats.deleteAccount(sid)
                if (ok) {
                    settingsStore.update { it.copy(eucStatsEnabled = false, eucStatsStoreId = "", eucStatsDisplayName = "", eucStatsFlag = "") }
                    riderCard = null; eucProfile = null; showProfileDialog = false
                    eucStatsMsg = "Account deleted"
                } else {
                    eucStatsMsg = "Delete failed — try again"
                }
                eucStatsBusy = false
            }
        }
        fun exportEucStats() {
            val sid = settingsStore.current.eucStatsStoreId
            if (sid.isBlank()) return
            scope.launch {
                eucStatsBusy = true
                val data = eucStats.exportData(sid)
                eucStatsMsg = if (data != null) {
                    eucFileStore.writeText("eucstats_export.json", data)
                    "Export saved to Documents (eucstats_export.json)"
                } else "Export failed"
                eucStatsBusy = false
            }
        }

        fun startDemo(w: Wheel) {
            cleanupRide()
            demoWheel = w
            demoModel = DashboardModel(scope)
            if (settings.autoStartRecording && !settings.autoRecordStartInMotion) recorder.start()
            route = Route.Dashboard
        }

        val inRide = session != null || demoModel != null

        // Detect a dropped wheel: when the transport flips to Disconnected (out of
        // range, powered off, BLE glitch) leave the ride and surface it on Scan —
        // otherwise the dashboard freezes on the last frame. Demo rides have no
        // connection, so this only does anything for a real session.
        LaunchedEffect(session) {
            val s = session ?: return@LaunchedEffect
            s.connectionState.collect { st ->
                if (st == BleConnState.Disconnected) {
                    error = "Wheel disconnected"
                    leaveRide() // stops the speaker, so announce AFTER it
                    if (settingsStore.current.announceConnection) speakNow("Wheel disconnected")
                }
            }
        }

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
            // Crossfade between top-level screens, matching Android's Navigation Compose
            // default fade (vs. the old instant/sharp swap). Each screen also gets its
            // own SaveableStateProvider (like Navigation Compose gives each destination),
            // so per-screen UI state — scroll position, expanded sections — survives a
            // round-trip to a sub-screen and back, instead of resetting to the top.
            Crossfade(targetState = route, animationSpec = tween(260), label = "screen") { r ->
            screenStateHolder.SaveableStateProvider(r.name) {
            when {
                // Dashboard is home — even with no wheel connected (matches Android:
                // launch straight to the main screen; the rider decides when to scan).
                // Scan is a backable sub-screen reached via the dashboard Bluetooth
                // button. connectReal/startDemo clean up any current ride themselves.
                r == Route.Scan -> ScanScreen(
                    connectModel = connectModel,
                    connectingName = connectingName,
                    error = error,
                    onConnectReal = { dev -> connectReal(dev) },
                    onConnectDemo = { startDemo(it) },
                    onBack = { route = Route.Dashboard },
                    onDisconnect = if (inRide) ({ leaveRide() }) else null,
                )
                r == Route.Settings -> SettingsScreen(
                    settings = settings,
                    connected = session != null,
                    onUpdate = { transform -> settingsStore.update(transform) },
                    onApplyMaxSpeed = { tiltback, alarm ->
                        session?.let { s -> scope.launch { s.setMaxSpeed(tiltback, alarm) } }
                    },
                    onServiceMode = { route = Route.ServiceMode },
                    onThemeEditor = { route = Route.ThemeEditor },
                    onEditAlarm = { editingAlarm = it; route = Route.AlarmEditor },
                    onVoicePicker = { route = Route.VoicePicker },
                    riderCard = riderCard,
                    eucStatsBusy = eucStatsBusy,
                    eucStatsMsg = eucStatsMsg,
                    onEucStatsRegister = { registerEucStats() },
                    onEucStatsRefresh = { refreshEucStats() },
                    onJoinLeaderboard = { eucStatsMsg = null; showOnboarding = true },
                    onManageProfile = { openManageProfile() },
                    expandedSections = expandedSettings,
                    onToggleSection = { t -> expandedSettings = if (t in expandedSettings) expandedSettings - t else expandedSettings + t },
                    hudStatus = hudStatusLabel,
                    onOverlayStudio = { route = Route.OverlayStudio },
                    onBack = { route = Route.Dashboard },
                )
                r == Route.VoicePicker -> VoicePickerScreen(
                    currentVoiceId = settings.voiceId,
                    onSelect = { id -> settingsStore.update { it.copy(voiceId = id) } },
                    onPreview = { id ->
                        speaker.stop()
                        speaker.rate = ttsRate()
                        speaker.voiceId = id
                        speaker.speak("$SPOKEN_APP_NAME. Speed twenty five kilometers per hour.")
                    },
                    onBack = { route = Route.Settings },
                )
                r == Route.ThemeEditor -> ThemeEditorScreen(
                    settings = settings,
                    onUpdate = { transform -> settingsStore.update(transform) },
                    onBack = { route = Route.Settings },
                )
                r == Route.AlarmEditor && editingAlarm != null -> AlarmEditorScreen(
                    initial = editingAlarm!!,
                    onChange = { upsertAlarm(it) },
                    onDelete = { deleteAlarm(editingAlarm!!.id); editingAlarm = null; route = Route.Settings },
                    onBack = { editingAlarm = null; route = Route.Settings },
                )
                r == Route.Recording -> {
                    val st = selectedTrip
                    if (st != null) {
                        TripDetailScreen(st, unitSpeed = settings.unitSpeed, unitDistance = settings.unitDistance, onBack = { selectedTrip = null })
                    } else {
                        RecordingScreen(
                            trips = trips,
                            unitSpeed = settings.unitSpeed,
                            unitDistance = settings.unitDistance,
                            backupEnabled = settings.eucStatsEnabled && settings.eucStatsStoreId.isNotBlank(),
                            onOpen = { selectedTrip = it },
                            onSyncAll = { syncAllTrips() },
                            onRetry = { backUpTrip(it) },
                            onBack = { route = Route.Dashboard },
                        )
                    }
                }
                r == Route.ServiceMode -> ServiceModeScreen(
                    connected = session != null,
                    onFire = { bytes -> session?.let { s -> scope.launch { s.sendRaw(bytes) } } },
                    onBack = { route = Route.Settings },
                )
                selectedMetric != null -> MetricDetailScreen(
                    metricKey = selectedMetric!!,
                    history = history,
                    current = current,
                    unitSpeed = settings.unitSpeed,
                    unitDistance = settings.unitDistance,
                    unitTemp = settings.unitTemp,
                    onBack = { selectedMetric = null },
                )
                r == Route.Battery -> BatteryScreen(
                    state = chargeState,
                    voltage = current.voltage,
                    maxTempC = current.maxTemperature,
                    batteryHistory = history.map { it.batteryPercent.toFloat() },
                    unitTemp = settings.unitTemp,
                    connected = session != null,
                    onBack = { route = Route.Dashboard },
                )
                r == Route.Map -> RouteBuilderScreen(routeBuilderVm, onBack = { route = Route.Dashboard })
                r == Route.OverlayStudio -> OverlayStudioScreen(
                    initial = OverlayPresetCodec.decode(settings.hudCustomOverlayJson) ?: OverlayPreset(),
                    live = current,
                    wheelName = session?.modelName?.value ?: demoWheel?.name ?: "EUC Planet",
                    unitSpeed = settings.unitSpeed,
                    unitDistance = settings.unitDistance,
                    unitTemp = settings.unitTemp,
                    onSave = { preset ->
                        settingsStore.update { it.copy(hudCustomOverlayJson = OverlayPresetCodec.encode(preset)) }
                        route = Route.Settings
                    },
                    onBack = { route = Route.Settings },
                )
                else -> DashboardRoute(
                    session = session,
                    demoTitle = demoWheel?.name ?: "EUC Planet",
                    demoBrand = demoWheel?.brand ?: "demo",
                    data = current,
                    history = history,
                    alarms = alarms,
                    gaugeBand = settings.gaugeColorBand,
                    // Gauge scales to the rider's tiltback (rounded up to the next 10,
                    // min 30) like Android, so the warn/danger bands land at meaningful
                    // speeds instead of a fixed 60.
                    gaugeMax = (((settings.tiltbackKmh / 10f).toInt() + 1) * 10f).coerceAtLeast(30f),
                    orangeThresholdPct = settings.gaugeOrangeThresholdPct,
                    redThresholdPct = settings.gaugeRedThresholdPct,
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
                    onScan = { route = Route.Scan },
                    onSettings = { route = Route.Settings },
                    onRecording = { route = Route.Recording },
                    onMetricClick = { if (it == "battery") route = Route.Battery else selectedMetric = it },
                    onStudio = { route = Route.OverlayStudio },
                    onMap = { route = Route.Map },
                    disconnected = !inRide,
                )
            }
            } // end SaveableStateProvider
            } // end Crossfade

            // EUC Stats profile flow — overlays the whole app.
            if (showOnboarding) {
                OnlineOnboardingDialog(
                    busy = eucStatsBusy,
                    errorMsg = eucStatsMsg,
                    onPickAvatar = { onResult -> AvatarPicker.pick(onResult) },
                    onRegister = { name, flag, avatar -> joinLeaderboard(name, flag, avatar) },
                    onDismiss = { showOnboarding = false },
                )
            }
            if (showProfileDialog) {
                ManageProfileDialog(
                    profile = eucProfile,
                    busy = eucStatsBusy,
                    errorMsg = eucStatsMsg,
                    onPickAvatar = { onResult -> AvatarPicker.pick(onResult) },
                    onSave = { name, flag, avatar -> saveProfile(name, flag, avatar) },
                    onDelete = { deleteEucStatsAccount() },
                    onExport = { exportEucStats() },
                    onDismiss = { showProfileDialog = false },
                )
            }
            // First-launch welcome tour (suppressed in the screenshot harness unless
            // EUC_DEMO_SCREEN=welcome). Drawn last so it overlays everything.
            val demoScreen = debugStartScreen()?.lowercase()
            if (!settings.welcomeTutorialSeen && route == Route.Dashboard && (demoScreen == null || demoScreen == "welcome")) {
                WelcomeWizard(
                    onVoiceOptIn = { on ->
                        settingsStore.update {
                            it.copy(
                                ttsEnabled = on, announceConnection = on, announceWelcome = on, announceGps = on,
                                announceLights = on, announceWheelLock = on, announceRecording = on,
                            )
                        }
                    },
                    onFinish = { settingsStore.update { it.copy(welcomeTutorialSeen = true) } },
                    initialStep = if (demoScreen == "welcome") 2 else 0,
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
    gaugeMax: Float,
    orangeThresholdPct: Int,
    redThresholdPct: Int,
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
    onStudio: () -> Unit = {},
    onMap: () -> Unit = {},
    disconnected: Boolean = false,
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

    val title = when {
        live -> liveModel ?: session!!.brand
        disconnected -> "EUC Planet"
        else -> demoTitle
    }
    val subtitle = when {
        live -> "${session!!.brand} · live"
        disconnected -> "Not connected — tap Bluetooth to scan"
        else -> "$demoBrand · demo"
    }

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
        gaugeMax = gaugeMax,
        orangeThresholdPct = orangeThresholdPct,
        redThresholdPct = redThresholdPct,
        unitSpeed = unitSpeed,
        unitDistance = unitDistance,
        unitTemp = unitTemp,
        columns = columns,
        statCorners = statCorners,
        disconnected = disconnected,
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
        onStudio = onStudio,
        onMap = onMap,
    )
}

@Composable
private fun ScanScreen(
    connectModel: ConnectModel,
    connectingName: String?,
    error: String?,
    onConnectReal: (BleDevice) -> Unit,
    onConnectDemo: (Wheel) -> Unit,
    onBack: (() -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
) {
    val c = MaterialTheme.appColors
    val devices by connectModel.devices.collectAsState()
    // Default to wheels only (like Android); the rider can reveal every BLE device
    // for a wheel with an unusual name via the toggle below.
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) devices else devices.filter { isLikelyWheel(it.name) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        if (onBack != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹  Back", color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(vertical = 6.dp, horizontal = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                if (onDisconnect != null) {
                    Text(
                        "Disconnect", color = c.statusDanger, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDisconnect() }.padding(vertical = 6.dp, horizontal = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            Spacer(Modifier.height(40.dp))
        }
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

        if (shown.isNotEmpty()) {
            Text(if (showAll) "NEARBY · ALL BLE" else "NEARBY WHEELS", color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            shown.forEach { dev ->
                WheelRow(c, dev.name ?: "(unnamed)", dev.address, dev.rssi) { onConnectReal(dev) }
            }
            Spacer(Modifier.height(12.dp))
        }

        // "Show all" toggle (matches Android's scan switch): only meaningful on a real
        // radio. Reveals non-wheel peripherals + wheels whose name isn't recognised.
        if (!isSimulator()) {
            val hidden = devices.size - shown.size
            Text(
                when {
                    showAll -> "Showing all Bluetooth devices · tap for wheels only"
                    hidden > 0 -> "Show all Bluetooth devices ($hidden hidden)"
                    else -> "Show all Bluetooth devices"
                },
                color = c.primary, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showAll = !showAll }.padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        // Demo wheels exist only because the Simulator has no Bluetooth radio. On a
        // real device we list only actual peripherals (like Android) — no fake names.
        if (isSimulator()) {
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
        }
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
