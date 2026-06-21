package com.eried.eucplanet.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.cloud.RiderCard
import com.eried.eucplanet.data.AlarmComparator
import com.eried.eucplanet.data.AlarmMetric
import com.eried.eucplanet.data.AlarmRule
import com.eried.eucplanet.audio.EngineProfile
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.ui.settings.SettingsSectionId
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.roundToInt

/**
 * Shared Settings screen — a port of the Android settings surface as collapsible
 * sections, bound to the shared [AppSettings] via [onUpdate] (so changes flow
 * through the SettingsStore and into the gauge / alarms / automations live). The
 * Speed sliders also push tiltback/alarm to the connected wheel.
 *
 * The *set and order* of sections is shared with the Android app via
 * [SettingsSectionId] (the single source of truth), so the two platforms can't
 * drift; only the section bodies below are iOS-specific (the v1 subset).
 */
/** Current settings-search query (empty = not searching). Each Section reads this
 *  to hide itself when it doesn't match, and to auto-expand on a match. */
private val LocalSettingsQuery = compositionLocalOf { "" }

/** Hoisted set of open section titles + a toggle, so a section's expanded state
 *  survives leaving + re-entering the Settings screen (sub-screen navigation). */
private class SectionExpand(val open: Set<String>, val toggle: (String) -> Unit)
private val LocalSectionExpand = compositionLocalOf { SectionExpand(emptySet()) {} }

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    connected: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onApplyMaxSpeed: (tiltbackKmh: Float, alarmKmh: Float) -> Unit,
    onServiceMode: () -> Unit,
    onThemeEditor: () -> Unit,
    onEditAlarm: (AlarmRule) -> Unit,
    onVoicePicker: () -> Unit,
    riderCard: RiderCard?,
    eucStatsBusy: Boolean,
    eucStatsMsg: String?,
    onEucStatsRegister: () -> Unit,
    onEucStatsRefresh: () -> Unit,
    onJoinLeaderboard: () -> Unit = {},
    onManageProfile: () -> Unit = {},
    expandedSections: Set<String>,
    onToggleSection: (String) -> Unit,
    hudStatus: String = "Off",
    onOverlayStudio: () -> Unit = {},
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {

            var query by remember { mutableStateOf("") }
            SettingsSearchField(c, query) { query = it }
            Spacer(Modifier.height(8.dp))

            // Render every shared section in its canonical order. The `when` is
            // used as an expression (via `.let`) so Kotlin enforces exhaustiveness:
            // add a SettingsSectionId in :shared and this stops compiling until iOS
            // handles it — that's the guarantee the two platforms can't drift.
            CompositionLocalProvider(
                LocalSettingsQuery provides query,
                LocalSectionExpand provides SectionExpand(expandedSections, onToggleSection),
            ) {
            SettingsSectionId.entries.forEach { id ->
                when (id) {
                    SettingsSectionId.General -> Section(c, "General", Icons.Filled.Tune, expandedDefault = true, keywords = "auto connect keep screen record recording motion idle backup") {
                        SwitchRow(c, "Auto-connect last wheel", settings.autoConnectLastWheel) { onUpdate { s -> s.copy(autoConnectLastWheel = it) } }
                        SwitchRow(c, "Keep screen on while riding", settings.keepScreenOn) { onUpdate { s -> s.copy(keepScreenOn = it) } }
                        SwitchRow(c, "Auto-record trips", settings.autoStartRecording) { onUpdate { s -> s.copy(autoStartRecording = it) } }
                        if (settings.autoStartRecording) {
                            SwitchRow(c, "Start when moving", settings.autoRecordStartInMotion) { onUpdate { s -> s.copy(autoRecordStartInMotion = it) } }
                            if (settings.autoRecordStartInMotion) {
                                SliderRow(c, "Stop after idle", "${settings.autoRecordStopIdleSeconds}s", settings.autoRecordStopIdleSeconds.toFloat(), 30f..600f) { onUpdate { s -> s.copy(autoRecordStopIdleSeconds = it.roundToInt()) } }
                            }
                        }
                    }

                    SettingsSectionId.Dashboard -> Section(c, "Dashboard", Icons.Filled.Dashboard, keywords = "columns tiles min max corner stats grid") {
                        LabelRow(c, "Metric tile columns")
                        Segmented(c, listOf("2", "3"), (settings.dashboardColumns - 2).coerceIn(0, 1)) { onUpdate { s -> s.copy(dashboardColumns = it + 2) } }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(c, "Show MIN / MAX corner stats", settings.statCorners) { onUpdate { s -> s.copy(statCorners = it) } }
                        Note(c, "Custom tile order + action-grid editor is Android-only for now.")
                    }

                    SettingsSectionId.Display -> Section(c, "Display", Icons.Filled.DisplaySettings, keywords = "units metric imperial custom km mph distance temperature theme dark light pure black gauge colour color band warn danger threshold") {
                        LabelRow(c, "Units")
                        // Selected system is DERIVED from the three per-unit choices,
                        // like Android: all-metric -> Metric, all-imperial -> Imperial,
                        // any other mix -> Custom (which reveals the per-unit pickers).
                        val unitSystem = when {
                            settings.unitSpeed == "kmh" && settings.unitDistance == "km" && settings.unitTemp == "C" -> 0
                            settings.unitSpeed == "mph" && settings.unitDistance == "mi" && settings.unitTemp == "F" -> 1
                            else -> 2
                        }
                        Segmented(c, listOf("Metric", "Imperial", "Custom"), unitSystem) { idx ->
                            onUpdate { s ->
                                when (idx) {
                                    0 -> s.copy(unitSpeed = "kmh", unitDistance = "km", unitTemp = "C")
                                    1 -> s.copy(unitSpeed = "mph", unitDistance = "mi", unitTemp = "F")
                                    // Tapping Custom from a preset nudges speed to m/s so the
                                    // Custom segment actually selects (matches Android); an
                                    // already-custom combo is left as the user set it.
                                    else -> if (unitSystem != 2) s.copy(unitSpeed = "ms") else s
                                }
                            }
                        }
                        if (unitSystem == 2) {
                            Spacer(Modifier.height(8.dp))
                            UnitPicker(c, "Speed", listOf("km/h" to "kmh", "mph" to "mph", "m/s" to "ms", "kn" to "kn"), settings.unitSpeed) { onUpdate { s -> s.copy(unitSpeed = it) } }
                            UnitPicker(c, "Distance", listOf("km" to "km", "mi" to "mi", "m" to "m", "ft" to "ft", "mil" to "mil"), settings.unitDistance) { onUpdate { s -> s.copy(unitDistance = it) } }
                            UnitPicker(c, "Temperature", listOf("°C" to "C", "°F" to "F", "K" to "K"), settings.unitTemp) { onUpdate { s -> s.copy(unitTemp = it) } }
                        }
                        Spacer(Modifier.height(10.dp))
                        LabelRow(c, "Theme")
                        Segmented(c, listOf("Light", "Dark", "Pure Black"), settings.theme) { onUpdate { s -> s.copy(theme = it) } }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(c, "Gauge color band (warn/danger)", settings.gaugeColorBand) { onUpdate { s -> s.copy(gaugeColorBand = it) } }
                        if (settings.gaugeColorBand) {
                            SliderRow(c, "Warn threshold", "${settings.gaugeOrangeThresholdPct}%", settings.gaugeOrangeThresholdPct.toFloat(), 40f..90f) { onUpdate { s -> s.copy(gaugeOrangeThresholdPct = it.roundToInt()) } }
                            SliderRow(c, "Danger threshold", "${settings.gaugeRedThresholdPct}%", settings.gaugeRedThresholdPct.toFloat(), 50f..95f) { onUpdate { s -> s.copy(gaugeRedThresholdPct = it.roundToInt()) } }
                        }
                        // "Customize theme colors" entry hidden (user request): the per-token
                        // editor wasn't on par with Android's interactive theme widget, so the
                        // 3 built-in themes are the supported set for now. onThemeEditor unused.
                    }

                    SettingsSectionId.Speed -> Section(c, "Speed", Icons.Filled.Speed, keywords = "calibration tiltback max alarm legal limit km/h apply wheel") {
                        SliderRow(c, "Speed calibration", "${if (settings.speedCalibrationPct >= 0f) "+" else ""}${(settings.speedCalibrationPct * 10).roundToInt() / 10f}%", settings.speedCalibrationPct, -15f..15f) {
                            onUpdate { s -> s.copy(speedCalibrationPct = it) }
                        }
                        // Tiltback + alarm write to the wheel LIVE on change, exactly
                        // like Android (updateTiltbackSpeed/updateAlarmSpeed call
                        // wheelRepository.setSpeed immediately) — no manual "Apply".
                        // onApplyMaxSpeed is a no-op without a connected wheel.
                        SliderRow(c, "Tiltback (max) speed", "${settings.tiltbackKmh.roundToInt()} km/h", settings.tiltbackKmh, 10f..70f) {
                            val tb = it; val al = settings.alarmKmh.coerceAtMost(tb)
                            onUpdate { s -> s.copy(tiltbackKmh = tb, alarmKmh = s.alarmKmh.coerceAtMost(tb)) }
                            onApplyMaxSpeed(tb, al)
                        }
                        SliderRow(c, "Alarm speed", "${settings.alarmKmh.roundToInt()} km/h", settings.alarmKmh, 5f..70f) {
                            val al = it; val tb = settings.tiltbackKmh.coerceAtLeast(al)
                            onUpdate { s -> s.copy(alarmKmh = al, tiltbackKmh = s.tiltbackKmh.coerceAtLeast(al)) }
                            onApplyMaxSpeed(tb, al)
                        }
                        // Legal-mode limits apply when Legal mode is toggled on (matches
                        // Android updateSafetyTiltback, which only persists the setting).
                        SliderRow(c, "Legal-mode tiltback", "${settings.legalTiltbackKmh.roundToInt()} km/h", settings.legalTiltbackKmh, 10f..40f) {
                            onUpdate { s -> s.copy(legalTiltbackKmh = it) }
                        }
                        SliderRow(c, "Legal-mode alarm", "${settings.legalAlarmKmh.roundToInt()} km/h", settings.legalAlarmKmh, 5f..40f) {
                            onUpdate { s -> s.copy(legalAlarmKmh = it) }
                        }
                        if (!connected) HintText(c, "Connect a wheel to write speed limits live.")
                    }

                    SettingsSectionId.Voice -> Section(c, "Voice", Icons.Filled.RecordVoiceOver, keywords = "tts text to speech announce report rate interval lights lock legal recording spoken voice") {
                        SwitchRow(c, "Text-to-speech announcements", settings.ttsEnabled) { onUpdate { s -> s.copy(ttsEnabled = it) } }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(c.surface)
                                .clickable { onVoicePicker() }.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Voice", color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(if (settings.voiceId.isBlank()) "System default" else "Custom", color = c.textSecondary, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("›", color = c.primary, fontSize = 16.sp)
                        }
                        run {
                            // Speed multiplier (1.0× = normal), matching Android's 0.5–2.5× slider.
                            // Legacy 0–100 values from before this rework fall back to 1.2×.
                            val mult = settings.speechRate.takeIf { it in 0.3f..2.5f } ?: 1.1f
                            val rl = (mult * 10).roundToInt()
                            SliderRow(c, "Speech rate", "${rl / 10}.${rl % 10}×", mult, 0.5f..2.5f) { onUpdate { s -> s.copy(speechRate = (it * 10).roundToInt() / 10f) } }
                        }
                        SliderRow(c, "Announce interval", "${settings.announceIntervalSec}s", settings.announceIntervalSec.toFloat(), 10f..300f) { onUpdate { s -> s.copy(announceIntervalSec = it.roundToInt()) } }
                        Spacer(Modifier.height(6.dp))
                        LabelRow(c, "Periodic report")
                        SwitchRow(c, "Report speed", settings.announceSpeed) { onUpdate { s -> s.copy(announceSpeed = it) } }
                        SwitchRow(c, "Report battery", settings.announceBattery) { onUpdate { s -> s.copy(announceBattery = it) } }
                        SwitchRow(c, "Report temperature", settings.announceTemp) { onUpdate { s -> s.copy(announceTemp = it) } }
                        Spacer(Modifier.height(6.dp))
                        LabelRow(c, "Spoken events")
                        SwitchRow(c, "Lights on / off", settings.announceLights) { onUpdate { s -> s.copy(announceLights = it) } }
                        SwitchRow(c, "Wheel lock / unlock", settings.announceWheelLock) { onUpdate { s -> s.copy(announceWheelLock = it) } }
                        SwitchRow(c, "Legal mode on / off", settings.announceLegalMode) { onUpdate { s -> s.copy(announceLegalMode = it) } }
                        SwitchRow(c, "Recording start / stop", settings.announceRecording) { onUpdate { s -> s.copy(announceRecording = it) } }
                        SwitchRow(c, "Wheel connected / disconnected", settings.announceConnection) { onUpdate { s -> s.copy(announceConnection = it) } }
                        SwitchRow(c, "Welcome on launch", settings.announceWelcome) { onUpdate { s -> s.copy(announceWelcome = it) } }
                    }

                    // Hidden on iOS — not supported on the v1 ride slice, so omitted
                    // entirely rather than shown as dead toggles (engine-sound synth,
                    // cloud sync, GPS-scheduled automations, maps/navigation, external
                    // GPS, Flic/Radar, Wear OS / Garmin). The shared enum still forces
                    // a deliberate show/hide decision whenever Android adds a section.
                    SettingsSectionId.Motor -> Section(c, "Motor sound", Icons.Filled.MusicNote, keywords = "engine motor sound exhaust vroom v8 v12 muffler gearbox idle decel backfire brake duck synth two stroke") {
                        SwitchRow(c, "Engine sound", settings.engineSoundEnabled) { onUpdate { s -> s.copy(engineSoundEnabled = it) } }
                        if (settings.engineSoundEnabled) {
                            Note(c, "Engine")
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                EngineProfile.PROFILES.forEach { p ->
                                    val sel = p.key == settings.engineType
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) c.primary else c.surfaceVariant)
                                            .clickable { onUpdate { s -> s.copy(engineType = p.key) } }.padding(horizontal = 12.dp, vertical = 7.dp),
                                    ) { Text(p.displayName, color = if (sel) c.onPrimary else c.textSecondary, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
                                }
                            }
                            SliderRow(c, "Volume", "${(settings.engineVolume * 100).roundToInt()}%", settings.engineVolume, 0f..1f) { onUpdate { s -> s.copy(engineVolume = it) } }
                            Note(c, "Muffler"); Segmented(c, listOf("Open", "Half", "Muffled"), listOf("OPEN", "HALF", "MUFFLED").indexOf(settings.engineMuffler).coerceAtLeast(0)) { i -> onUpdate { s -> s.copy(engineMuffler = listOf("OPEN", "HALF", "MUFFLED")[i]) } }
                            Note(c, "Gearbox"); Segmented(c, listOf("Off", "4-speed", "6-speed"), listOf("OFF", "FOUR", "SIX").indexOf(settings.engineGearbox).coerceAtLeast(0)) { i -> onUpdate { s -> s.copy(engineGearbox = listOf("OFF", "FOUR", "SIX")[i]) } }
                            Note(c, "Idle"); Segmented(c, listOf("Always", "Fade", "Moving"), listOf("ALWAYS", "FADE", "MOVING").indexOf(settings.engineIdleBehavior).coerceAtLeast(0)) { i -> onUpdate { s -> s.copy(engineIdleBehavior = listOf("ALWAYS", "FADE", "MOVING")[i]) } }
                            Note(c, "Deceleration"); Segmented(c, listOf("Smooth", "Standard", "Backfire"), listOf("SMOOTH", "STANDARD", "BACKFIRE").indexOf(settings.engineDecelChar).coerceAtLeast(0)) { i -> onUpdate { s -> s.copy(engineDecelChar = listOf("SMOOTH", "STANDARD", "BACKFIRE")[i]) } }
                            Note(c, "Engine brake"); Segmented(c, listOf("Off", "Light", "Strong"), listOf("OFF", "LIGHT", "STRONG").indexOf(settings.engineBrake).coerceAtLeast(0)) { i -> onUpdate { s -> s.copy(engineBrake = listOf("OFF", "LIGHT", "STRONG")[i]) } }
                            Note(c, "Duck on voice"); Segmented(c, listOf("Duck", "Pause", "Mix"), listOf("DUCK", "PAUSE", "MIX").indexOf(settings.engineDuckOnVoice).coerceAtLeast(0)) { i -> onUpdate { s -> s.copy(engineDuckOnVoice = listOf("DUCK", "PAUSE", "MIX")[i]) } }
                            Note(c, "Synthesizes a virtual engine from your live speed + PWM, through the phone speaker. iOS renders the synth engines; sampled engines fall back to synthesis.")
                        }
                    }

                    SettingsSectionId.Cloud -> Section(c, "Backup · EUC Stats", Icons.Filled.CloudUpload, keywords = "eucstats online backup leaderboard rank rider register upload cloud flag profile avatar delete export") {
                        val registered = settings.eucStatsStoreId.isNotBlank()
                        if (!registered) {
                            Text("Join the public leaderboard at eucstats.ried.no — back up your rides and share distance, top speed and rank.", color = c.textSecondary, fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            CloudButton(c, "Join leaderboard", enabled = !eucStatsBusy) { onJoinLeaderboard() }
                        } else {
                            val card = riderCard
                            if (card != null) {
                                LabelRow(c, "Your stats")
                                StatLine(c, "Total distance", "${card.totalKm.roundToInt()} km")
                                StatLine(c, "Trips", card.trips.toString())
                                StatLine(c, "Top speed", "${card.topSpeedKmh.roundToInt()} km/h")
                                if (card.mileageRank != null) StatLine(c, "Distance rank", "#${card.mileageRank}")
                                Spacer(Modifier.height(10.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CloudButton(c, "Manage profile", enabled = !eucStatsBusy) { onManageProfile() }
                                Spacer(Modifier.width(10.dp))
                                Text("Refresh", color = c.primary, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !eucStatsBusy) { onEucStatsRefresh() }.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            SwitchRow(c, "Back up trips online", settings.eucStatsEnabled) { onUpdate { s -> s.copy(eucStatsEnabled = it) } }
                            SwitchRow(c, "Auto-upload each ride", settings.eucStatsAutoUpload) { onUpdate { s -> s.copy(eucStatsAutoUpload = it) } }
                        }
                        if (eucStatsBusy) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.primary)
                        }
                        if (eucStatsMsg != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(eucStatsMsg, color = c.textSecondary, fontSize = 11.sp)
                        }
                        Note(c, "Trips back up to the leaderboard. Per-trip status + retry are in Recordings. Dev server: dev.eucstats.ried.no")
                    }

                    SettingsSectionId.Alarms -> Section(c, "Alarms", Icons.Filled.NotificationsActive, keywords = "alarm speed temperature pwm voltage current battery warn rule") {
                        if (settings.alarmRules.isEmpty()) {
                            Note(c, "No alarms. Add one to be warned on speed, temperature, PWM, voltage, current or battery.")
                        }
                        settings.alarmRules.forEach { rule ->
                            AlarmRuleRow(c, rule, onEdit = { onEditAlarm(rule) }) { en ->
                                onUpdate { s -> s.copy(alarmRules = s.alarmRules.map { if (it.id == rule.id) it.copy(enabled = en) else it }) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
                                .clickable {
                                    val nextId = (settings.alarmRules.maxOfOrNull { it.id } ?: 0L) + 1L
                                    onEditAlarm(AlarmRule(id = nextId))
                                }.padding(vertical = 11.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("+ Add alarm", color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    SettingsSectionId.Automations -> Section(c, "Automations", Icons.Filled.FlashlightOn, keywords = "auto lights sunset sunrise sun gps automatic headlight") {
                        SwitchRow(c, "Auto-lights (sunset / sunrise)", settings.autoLightsEnabled) { onUpdate { s -> s.copy(autoLightsEnabled = it) } }
                        if (settings.autoLightsEnabled) {
                            SliderRow(c, "On before sunset", "${settings.autoLightsOnMinutesBefore} min", settings.autoLightsOnMinutesBefore.toFloat(), 0f..120f) { onUpdate { s -> s.copy(autoLightsOnMinutesBefore = it.roundToInt()) } }
                            SliderRow(c, "Off after sunrise", "${settings.autoLightsOffMinutesAfter} min", settings.autoLightsOffMinutesAfter.toFloat(), 0f..120f) { onUpdate { s -> s.copy(autoLightsOffMinutesAfter = it.roundToInt()) } }
                            Note(c, "Uses your GPS location to compute sunset/sunrise. Tap the light button to override for the rest of the ride.")
                        }
                    }

                    SettingsSectionId.Navigator -> Section(c, "Navigator", Icons.Filled.Navigation, keywords = "route navigation map gps turn directions waypoint arrival off route router geocoder osrm nominatim guidance full path") {
                        val imp = settings.unitDistance == "mi" || settings.unitDistance == "ft"
                        fun distLabel(m: Int) = if (imp) "${(m * 3.28084).roundToInt()} ft" else "$m m"
                        SwitchRow(c, "Solve the full route", settings.navSolveFullPath) { onUpdate { s -> s.copy(navSolveFullPath = it) } }
                        Note(c, "On: route through every stop at once. Off: route only the next leg and dash the rest.")
                        SwitchRow(c, "Voice guidance", settings.navVoiceGuidance) { onUpdate { s -> s.copy(navVoiceGuidance = it) } }
                        SliderRow(c, "Arrival radius", distLabel(settings.navArrivalRadiusM), settings.navArrivalRadiusM.toFloat(), 5f..100f) { onUpdate { s -> s.copy(navArrivalRadiusM = (it / 5f).roundToInt() * 5) } }
                        SliderRow(c, "Off-route tolerance", distLabel(settings.navOffRouteToleranceM), settings.navOffRouteToleranceM.toFloat(), 15f..150f) { onUpdate { s -> s.copy(navOffRouteToleranceM = (it / 5f).roundToInt() * 5) } }
                        Note(c, "Routing endpoints (advanced) — leave blank for the default OpenStreetMap services.")
                        CloudTextField(c, settings.navGeocoderUrl, "Geocoder URL (Nominatim)") { onUpdate { s -> s.copy(navGeocoderUrl = it.trim()) } }
                        CloudTextField(c, settings.navRouterUrl, "Router URL (OSRM)") { onUpdate { s -> s.copy(navRouterUrl = it.trim()) } }
                    }

                    SettingsSectionId.Location -> Section(c, "Location", Icons.Filled.Tune, keywords = "gps location speed permission satellite announce") {
                        Note(c, "EUC Planet uses your phone GPS for speed, trip tracking and sunset-based auto-lights. Allow location access when prompted (or in iOS Settings ▸ EUC Planet ▸ Location).")
                        SwitchRow(c, "Announce GPS acquired / lost", settings.announceGps) { onUpdate { s -> s.copy(announceGps = it) } }
                    }

                    SettingsSectionId.Integration -> Section(c, "Integration", Icons.Filled.Settings, keywords = "hud heads up display external screen handlebar motoeye websocket ip port stream") {
                        SwitchRow(c, "Stream to HUD", settings.hudEnabled) { onUpdate { s -> s.copy(hudEnabled = it) } }
                        if (settings.hudEnabled) {
                            CloudTextField(c, settings.hudIp, "HUD IP address (e.g. 192.168.4.1)") { onUpdate { s -> s.copy(hudIp = it.trim()) } }
                            CloudTextField(c, if (settings.hudPort > 0) settings.hudPort.toString() else "", "Port (default 28080)") { v ->
                                val p = v.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 28080
                                onUpdate { s -> s.copy(hudPort = p) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "HUD link: $hudStatus",
                                color = when (hudStatus) { "Connected" -> c.statusGood; "Connecting…" -> c.primary; else -> c.textSecondary },
                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                            Note(c, "Find the IP on your HUD's screen. The phone streams telemetry to ws://<ip>:<port>/state at 5 Hz — same protocol as the Android HUD.")
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
                                .clickable { onOverlayStudio() }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Design overlay layout", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("›", color = c.primary, fontSize = 16.sp)
                        }
                    }

                    SettingsSectionId.Watch -> {}
                }.let { /* exhaustive: a new SettingsSectionId without a branch fails to compile here */ }
            }
            } // end CompositionLocalProvider(LocalSettingsQuery)

            Spacer(Modifier.height(16.dp))
            Text(
                "Settings persist to device storage (NSUserDefaults on iOS). Speed limits apply to the wheel live.",
                color = c.textDisabled, fontSize = 10.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(
    c: AppThemeColors,
    title: String,
    icon: ImageVector,
    expandedDefault: Boolean = false,
    keywords: String = "",
    content: @Composable () -> Unit,
) {
    val query = LocalSettingsQuery.current.trim().lowercase()
    val searching = query.isNotEmpty()
    // While searching, hide non-matching sections; auto-expand the ones that match.
    if (searching && !title.lowercase().contains(query) && !keywords.lowercase().contains(query)) return
    val exp = LocalSectionExpand.current
    val isExpanded = searching || title in exp.open
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { exp.toggle(title) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(17.dp))
            Text(title, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(24.dp),
            )
        }
        if (isExpanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun CloudButton(c: AppThemeColors, label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) c.onPrimary else c.textDisabled, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (enabled) c.primary else c.surfaceVariant)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsSearchField(c: AppThemeColors, value: String, onChange: (String) -> Unit) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp)) },
        placeholder = { Text("Search settings", color = c.textDisabled, fontSize = 14.sp) },
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
            focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
            focusedIndicatorColor = c.primary, unfocusedIndicatorColor = c.outline,
            cursorColor = c.primary,
        ),
    )
}

@Composable
private fun LabelRow(c: AppThemeColors, label: String) {
    Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Note(c: AppThemeColors, text: String) {
    Text(text, color = c.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CloudTextField(c: AppThemeColors, value: String, placeholder: String, onChange: (String) -> Unit) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = c.textDisabled, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
            focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
            focusedIndicatorColor = c.primary, unfocusedIndicatorColor = c.outline,
            cursorColor = c.primary,
        ),
    )
}

@Composable
private fun StatLine(c: AppThemeColors, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** A labelled per-unit picker: [options] is (display label -> stored key). */
@Composable
private fun UnitPicker(c: AppThemeColors, label: String, options: List<Pair<String, String>>, current: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
        val idx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        Segmented(c, options.map { it.first }, idx) { onSelect(options[it].second) }
    }
}

@Composable
private fun AlarmRuleRow(c: AppThemeColors, rule: AlarmRule, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val metric = AlarmMetric.parse(rule.metric)
    val cmp = AlarmComparator.parse(rule.comparator)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant)
            .clickable { onEdit() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name.ifBlank { metric.label }, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${metric.label} ${cmp.symbol} ${rule.threshold.roundToInt()} ${metric.unit}", color = c.textSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = rule.enabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary, checkedTrackColor = c.switchOn,
                uncheckedTrackColor = c.switchOff, uncheckedBorderColor = c.outline, checkedBorderColor = c.switchOn,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text("›", color = c.primary, fontSize = 16.sp)
    }
}

@Composable
private fun SwitchRow(c: AppThemeColors, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.switchOn,
                uncheckedTrackColor = c.switchOff,
                uncheckedBorderColor = c.outline,
                checkedBorderColor = c.switchOn,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    c: AppThemeColors,
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = current, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = c.sliderActive,
                activeTrackColor = c.sliderActive,
                inactiveTrackColor = c.sliderTrack,
            ),
        )
    }
}

@Composable
private fun HintText(c: AppThemeColors, text: String) {
    Text(text, color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

@Composable
private fun Segmented(c: AppThemeColors, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) c.primary else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt, color = if (sel) c.onPrimary else c.textSecondary,
                    fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
internal fun ScreenTopBar(c: AppThemeColors, title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(c.topBar).padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { onBack() }.padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(6.dp))
        Text(title, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
