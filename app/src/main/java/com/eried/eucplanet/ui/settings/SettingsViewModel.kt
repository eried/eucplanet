package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.location.Location
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.CustomBleCommand
import com.eried.eucplanet.data.model.PairedSurface
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.eucstats.EucStatsRepository
import com.eried.eucplanet.data.eucstats.RiderCard
import com.eried.eucplanet.data.sync.BackupEntry
import com.eried.eucplanet.data.sync.BackupOutcome
import com.eried.eucplanet.data.sync.RestorableRider
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.service.VoiceService
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val wheelRepository: WheelRepository,
    private val voiceService: VoiceService,
    private val tripRepository: TripRepository,
    private val syncManager: SyncManager,
    private val automationManager: AutomationManager,
    private val wearBridge: com.eried.eucplanet.wear.WearBridge,
    private val garminBridge: com.eried.eucplanet.garmin.GarminBridge,
    private val engineSoundEngine: com.eried.eucplanet.audio.EngineSoundEngine,
    val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val overlayPresetStore: com.eried.eucplanet.data.store.OverlayPresetStore,
    private val themeController: com.eried.eucplanet.ui.theme.ThemeController,
    hudCommandSink: com.eried.eucplanet.service.hud.HudCommandSink,
    private val eucStatsRepository: EucStatsRepository,
) : ViewModel() {

    /** Live HUD protocol compatibility for the Settings/Integration card.
     *  Surfaces the "update HUD" / "update phone" hints. EXACT means nothing
     *  to show. */
    val hudVersionCompat = hudCommandSink.hudVersionCompat
    /** APK version string the HUD reported on pairing, e.g. "0.1.6". Null
     *  when no HUD is currently paired. */
    val hudVersion = hudCommandSink.hudVersion
    val hudEverConnected = hudCommandSink.hudEverConnected

    /**
     * Full preset-list snapshot for the HUD overlay picker dialog:
     *   - folderAvailable: rider has configured a sync folder
     *   - savedPresets: rider-saved presets in the folder
     *   - bundledPortrait: starter presets whose elements are mostly upright
     *   - bundledLandscape: starter presets whose elements are pre-rotated
     *
     * The portrait / landscape split uses the same rule as the Studio: more
     * than half of the elements rotated => landscape.
     */
    suspend fun loadHudOverlayLists(): HudOverlayLists {
        val hasFolder = overlayPresetStore.presetFolderAvailable()
        val saved = if (hasFolder) overlayPresetStore.listPresets() else emptyList()
        val portrait = mutableListOf<String>()
        val landscape = mutableListOf<String>()
        overlayPresetStore.listBundledPresets().forEach { name ->
            val els = overlayPresetStore.loadBundledPreset(name)?.elements
            if (els != null && els.isNotEmpty() &&
                els.count { it.rotationDeg != 0f } * 2 > els.size
            ) {
                landscape.add(name)
            } else {
                portrait.add(name)
            }
        }
        return HudOverlayLists(hasFolder, saved, portrait, landscape)
    }

    data class HudOverlayLists(
        val folderAvailable: Boolean,
        val savedPresets: List<String>,
        val bundledPortrait: List<String>,
        val bundledLandscape: List<String>
    )

    /** Resolve a preset name -> JSON via OverlayPresetStore, then persist
     *  it on the settings. The HUD link reads the json and ships it to
     *  the HUD on the next state frame. */
    fun pickHudOverlay(name: String) {
        viewModelScope.launch {
            if (name.isBlank()) {
                updateHudCustomOverlay("", "")
                return@launch
            }
            // Bundled first (works without backup folder); saved second.
            val preset = overlayPresetStore.loadBundledPreset(name)
                ?: overlayPresetStore.loadPreset(name)
                ?: return@launch
            val json = com.eried.eucplanet.data.store.OverlayPresetJson
                .toJson(preset).toString()
            updateHudCustomOverlay(name, json)
        }
    }

    /** Manual "wake the watch app" trigger, fires the same /euc/wake
     *  message that MainActivity.onResume() sends. Lets the user verify
     *  pairing without restarting the phone app. */
    fun testWatchWake() = wearBridge.pingWatchToWake()

    val autoLightsSuspended: StateFlow<Boolean> = automationManager.autoLightsSuspended

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val maxSpeedCap: StateFlow<Float> = wheelRepository.maxSpeedCap

    val isConnected: StateFlow<Boolean> = wheelRepository.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Unified view of every paired companion device — Wear OS + Garmin —
     * for the Settings "Device" region. Bridges expose raw name lists and
     * delivery-rate flows; we combine, tag with [PairedSurface.Kind], and
     * stamp each entry with the surface's current update rate so the UI
     * card can show what the rider's actual frame rate looks like on the
     * wire (Wear OS at the configured publish interval, Garmin at the
     * CIQ SDK-rate-capped delivery rate).
     */
    val pairedSurfaces: StateFlow<List<PairedSurface>> =
        combine(
            wearBridge.pairedNodes,
            garminBridge.pairedDevices,
            settingsRepository.settings,
            garminBridge.deliveryRateHz,
            garminBridge.lastSuccessAtMs
        ) { args ->
            @Suppress("UNCHECKED_CAST") val wear = args[0] as List<String>
            @Suppress("UNCHECKED_CAST") val garmin = args[1] as List<String>
            val settings = args[2] as AppSettings?
            val garminHz = args[3] as Double
            val lastGarminMs = args[4] as Long
            val wearHz = settings?.let { wearRateHzFor(it.watchUpdateRate) } ?: 5.0
            // "Active" = the bridge has delivered a frame in the last 3 s.
            // Reading the timestamp (not the rolling rate) avoids the
            // 0/1/0/1 sampling artifact that happens when the wire is
            // running at exactly 1 Hz and our rate poller is also 1 Hz.
            val garminActive = lastGarminMs > 0L &&
                (System.currentTimeMillis() - lastGarminMs) < 3_000L
            val wearActive = wear.isNotEmpty()
            buildList {
                wear.forEach { name ->
                    add(PairedSurface(PairedSurface.Kind.WEAR_OS, name, wearActive, wearHz))
                }
                garmin.forEach { name ->
                    add(PairedSurface(PairedSurface.Kind.GARMIN, name, garminActive, garminHz))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Configured publish-interval → Hz mapping for the Wear OS surface,
     *  matching the values WearBridge actually drives the publish loop at. */
    private fun wearRateHzFor(key: String): Double = when (key) {
        "CONSERVATIVE" -> 1000.0 / 750.0
        "FAST" -> 1000.0 / 150.0
        else -> 1000.0 / 200.0 // NORMAL
    }

    /** True when at least one Wear OS device is paired right now. Settings
     *  uses this to hide toggles that have no effect on Garmin-only setups
     *  (auto-start, keep-screen-on). */
    val hasWearOsPaired: StateFlow<Boolean> =
        wearBridge.pairedNodes
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when at least one Garmin device is paired. The Watch tab uses this to
     *  badge Wear-only features (auto-start, dial rotation, …) as unsupported on
     *  Garmin when a Garmin AND a Wear OS watch are both paired. */
    val hasGarminPaired: StateFlow<Boolean> =
        garminBridge.pairedDevices
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * True when at least one paired surface has bindable hardware buttons:
     *  - Any Garmin device (every Garmin watch ships ≥2 physical buttons,
     *    and our CIQ Delegate maps the universal Start + Up-hold pair to
     *    `stem1` and `stem2`).
     *  - A Galaxy Watch Ultra on Wear OS — the only Wear OS device that
     *    delivers `KEYCODE_STEM_1` (orange Action) and `KEYCODE_STEM_2`
     *    (bottom side) to third-party apps. Detected by friendly-name
     *    containing "Ultra" (case-insensitive); Pixel Watch / Galaxy
     *    Watch 4/5/6 non-Ultra are excluded.
     *
     * Settings → Watch uses this to gate the "Hardware buttons" picker
     * section so riders without compatible devices don't see dropdowns
     * that wouldn't do anything.
     */
    val hasHardwareButtonCapableWatch: StateFlow<Boolean> =
        wearBridge.pairedNodes.combine(garminBridge.pairedDevices) { wear, garmin ->
            garmin.isNotEmpty() || wear.any { it.contains("Ultra", ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Engine-sound preview buttons should only fire while the rider is parked,
     * because previewing pushes synthetic telemetry through the same audio path
     * as the real ride and would clash. Disconnected = no ride happening, so
     * preview is allowed. Connected + sub-walking-pace = parked.
     */
    val engineParked: StateFlow<Boolean> = wheelRepository.wheelData
        .map { kotlin.math.abs(it.speed) < 0.5f }
        .combine(isConnected) { speedParked, conn -> !conn || speedParked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val availableVoices: StateFlow<List<VoiceOption>> = voiceService.availableVoices

    val currentLocation: StateFlow<Location?> = tripRepository.currentLocation

    private fun update(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(current.transform())
        }
    }

    fun updateTiltbackSpeed(value: Float) {
        update {
            // Now that the slider floor is 0 km/h (Begode parity), the legal
            // cap can land on 0 without going negative.
            val legalCap = (value - 1f).coerceAtLeast(0f)
            copy(
                tiltbackSpeedKmh = value,
                alarmSpeedKmh = alarmSpeedKmh.coerceAtMost(value),
                safetyTiltbackKmh = safetyTiltbackKmh.coerceAtMost(legalCap),
                safetyAlarmKmh = safetyAlarmKmh.coerceAtMost(legalCap)
            )
        }
        viewModelScope.launch {
            val s = settingsRepository.get()
            wheelRepository.setSpeed(value, s.alarmSpeedKmh.coerceAtMost(value))
        }
    }

    fun updateAlarmSpeed(value: Float) {
        update { copy(alarmSpeedKmh = value, tiltbackSpeedKmh = tiltbackSpeedKmh.coerceAtLeast(value)) }
        viewModelScope.launch {
            val s = settingsRepository.get()
            wheelRepository.setSpeed(s.tiltbackSpeedKmh.coerceAtLeast(value), value)
        }
    }
    fun updateSafetyTiltback(value: Float) =
        update {
            val capped = value.coerceAtMost((tiltbackSpeedKmh - 1f).coerceAtLeast(0f))
            copy(safetyTiltbackKmh = capped, safetyAlarmKmh = safetyAlarmKmh.coerceAtMost(capped))
        }

    fun updateSafetyAlarm(value: Float) =
        update { copy(safetyAlarmKmh = value, safetyTiltbackKmh = safetyTiltbackKmh.coerceAtLeast(value)) }
    fun updateVoiceEnabled(enabled: Boolean) = update { copy(voiceEnabled = enabled) }
    fun updateVoiceOnlyWhenConnected(enabled: Boolean) = update { copy(voiceOnlyWhenConnected = enabled) }
    fun updateVoiceInterval(seconds: Int) = update { copy(voiceIntervalSeconds = seconds) }
    fun updateVoiceSpeechRate(v: Float) = update { copy(voiceSpeechRate = v) }
    fun updateVoiceReportSpeed(v: Boolean) = update { copy(voiceReportSpeed = v) }
    fun updateVoiceReportBattery(v: Boolean) = update { copy(voiceReportBattery = v) }
    fun updateVoiceReportTemp(v: Boolean) = update { copy(voiceReportTemp = v) }
    fun updateVoiceReportPwm(v: Boolean) = update { copy(voiceReportPwm = v) }
    fun updateVoiceReportDistance(v: Boolean) = update { copy(voiceReportDistance = v) }
    fun updateVoiceReportTime(v: Boolean) = update { copy(voiceReportTime = v) }
    fun updateVoiceReportNavigation(v: Boolean) = update { copy(voiceReportNavigation = v) }
    fun updateTriggerReportSpeed(v: Boolean) = update { copy(triggerReportSpeed = v) }
    fun updateTriggerReportBattery(v: Boolean) = update { copy(triggerReportBattery = v) }
    fun updateTriggerReportTemp(v: Boolean) = update { copy(triggerReportTemp = v) }
    fun updateTriggerReportPwm(v: Boolean) = update { copy(triggerReportPwm = v) }
    fun updateTriggerReportDistance(v: Boolean) = update { copy(triggerReportDistance = v) }
    fun updateTriggerReportTime(v: Boolean) = update { copy(triggerReportTime = v) }
    fun updateTriggerReportNavigation(v: Boolean) = update { copy(triggerReportNavigation = v) }
    fun updateVoiceLocale(tag: String) {
        // Explicit voice pick sets the override flag so a later UI-language
        // change re-prompts ("switch voice too?") instead of silently
        // clobbering the rider's chosen voice.
        update { copy(voiceLocale = tag, voiceLocaleOverridden = true) }
        voiceService.setVoiceLocale(tag)
    }
    fun updateVoiceAudioFocus(v: String) = update { copy(voiceAudioFocus = v) }
    fun updateVoiceOutputChannel(v: String) = update { copy(voiceOutputChannel = v) }

    // Motor sound
    fun updateEngineSoundEnabled(v: Boolean) = update { copy(engineSoundEnabled = v) }
    fun updateEngineType(v: String) = update { copy(engineType = v) }
    fun updateEngineVolume(v: Float) = update { copy(engineVolume = v.coerceIn(0f, 1f)) }
    fun updateEngineVolumeAutoEnabled(v: Boolean) = update { copy(engineVolumeAutoEnabled = v) }
    fun updateEngineVolumeAutoCurve(curve: String) = update { copy(engineVolumeAutoCurve = curve) }
    fun updateEngineMuffler(v: String) = update { copy(engineMuffler = v) }
    fun updateEngineGearbox(v: String) = update { copy(engineGearbox = v) }
    fun updateEngineIdleBehavior(v: String) = update { copy(engineIdleBehavior = v) }
    fun updateEngineDecelChar(v: String) = update { copy(engineDecelChar = v) }
    fun updateEngineBrake(v: String) = update { copy(engineBrake = v) }
    fun updateEngineDuckOnVoice(v: String) = update { copy(engineDuckOnVoice = v) }
    fun updateEngineHeadphonesOnly(v: Boolean) = update { copy(engineHeadphonesOnly = v) }

    fun previewEngine(key: String) {
        viewModelScope.launch {
            val s = settingsRepository.get()
            engineSoundEngine.previewProfile(
                key = key,
                volume = s.engineVolume,
                muffler = s.engineMuffler,
                gearbox = s.engineGearbox
            )
        }
    }

    /**
     * Section preview, play a short clip that demonstrates the section's current setting.
     *
     * [scenario] picks the motion pattern fed to the engine:
     *  - "DEFAULT": idle → mid-rev → idle (Muffler, shows the muffler tone across the rev range)
     *  - "GEARBOX": speed sweep so the virtual gearbox actually shifts
     *  - "DECEL":   accel under load then sharp off-throttle to trigger pops / backfire
     *  - "BRAKE":   sustained coast at speed so the engine-brake whine engages
     */
    fun previewEngineSection(scenario: String) {
        viewModelScope.launch {
            val s = settingsRepository.get()
            engineSoundEngine.previewProfile(
                key = s.engineType,
                volume = s.engineVolume,
                muffler = s.engineMuffler,
                gearbox = s.engineGearbox,
                scenario = scenario
            )
        }
    }
    fun updateAutoRecord(v: Boolean) = update { copy(autoRecord = v) }
    fun updateAutoRecordStartInMotion(v: Boolean) = update { copy(autoRecordStartInMotion = v) }
    fun updateAutoRecordStopIdleSeconds(v: Int) = update { copy(autoRecordStopIdleSeconds = v.coerceIn(30, 600)) }
    fun updateAutoConnect(v: Boolean) = update { copy(autoConnect = v) }
    fun updateChargingAutoOpen(v: Boolean) = update { copy(chargingAutoOpen = v) }
    fun updateChargingDashboardIcon(v: Boolean) = update { copy(chargingDashboardIcon = v) }
    fun updateBackButtonAction(value: String) = update { copy(backButtonAction = value) }
    fun updateSpeedCalibrationOffsetPct(v: Float) = update {
        // Round to 0.1 % granularity so the value reads cleanly across UI,
        // backup JSON, and per-wheel profile storage.
        val rounded = (kotlin.math.round(v * 10f) / 10f).coerceIn(-15f, 15f)
        copy(speedCalibrationOffsetPct = rounded)
    }
    fun updateRaceboxMapX(v: String) = update { copy(raceboxMapX = v) }
    fun updateRaceboxMapY(v: String) = update { copy(raceboxMapY = v) }
    fun updateRaceboxMapZ(v: String) = update { copy(raceboxMapZ = v) }
    fun updateGpsPrioritizeExternal(v: Boolean) = update { copy(gpsPrioritizeExternal = v) }
    fun updateGpsShowOnDashboard(v: Boolean) = update { copy(gpsShowOnDashboard = v) }
    fun setRaceboxAxisMap(mapX: String, mapY: String, mapZ: String) =
        update { copy(raceboxMapX = mapX, raceboxMapY = mapY, raceboxMapZ = mapZ) }

    // Automations
    fun updateAutoLightsEnabled(v: Boolean) {
        update { copy(autoLightsEnabled = v) }
        // Toggling the setting itself clears any session-level suspension
        automationManager.clearLightsSuspension()
        // Apply the correct state immediately instead of waiting for the next 60s tick
        if (v) automationManager.triggerImmediateLightEvaluation()
    }
    fun updateAutoLightsOnMinutes(v: Int) = update { copy(autoLightsOnMinutesBefore = v) }
    fun updateAutoLightsOffMinutes(v: Int) = update { copy(autoLightsOffMinutesAfter = v) }
    fun updateAutoVolumeEnabled(v: Boolean) = update { copy(autoVolumeEnabled = v) }
    fun updateAutoVolumeCurve(curve: String) = update { copy(autoVolumeCurve = curve) }

    // Voice report: recording
    fun updateVoiceReportRecording(v: Boolean) = update { copy(voiceReportRecording = v) }
    fun updateTriggerReportRecording(v: Boolean) = update { copy(triggerReportRecording = v) }

    fun testSpeak(text: String) {
        viewModelScope.launch {
            val s = settingsRepository.get()
            voiceService.testSpeak(text, s.voiceSpeechRate, s.voiceLocale)
        }
    }

    // Special announcements
    fun updateAnnounceWheelLock(v: Boolean) = update { copy(announceWheelLock = v) }
    fun updateAnnounceLights(v: Boolean) = update { copy(announceLights = v) }
    fun updateAnnounceRecording(v: Boolean) = update { copy(announceRecording = v) }
    fun updateAnnounceConnection(v: Boolean) = update { copy(announceConnection = v) }
    fun updateAnnounceGps(v: Boolean) = update { copy(announceGps = v) }
    fun updateAnnounceSafetyMode(v: Boolean) = update { copy(announceSafetyMode = v) }
    fun updateAnnounceWelcome(v: Boolean) = update { copy(announceWelcome = v) }

    fun updateVoiceReportOrder(order: String) = update { copy(voiceReportOrder = order) }

    // Measurement units: speed, distance and temperature are independently
    // selectable. Metric/Imperial/Custom is a derived label (see Units.unitSystemOf).
    fun setUnitSpeed(v: String) = update { copy(unitSpeed = v) }
    fun setUnitDistance(v: String) = update { copy(unitDistance = v) }
    fun setUnitTemp(v: String) = update { copy(unitTemp = v) }

    /** Sets all three per-unit fields at once from the Metric/Imperial preset. */
    fun applyUnitPreset(imperial: Boolean) = update {
        copy(
            unitSpeed = if (imperial) "mph" else "kmh",
            unitDistance = if (imperial) "mi" else "km",
            unitTemp = if (imperial) "F" else "C"
        )
    }

    /**
     * Nudges an exact preset into a genuinely custom combo (knots + Norwegian
     * mile) in ONE write, so tapping Custom actually lands on Custom. Two
     * separate setUnit* calls would race, each reads settings independently
     * and the last write wins, so only one field would stick.
     */
    fun applyCustomNudge() = update {
        copy(unitSpeed = "kn", unitDistance = "mil")
    }

    // Wear OS companion
    fun updateWatchKeepScreenOn(v: Boolean) = update { copy(watchKeepScreenOn = v) }
    fun updatePhoneKeepScreenOn(v: Boolean) = update { copy(phoneKeepScreenOn = v) }
    fun updateWatchAutoStart(v: Boolean) = update { copy(watchAutoStart = v) }
    fun updateWatchCloseOnExit(v: Boolean) = update { copy(watchCloseOnExit = v) }
    fun updateWatchShowWheelBattery(v: Boolean) = update { copy(watchShowWheelBattery = v) }
    fun updateWatchShowPhoneBattery(v: Boolean) = update { copy(watchShowPhoneBattery = v) }
    fun updateWatchShowWatchBattery(v: Boolean) = update { copy(watchShowWatchBattery = v) }
    fun updateWatchPwmDisplay(v: String) = update { copy(watchPwmDisplay = v) }
    fun updateWatchShowSpeedUnit(v: Boolean) = update { copy(watchShowSpeedUnit = v) }
    fun updateWatchEnableGpsSpeed(v: Boolean) = update { copy(watchEnableGpsSpeed = v) }
    fun updateWatchPrioritizePwm(v: Boolean) = update { copy(watchPrioritizePwm = v) }
    fun updateWatchDialRotationDeg(v: Int) = update { copy(watchDialRotationDeg = v.coerceIn(-90, 90)) }
    fun updateWatchStem1Click(action: String) = update { copy(watchStem1Click = action) }
    fun updateWatchStem1Hold(action: String) = update { copy(watchStem1Hold = action) }
    fun updateWatchStem2Click(action: String) = update { copy(watchStem2Click = action) }
    fun updateWatchStem2Hold(action: String) = update { copy(watchStem2Hold = action) }
    fun updateWatchScreen1Click(action: String) = update { copy(watchScreen1Click = action) }
    fun updateWatchScreen1Hold(action: String) = update { copy(watchScreen1Hold = action) }
    fun updateWatchScreen2Click(action: String) = update { copy(watchScreen2Click = action) }
    fun updateWatchScreen2Hold(action: String) = update { copy(watchScreen2Hold = action) }
    fun updateWatchHapticOnAction(v: Boolean) = update { copy(watchHapticOnAction = v) }
    fun updateWatchUpdateRate(v: String) = update { copy(watchUpdateRate = v) }
    fun updateWheelNameDisplay(v: String) = update { copy(wheelNameDisplay = v) }
    fun updateWatchShowNavigation(v: Boolean) = update { copy(watchShowNavigation = v) }

    // HUD companion
    fun updateHudServerEnabled(v: Boolean) = update { copy(hudServerEnabled = v) }
    fun updateHudServerPort(v: Int) = update {
        // Match the dial port range. Below 1024 the HUD's listening socket
        // couldn't bind without root; above 65535 isn't a port.
        copy(hudServerPort = v.coerceIn(1024, 65535))
    }
    fun updateHudIp(v: String) = update { copy(hudIp = v.trim()) }
    /**
     * Set the HUD's "Custom" screen to mirror an Overlay Studio preset.
     * Caller supplies the resolved JSON so the ViewModel doesn't need to
     * know about asset/IO paths. Empty name clears the choice.
     */
    fun updateHudCustomOverlay(name: String, json: String) = update {
        copy(hudCustomOverlayName = name.trim(), hudCustomOverlayJson = json)
    }

    /** Update the HUD's map tile style. See AppSettings.hudMapStyle for
     *  the recognised codes; empty = "use HUD's compiled default." */
    fun updateHudMapStyle(code: String) = update { copy(hudMapStyle = code) }

    /** Map tile contrast, 50..200 percent (100 = neutral). */
    fun updateHudMapContrast(pct: Int) = update {
        copy(hudMapContrastPct = pct.coerceIn(50, 200))
    }

    /** Map tile brightness offset, -100..100 (0 = neutral). */
    fun updateHudMapBrightness(pct: Int) = update {
        copy(hudMapBrightnessPct = pct.coerceIn(-100, 100))
    }

    /** Screens that are ON by default on a fresh install. Order is the
     *  default carousel order. Updated for preview-3 tester feedback:
     *  ALL screens ship enabled so a new rider scrolls through the full
     *  carousel and decides what to keep -- previously "opt-in defaults
     *  off" meant testers never saw the Power / TripStats / Compass /
     *  Safety / BigClock screens unless they read the release notes
     *  carefully. The rider can still trim them from Personalize ->
     *  HUD screens. */
    val defaultEnabledHudScreens: List<String> = listOf(
        "Dashboard", "Camera", "Telemetry",
        "Custom", "CustomCam", "MapNav", "Map", "Nav",
        "Power", "TripStats", "Compass", "Safety", "BigClock"
    )

    /** Stable identifiers for every HUD screen the app knows about,
     *  mirroring the HudUiController.Screen enum on the HUD side. Order
     *  determines where new screens appear in the Personalize list. */
    val knownHudScreens: List<String> = defaultEnabledHudScreens

    /** Parse the rider's saved enabled-set, falling back to defaults
     *  when nothing has been saved. Strict member check ensures stale
     *  values from a future schema don't leak in. */
    private fun parseEnabledSet(raw: String): Set<String> {
        val parsed = raw.split(",")
            .map { it.trim() }
            .filter { it in knownHudScreens }
            .toSet()
        return parsed.ifEmpty {
            if (raw.isBlank()) defaultEnabledHudScreens.toSet()
            else emptySet() // raw set explicitly, but nothing recognised
        }
    }

    /** Parse the rider's saved row order, falling back to the default
     *  full known order when nothing has been saved. Always returns
     *  every known screen exactly once -- if the saved value is
     *  partial (e.g. shipped before some screens were added), the
     *  newer screens are appended at the end. */
    private fun parseOrder(raw: String): List<String> {
        val parsed = raw.split(",")
            .map { it.trim() }
            .filter { it in knownHudScreens }
            .distinct()
        return parsed + knownHudScreens.filter { it !in parsed }
    }

    /** Toggle whether a HUD screen is enabled. Touches ONLY the enabled
     *  set -- the row's position in the Personalize list is preserved
     *  because hudScreensOrder is independent. Enforces a minimum of
     *  one enabled screen so the carousel can't be emptied. */
    fun setHudScreenEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val set = parseEnabledSet(current.hudScreensEnabled).toMutableSet()
            if (enabled) {
                set.add(id)
            } else {
                if (set.size <= 1 && id in set) return@launch // keep one
                set.remove(id)
            }
            settingsRepository.update(
                current.copy(hudScreensEnabled = set.joinToString(","))
            )
        }
    }

    /** Reorder HUD screens. Touches ONLY hudScreensOrder. Toggle state
     *  is preserved. from/to are indices into the full ordered list,
     *  which is what the Personalize ReorderableColumn renders. */
    fun moveHudScreen(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val order = parseOrder(current.hudScreensOrder).toMutableList()
            if (fromIndex in order.indices && toIndex in order.indices) {
                val item = order.removeAt(fromIndex)
                order.add(toIndex, item)
                settingsRepository.update(
                    current.copy(hudScreensOrder = order.joinToString(","))
                )
            }
        }
    }

    // Navigator
    fun updateNavVoiceEnabled(v: Boolean) = update { copy(navVoiceEnabled = v) }
    fun updateNavArrivalRadius(v: Int) = update { copy(navArrivalRadiusM = v.coerceIn(5, 100)) }
    fun updateNavOffRouteTolerance(v: Int) = update { copy(navOffRouteToleranceM = v.coerceIn(15, 150)) }
    fun updateNavSolveFullPath(v: Boolean) = update { copy(navSolveFullPath = v) }

    /** Cheat: clears the welcome-tour-seen flag so it replays next time the dashboard shows. */
    fun resetWelcomeTutorial() = update { copy(welcomeTutorialSeen = false) }
    fun updateNavDefaultTravelMode(v: String) = update { copy(navDefaultTravelMode = v) }
    fun updateNavGeocoderUrl(v: String) = update { copy(navGeocoderUrl = v) }
    fun updateNavRouterUrl(v: String) = update { copy(navRouterUrl = v) }

    private val _ttsSwitchPrompt = MutableStateFlow<String?>(null)
    val ttsSwitchPrompt: StateFlow<String?> = _ttsSwitchPrompt.asStateFlow()

    // Appearance
    fun updateLanguage(v: String) {
        val appLang = if (v.isBlank()) "en" else v
        // Pull just the primary language subtag (e.g. "es" from "es-419", "pt" from "pt-BR")
        // so es-419 / pt-BR don't confuse the equality check below.
        val appLangPrimary = appLang.substringBefore('-').lowercase()
        viewModelScope.launch {
            val current = settingsRepository.get()
            val ttsLang = voiceService.currentVoiceLanguage().lowercase()
            if (!current.voiceLocaleOverridden) {
                // Voice has been auto-following the UI language. Switch both
                // in a single write so they don't race, and skip the prompt
                // entirely - the rider never picked a voice manually.
                val tag = voiceService.pickVoiceForLanguage(appLang)
                if (tag != null) {
                    settingsRepository.update(current.copy(language = v, voiceLocale = tag))
                    voiceService.setVoiceLocale(tag)
                } else {
                    settingsRepository.update(current.copy(language = v))
                }
                com.eried.eucplanet.util.LocaleHelper.apply(v)
            } else if (ttsLang != appLangPrimary) {
                // Override set AND voice differs from new UI language. Defer
                // until the rider answers the prompt; show it in the current
                // language so they can still read Cancel.
                _ttsSwitchPrompt.value = v
            } else {
                // Override set but voice already matches the new language
                // (rare, but possible if the rider's manual pick happens to
                // align). Apply directly without prompting.
                settingsRepository.update(current.copy(language = v))
                com.eried.eucplanet.util.LocaleHelper.apply(v)
            }
        }
    }

    /**
     * User accepted: switch the app language and ALSO switch the TTS voice
     * to match. Clears the override flag so future language changes auto-sync
     * without re-prompting.
     */
    fun acceptTtsSwitch() {
        val v = _ttsSwitchPrompt.value ?: return
        viewModelScope.launch {
            val current = settingsRepository.get()
            val appLang = if (v.isBlank()) "en" else v
            val tag = voiceService.pickVoiceForLanguage(appLang)
            // CRITICAL: single write so language + voiceLocale + override are
            // committed atomically. Two separate update() calls would race
            // each other's read-then-write and lose one of the fields - the
            // bug that previously left the voice unchanged even after the
            // rider tapped "yes, switch".
            val updated = if (tag != null) {
                current.copy(language = v, voiceLocale = tag, voiceLocaleOverridden = false)
            } else {
                current.copy(language = v, voiceLocaleOverridden = false)
            }
            settingsRepository.update(updated)
            com.eried.eucplanet.util.LocaleHelper.apply(v)
            if (tag != null) voiceService.setVoiceLocale(tag)
            _ttsSwitchPrompt.value = null
        }
    }

    /**
     * User chose to switch language but keep the existing TTS voice. Sets
     * the override flag so future language changes continue to prompt
     * instead of auto-switching the voice the rider just confirmed.
     */
    fun dismissTtsSwitch() {
        val v = _ttsSwitchPrompt.value ?: return
        update { copy(language = v, voiceLocaleOverridden = true) }
        com.eried.eucplanet.util.LocaleHelper.apply(v)
        _ttsSwitchPrompt.value = null
    }

    /**
     * User cancelled the language switch entirely (e.g. picked the wrong
     * language by mistake).
     */
    fun cancelLanguageSwitch() { _ttsSwitchPrompt.value = null }


    // --- Custom theme system ---
    private val _themeChoices = MutableStateFlow(
        com.eried.eucplanet.ui.theme.ThemeChoices(
            com.eried.eucplanet.ui.theme.BuiltInThemes.all.map { it.name },
            emptyList(),
            emptyList(),
            folderAvailable = false
        )
    )
    /** Built-ins + saved customs for the theme combo. Call [refreshThemeChoices]
     *  on screen entry / after a save so newly-saved themes appear. */
    val themeChoices: StateFlow<com.eried.eucplanet.ui.theme.ThemeChoices> = _themeChoices.asStateFlow()

    fun refreshThemeChoices() {
        viewModelScope.launch { _themeChoices.value = themeController.availableThemes() }
    }

    fun selectTheme(name: String) {
        viewModelScope.launch { themeController.selectTheme(name) }
    }

    fun selectUnsavedTheme(base: String) {
        viewModelScope.launch { themeController.selectUnsaved(base) }
    }

    fun setThemeEditorEnabled(enabled: Boolean) {
        viewModelScope.launch { themeController.setEditorEnabled(enabled) }
    }
    fun updateShowGaugeColorBand(v: Boolean) = update { copy(showGaugeColorBand = v) }
    fun updateGaugeThresholds(orangePct: Int, redPct: Int) = update {
        // Keep all three bands visible: green ≥ 5%, orange ≥ 4%, red ≥ 5%. That way
        // the user can collapse any zone to a thin sliver but never to zero, which
        // would make the band confusing on the gauge.
        val o = orangePct.coerceIn(5, 91)
        val r = redPct.coerceIn((o + 4).coerceAtMost(95), 95)
        copy(gaugeOrangeThresholdPct = o, gaugeRedThresholdPct = r)
    }
    fun updateCurrentDisplayMode(v: String) = update { copy(currentDisplayMode = v) }

    // Volume keys
    fun updateVolumeKeysEnabled(v: Boolean) = update { copy(volumeKeysEnabled = v) }
    fun updateVolumeUpClick(v: String) = update { copy(volumeUpClick = v) }
    fun updateVolumeUpHold(v: String) = update { copy(volumeUpHold = v) }
    fun updateVolumeDownClick(v: String) = update { copy(volumeDownClick = v) }
    fun updateVolumeDownHold(v: String) = update { copy(volumeDownHold = v) }

    // Cloud sync
    private val _cloudEvent = MutableStateFlow<CloudEvent?>(null)
    val cloudEvent: StateFlow<CloudEvent?> = _cloudEvent.asStateFlow()

    fun consumeCloudEvent() { _cloudEvent.value = null }

    fun updateSyncFolder(uri: Uri) {
        viewModelScope.launch {
            val ok = syncManager.setSyncFolder(uri)
            _cloudEvent.value = if (ok) CloudEvent.FolderSet else CloudEvent.FolderFailed
        }
    }

    fun clearSyncFolder() {
        viewModelScope.launch { syncManager.clearSyncFolder() }
    }

    fun backupSettingsNow() {
        viewModelScope.launch {
            val ok = syncManager.backupSettings()
            _cloudEvent.value = if (ok) CloudEvent.BackupSuccess else CloudEvent.BackupFailed
        }
    }

    /** Sanitised name preview for the named-backup dialog. Null when the input
     *  is empty or strips to empty so the caller can disable the Save button. */
    fun sanitizeBackupName(raw: String): String? = syncManager.sanitizeBackupName(raw)

    /**
     * Saves the rider's named backup. Surfaces [BackupOutcome.AlreadyExists] as
     * [CloudEvent.BackupExists] so the dialog can prompt to overwrite.
     */
    fun backupSettingsNamed(name: String, overwrite: Boolean) {
        viewModelScope.launch {
            _cloudEvent.value = when (syncManager.backupSettingsAs(name, overwrite)) {
                BackupOutcome.Saved -> CloudEvent.BackupNamedSuccess(name)
                BackupOutcome.AlreadyExists -> CloudEvent.BackupExists(name)
                BackupOutcome.Failed -> CloudEvent.BackupFailed
            }
        }
    }

    /** Latest list of backup files in the sync folder, for the restore picker. */
    suspend fun listBackups(): List<BackupEntry> = syncManager.listSettingsBackups()

    fun restoreSettingsFrom(fileName: String) {
        viewModelScope.launch {
            val ok = restoreWithSafety(fileName)
            if (ok) refreshOnlineUploadCard()
            _cloudEvent.value = if (ok) CloudEvent.RestoreSuccess else CloudEvent.RestoreFailed
        }
    }

    // ---- eucstats rider recovery from a linked backup folder ----

    private val _restorableRider = MutableStateFlow<RestorableRider?>(null)
    /** A rider identity found in the linked folder's backup that this phone could
     *  adopt. Non-null → UI shows the "continue as this rider?" prompt. */
    val restorableRider: StateFlow<RestorableRider?> = _restorableRider.asStateFlow()

    private val _startOnboarding = MutableStateFlow(false)
    /** One-shot: joinOrRecover found no previous profile, so the UI should open
     *  the create-profile onboarding. */
    val startOnboarding: StateFlow<Boolean> = _startOnboarding.asStateFlow()
    fun consumeStartOnboarding() { _startOnboarding.value = false }

    private val _rejoinConfirm = MutableStateFlow(false)
    /** True when the rider tapped Join but this phone ALREADY has a profile
     *  (e.g. they unlinked, the store_id is retained). The UI confirms rejoining
     *  as that existing rider rather than re-enabling silently. */
    val rejoinConfirm: StateFlow<Boolean> = _rejoinConfirm.asStateFlow()
    fun dismissRejoinConfirm() { _rejoinConfirm.value = false }
    fun confirmRejoin() {
        _rejoinConfirm.value = false
        setOnlineUploadEnabled(true)
    }

    /** Tapped "Join". If this phone already has a rider, confirm rejoining as it.
     *  Otherwise, if the linked folder's backup holds a previous profile, surface
     *  it for a continue-or-not prompt; else start onboarding for a new one. */
    fun joinOrRecover() {
        viewModelScope.launch {
            val current = settingsRepository.get().eucstatsStoreId
            if (current != null) {
                _rejoinConfirm.value = true
                return@launch
            }
            val found = syncManager.findRestorableRider()
            if (found != null) {
                _restorableRider.value = found
            } else {
                _startOnboarding.value = true
            }
        }
    }

    fun dismissRestorableRider() { _restorableRider.value = null }

    /** Adopt the rider carried by [rider] (restore its backup), snapshotting the
     *  current rider first if we'd be replacing a different one. */
    fun restoreRider(rider: RestorableRider) {
        viewModelScope.launch {
            val ok = restoreWithSafety(rider.fileName)
            _restorableRider.value = null
            if (ok) refreshOnlineUploadCard()
            _cloudEvent.value = if (ok) CloudEvent.RestoreSuccess else CloudEvent.RestoreFailed
        }
    }

    /**
     * Restore a backup, but if it would replace an EXISTING, DIFFERENT rider
     * identity, first save a timestamped safety copy of the current settings so
     * the previous rider stays recoverable even if the rider tapped through the
     * confirm. Applies to every restore path (recovery prompt and manual picker).
     */
    private suspend fun restoreWithSafety(fileName: String): Boolean {
        val currentId = settingsRepository.get().eucstatsStoreId
        val incoming = syncManager.peekRider(fileName)
        if (currentId != null && incoming != null && incoming.storeId != currentId) {
            syncManager.snapshotBeforeRestore()
        }
        return syncManager.restoreSettingsFrom(fileName)
    }

    /** Reset all rider configuration to factory defaults (keeps pairings, sync
     *  folder and saved backups). Reuses the restore merge in [SyncManager]. */
    fun restoreFactoryDefaults() {
        viewModelScope.launch {
            val ok = syncManager.restoreFactoryDefaults()
            _cloudEvent.value = if (ok) CloudEvent.RestoreSuccess else CloudEvent.RestoreFailed
        }
    }

    fun restoreSettingsNow() {
        viewModelScope.launch {
            val ok = syncManager.restoreSettings()
            _cloudEvent.value = if (ok) CloudEvent.RestoreSuccess else CloudEvent.RestoreFailed
        }
    }

    fun retryUploadsNow() {
        viewModelScope.launch {
            val s = settingsRepository.get()
            syncManager.enqueueTripUpload(s)
            _cloudEvent.value = CloudEvent.UploadEnqueued
        }
    }

    // --- Two-way trip sync ---
    // Runs in SyncManager's app-scoped coroutine so it survives Settings navigation.

    val syncRunning: StateFlow<Boolean> = syncManager.syncRunning
    val syncProgress: StateFlow<Pair<Int, Int>?> = syncManager.syncProgress
    val syncConflictPrompt: StateFlow<Int?> = syncManager.syncConflictPrompt

    init {
        viewModelScope.launch {
            syncManager.syncResult.collect { result ->
                if (result == null) return@collect
                _cloudEvent.value = when (result) {
                    is SyncResult.NoFolder -> CloudEvent.SyncNoFolder
                    is SyncResult.Finished -> CloudEvent.SyncFinished(result.count)
                }
                syncManager.consumeSyncResult()
            }
        }
    }

    fun syncAllTrips() = syncManager.startSync()
    fun resolveSyncConflict(choice: SyncChoice) = syncManager.resolveSyncConflict(choice)
    fun cancelSyncConflict() = syncManager.cancelSyncConflict()

    fun moveReportItem(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val known = listOf("Speed", "Battery", "Temp", "PWM", "Distance", "Recording", "Time", "Navigation")
            val saved = current.voiceReportOrder.split(",").map { it.trim() }.filter { it in known }
            val items = (saved + known.filter { it !in saved }).toMutableList()
            if (fromIndex in items.indices && toIndex in items.indices) {
                val item = items.removeAt(fromIndex)
                items.add(toIndex, item)
                settingsRepository.update(current.copy(voiceReportOrder = items.joinToString(",")))
            }
        }
    }

    // ---- Dashboard layout (catalog model) ----
    //
    // Mental model:
    //   - knownDashboardMetrics / knownDashboardActions are the CATALOG: every
    //     static key the dashboard knows about. The pool in the layout editor
    //     renders this list alphabetically (sorted by display label).
    //   - dashboardMetricOrder / dashboardActionOrder are the GRID: the first
    //     ACTIVE_DASHBOARD_TILE_COUNT entries are what the rider sees on the
    //     active dashboard. The catalog stays full regardless.
    //   - Dynamic instances (`M:uuid` composites, `C:uuid` custom tiles,
    //     `G:uuid` action groups) only live in the grid order, never the
    //     catalog. Templates (+MULTI / +GROUP) spawn fresh instances when
    //     dragged onto a slot.
    //
    // Drag semantics (DashboardDragController.sourceFromGrid drives the branch):
    //   - Grid → grid (sourceFromGrid=true): SWAP via moveDashboard*ToIndex
    //   - Pool → grid (sourceFromGrid=false): COPY via setDashboard*AtIndex
    //     (catalog stays full; displaced grid tile is discarded)
    //   - Grid → pool: discard. Dynamic → delete definition. Static metric →
    //     demote (slot becomes empty custom tile). Static action → no-op
    //     (catalog always has it; rider uses Restore slot for default).
    //
    // To add a new metric:
    //   1. Append its key to knownDashboardMetrics below.
    //   2. Add a label entry in SettingsScreen.kt::metricChipLabel and an
    //      optional description in metricDescription.
    //   3. Add a placeholder/value in metricPlaceholderValue.
    //   4. Optionally an accent color in metricAccentColor and stats support
    //      flag in metricSupportsStats.
    //   5. When the dashboard renderer (DashboardScreen.kt) is wired in
    //      phase 2, also surface the metric there.
    //
    // To add a new ACTION: see the comment on knownDashboardActions below —
    // multiple files need touching because Flic / volume keys / WearOS have
    // their own definitions today (see audit comment there).
    //
    // Saved orders are sanitized against the catalog so unknown tokens
    // (renames, removals) drop silently and newly-added entries appear at
    // the end of the order on first read.
    val knownDashboardMetrics = listOf(
        // Currently active by default — keep these 6 first so a fresh install
        // mirrors the hard-coded layout byte-for-byte.
        "BATTERY", "TEMPERATURE", "VOLTAGE", "CURRENT", "LOAD", "TRIP",
        // Pool — already-buffered or simple-to-derive metrics.
        "SPEED", "POWER", "ODOMETER",
        "MOTOR_POWER", "BATTERY_POWER",
        "BATTERY_1", "BATTERY_2",
        "PITCH", "ROLL",
        "G_FORCE", "LATERAL_G", "FORWARD_G",
        "TORQUE", "DYN_SPEED_LIMIT", "DYN_CURRENT_LIMIT",
        // Individual temperature sensors (WheelData.temperatures by index).
        "MOTOR_TEMP", "CONTROLLER_TEMP", "BATTERY_TEMP",
        // Derived trip metrics (computed from speed/voltage/current histories
        // once Phase 3 aggregation lands).
        "HEADROOM", "TRIP_TIME", "TRIP_MAX_SPEED", "AVG_TRIP_SPEED",
        "WH_CONSUMED", "RANGE_ESTIMATE", "WH_PER_KM",
        // Phone + GPS feeds — sourced outside WheelData.
        "PHONE_BATTERY", "GPS_ALTITUDE", "GPS_SPEED", "GPS_HEADING",
        "GPS_ACCURACY",
        // Derived motion + pack health — slope/altitude integration and
        // wheel-firmware fields some boards expose.
        "SLOPE", "ASCENT", "DESCENT", "MOTOR_RPM", "REGEN_WH",
        // Connectivity diagnostic — useful when debugging dropouts.
        "BT_RSSI",
        // Extras targeted at composite-tile cells (small text, no
        // sparkline) -- they also render fine as standalone tiles.
        "LAT_LONG", "WHEEL_MAX_SPEED", "WHEEL_ALARM_SPEED", "PC_MODE", "LIGHT_ON"
    )
    /**
     * Dashboard-eligible actions, derived from [ActionCatalog]. Adding a
     * new action is a single entry in `ActionCatalog.all` — no edit here.
     *
     * The dashboard surface accepts every action regardless of
     * [ActionSpec.isEyesFreeSafe]; physical surfaces (Flic / volume key /
     * watch) filter via [ActionCatalog.keysFor]. See ActionCatalog.kt for
     * the full design.
     */
    val knownDashboardActions: List<String> =
        com.eried.eucplanet.data.model.ActionCatalog.keysFor(
            com.eried.eucplanet.data.model.ActionSurface.DASHBOARD
        )

    /**
     * Per-slot default layout for the action grid's "Restore slot": the shipped
     * default order ([AppSettings.dashboardActionOrder]) leads, then the rest of
     * the catalog. Mirrors how knownDashboardMetrics' first entries ARE the
     * metric default — knownDashboardActions is raw catalog declaration order,
     * which didn't match the shipped grid, so restoring by catalog index put the
     * wrong action in slots 2/4/5. Restore uses this so slot N gets the action
     * that actually ships there.
     */
    val defaultDashboardActions: List<String> =
        (AppSettings().dashboardActionOrder.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
            knownDashboardActions).distinct()

    private fun sanitize(
        saved: String,
        known: List<String>,
        dynamic: Set<String> = emptySet()
    ): List<String> {
        // EMPTY_SLOT_KEY entries are kept verbatim so "intentionally blank"
        // grid slots from a move-and-empty drop survive a round-trip through
        // settings persistence. Renderers map the sentinel to a blank Box.
        val s = saved.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && (it == EMPTY_SLOT_KEY || it in known || it in dynamic) }
        // Append known keys that aren't already in the saved order so the pool
        // surfaces new defaults after an app upgrade. Dynamic IDs are NOT
        // auto-added — they only exist while the rider has them on the grid
        // or until they delete the underlying composite/group definition.
        return s + known.filter { it !in s }
    }

    fun dashboardMetricOrder(s: AppSettings): List<String> =
        sanitize(
            s.dashboardMetricOrder,
            knownDashboardMetrics,
            listCompositeMetrics(s).keys + listCustomTiles(s).keys
        )
    fun dashboardActionOrder(s: AppSettings): List<String> =
        sanitize(s.dashboardActionOrder, knownDashboardActions, listActionGroups(s).keys + listCustomBle(s).keys)

    // --- Composite metrics (multi-cell tiles) -----------------------------
    //
    // A composite is one grid tile that stacks 2 or 3 sub-metric current
    // values in a chosen layout. Definitions live in [AppSettings.
    // dashboardCompositeMetrics] keyed by `M:<uuid>` so the rider can have
    // several composite instances on the grid simultaneously. The same ID
    // appears in [AppSettings.dashboardMetricOrder] to position the tile.

    fun listCompositeMetrics(s: AppSettings): Map<String, MetricComposite> {
        val root = parseSlotStatsRoot(s.dashboardCompositeMetrics)
        val out = LinkedHashMap<String, MetricComposite>()
        val it = root.keys()
        while (it.hasNext()) {
            val id = it.next()
            val node = root.optJSONObject(id) ?: continue
            val layout = runCatching {
                CompositeLayout.valueOf(node.optString("layout", CompositeLayout.ROW2.name))
            }.getOrDefault(CompositeLayout.ROW2)
            val cellsArr = node.optJSONArray("cells")
            val cells = if (cellsArr != null) {
                (0 until cellsArr.length()).mapNotNull { cellsArr.optString(it).takeIf { s -> s.isNotEmpty() } }
            } else emptyList()
            // Parallel "cellStats" array. Missing entries (older composites
            // saved before per-cell stats existed) default to CURRENT so
            // the rider's prior layout keeps looking the same.
            val statsArr = node.optJSONArray("cellStats")
            val cellStats: List<DashboardStat> = if (statsArr != null) {
                (0 until statsArr.length()).map { idx ->
                    runCatching {
                        DashboardStat.valueOf(statsArr.optString(idx, DashboardStat.CURRENT.name))
                    }.getOrDefault(DashboardStat.CURRENT)
                }
            } else List(cells.size) { DashboardStat.CURRENT }
            out[id] = MetricComposite(layout, cells, cellStats)
        }
        return out
    }

    fun getCompositeMetric(s: AppSettings, id: String): MetricComposite? = listCompositeMetrics(s)[id]

    /**
     * Creates a new composite-metric instance with default content, inserts
     * its ID into [AppSettings.dashboardMetricOrder] at [insertAtIndex] (and
     * drops whatever previously occupied that slot back to the pool), and
     * returns the new ID so the caller can route the rider into the edit
     * sheet.
     */
    fun createCompositeMetricAt(insertAtIndex: Int): String {
        val newId = "${COMPOSITE_METRIC_PREFIX}${java.util.UUID.randomUUID().toString().take(8)}"
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCompositeMetrics)
            val node = org.json.JSONObject()
                .put("layout", CompositeLayout.ROW2.name)
                .put("cells", org.json.JSONArray(listOf("SPEED", "BATTERY")))
            root.put(newId, node)

            // Insert into the order at the target index, replacing whatever
            // was there. The displaced key is appended so it doesn't vanish.
            val order = sanitize(
                current.dashboardMetricOrder,
                knownDashboardMetrics,
                root.keys().asSequence().toSet()
            ).toMutableList()
            val safeIdx = insertAtIndex.coerceIn(0, order.size)
            if (safeIdx < order.size) {
                val displaced = order.removeAt(safeIdx)
                order.add(safeIdx, newId)
                order.add(displaced)
            } else {
                order.add(newId)
            }

            settingsRepository.update(
                current.copy(
                    dashboardCompositeMetrics = root.toString(),
                    dashboardMetricOrder = order.joinToString(",")
                )
            )
        }
        return newId
    }

    fun updateCompositeMetric(
        id: String,
        layout: CompositeLayout,
        cells: List<String>,
        cellStats: List<DashboardStat> = List(cells.size) { DashboardStat.CURRENT }
    ) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCompositeMetrics)
            val node = root.optJSONObject(id) ?: org.json.JSONObject()
            node.put("layout", layout.name)
            val truncatedCells = cells.take(layout.cellCount)
            val truncatedStats = cellStats.take(layout.cellCount).let { existing ->
                // Pad with CURRENT if the rider widened the layout before
                // picking a stat for the new cell.
                if (existing.size >= truncatedCells.size) existing
                else existing + List(truncatedCells.size - existing.size) { DashboardStat.CURRENT }
            }
            node.put("cells", org.json.JSONArray(truncatedCells))
            node.put("cellStats", org.json.JSONArray(truncatedStats.map { it.name }))
            root.put(id, node)
            settingsRepository.update(current.copy(dashboardCompositeMetrics = root.toString()))
        }
    }

    fun deleteCompositeMetric(id: String) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCompositeMetrics)
            root.remove(id)
            val order = current.dashboardMetricOrder
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != id }
                .joinToString(",")
            settingsRepository.update(
                current.copy(
                    dashboardCompositeMetrics = root.toString(),
                    dashboardMetricOrder = order
                )
            )
        }
    }

    // --- Action groups (one button → popover with N sub-actions) ----------

    fun listActionGroups(s: AppSettings): Map<String, ActionGroup> {
        val root = parseSlotStatsRoot(s.dashboardActionGroups)
        val out = LinkedHashMap<String, ActionGroup>()
        val it = root.keys()
        while (it.hasNext()) {
            val id = it.next()
            val node = root.optJSONObject(id) ?: continue
            val name = node.optString("name", "")
            val icon = node.optString("icon", GROUP_DEFAULT_ICON).ifEmpty { GROUP_DEFAULT_ICON }
            val arr = node.optJSONArray("actions")
            val actions = if (arr != null) {
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
            } else emptyList()
            out[id] = ActionGroup(name = name, icon = icon, actions = actions)
        }
        return out
    }

    fun getActionGroup(s: AppSettings, id: String): ActionGroup? = listActionGroups(s)[id]

    fun createActionGroupAt(insertAtIndex: Int): String {
        val newId = "${ACTION_GROUP_PREFIX}${java.util.UUID.randomUUID().toString().take(8)}"
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardActionGroups)
            val node = org.json.JSONObject()
                .put("name", "")
                .put("icon", GROUP_DEFAULT_ICON)
                .put("actions", org.json.JSONArray(emptyList<String>()))
            root.put(newId, node)

            val order = sanitize(
                current.dashboardActionOrder,
                knownDashboardActions,
                root.keys().asSequence().toSet() + listCustomBle(current).keys
            ).toMutableList()
            val safeIdx = insertAtIndex.coerceIn(0, order.size)
            if (safeIdx < order.size) {
                val displaced = order.removeAt(safeIdx)
                order.add(safeIdx, newId)
                order.add(displaced)
            } else {
                order.add(newId)
            }

            settingsRepository.update(
                current.copy(
                    dashboardActionGroups = root.toString(),
                    dashboardActionOrder = order.joinToString(",")
                )
            )
        }
        return newId
    }

    fun updateActionGroup(id: String, name: String, icon: String, actions: List<String>) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardActionGroups)
            val node = root.optJSONObject(id) ?: org.json.JSONObject()
            node.put("name", name)
            node.put("icon", icon.ifEmpty { GROUP_DEFAULT_ICON })
            node.put("actions", org.json.JSONArray(actions.take(4)))
            root.put(id, node)
            settingsRepository.update(current.copy(dashboardActionGroups = root.toString()))
        }
    }

    fun deleteActionGroup(id: String) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardActionGroups)
            root.remove(id)
            val order = current.dashboardActionOrder
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != id }
                .joinToString(",")
            settingsRepository.update(
                current.copy(
                    dashboardActionGroups = root.toString(),
                    dashboardActionOrder = order
                )
            )
        }
    }

    // --- Custom BLE commands (rider-authored raw frames, family-scoped) ----

    fun listCustomBle(s: AppSettings): Map<String, CustomBleCommand> =
        CustomBleCommand.parseAll(s.dashboardCustomBle)

    fun getCustomBle(s: AppSettings, id: String): CustomBleCommand? = listCustomBle(s)[id]

    fun createCustomBleAt(insertAtIndex: Int): String {
        val newId = CustomBleCommand.newId(java.util.UUID.randomUUID().toString().take(8))
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCustomBle)
            // Default the family to whatever wheel is currently connected,
            // so a rider creating a tile on an InMotion V14 doesn't end up
            // with a "veteran"-targeted command that silently no-ops at
            // dispatch (the family-gate in resolveForDispatch drops every
            // frame whose family doesn't match the connected wheel). Falls
            // back to "veteran" only when nothing is connected at create
            // time -- the rider can change it from the editor anyway.
            val initialFamily = wheelRepository.connectedFamilyId ?: "veteran"
            root.put(
                newId,
                org.json.JSONObject()
                    .put("label", "")
                    .put("icon", CUSTOM_BLE_DEFAULT_ICON)
                    .put("family", initialFamily)
                    .put("frames", org.json.JSONArray(emptyList<String>()))
            )
            val order = sanitize(
                current.dashboardActionOrder,
                knownDashboardActions,
                listActionGroups(current).keys + root.keys().asSequence().toSet()
            ).toMutableList()
            val safeIdx = insertAtIndex.coerceIn(0, order.size)
            if (safeIdx < order.size) {
                val displaced = order.removeAt(safeIdx)
                order.add(safeIdx, newId)
                order.add(displaced)
            } else {
                order.add(newId)
            }
            settingsRepository.update(
                current.copy(
                    dashboardCustomBle = root.toString(),
                    dashboardActionOrder = order.joinToString(",")
                )
            )
        }
        return newId
    }

    fun updateCustomBle(
        id: String,
        label: String,
        icon: String,
        family: String,
        frames: List<ByteArray>
    ) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCustomBle)
            val node = root.optJSONObject(id) ?: org.json.JSONObject()
            node.put("label", label)
            node.put("icon", icon.ifEmpty { CUSTOM_BLE_DEFAULT_ICON })
            node.put("family", family)
            node.put("frames", org.json.JSONArray(frames.map { CustomBleCommand.bytesToHex(it) }))
            root.put(id, node)
            settingsRepository.update(current.copy(dashboardCustomBle = root.toString()))
        }
    }

    fun deleteCustomBle(id: String) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCustomBle)
            root.remove(id)
            val order = current.dashboardActionOrder
                .split(",").map { it.trim() }
                .filter { it.isNotEmpty() && it != id }
                .joinToString(",")
            settingsRepository.update(
                current.copy(dashboardCustomBle = root.toString(), dashboardActionOrder = order)
            )
        }
    }

    // --- Custom tiles (rider text + icon + optional URL / QR action) ----

    fun listCustomTiles(s: AppSettings): Map<String, CustomTile> {
        val root = parseSlotStatsRoot(s.dashboardCustomTiles)
        val out = LinkedHashMap<String, CustomTile>()
        val it = root.keys()
        while (it.hasNext()) {
            val id = it.next()
            val node = root.optJSONObject(id) ?: continue
            val text = node.optString("text", "")
            val rawIcon = node.optString("icon", CUSTOM_TILE_DEFAULT_ICON).ifEmpty { CUSTOM_TILE_DEFAULT_ICON }
            // Migration: the old default icon was "EMPTY" (outlined checkbox).
            // The new default is "INFO" (filled "i"). Existing tiles whose
            // saved icon is "EMPTY" are almost certainly the never-edited
            // default rather than a deliberate pick (no rider asks for the
            // square-checkbox icon), so upgrade them on load. A rider who
            // genuinely wanted EMPTY can re-pick it from the icon list.
            val icon = if (rawIcon == "EMPTY") CUSTOM_TILE_DEFAULT_ICON else rawIcon
            val action = runCatching {
                CustomTileAction.valueOf(node.optString("action", CustomTileAction.NONE.name))
            }.getOrDefault(CustomTileAction.NONE)
            val url = node.optString("url", "")
            out[id] = CustomTile(text = text, icon = icon, action = action, url = url)
        }
        return out
    }

    fun getCustomTile(s: AppSettings, id: String): CustomTile? = listCustomTiles(s)[id]

    fun createCustomTileAt(insertAtIndex: Int): String {
        val newId = "${CUSTOM_TILE_PREFIX}${java.util.UUID.randomUUID().toString().take(8)}"
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCustomTiles)
            val node = org.json.JSONObject()
                .put("text", "")
                .put("icon", CUSTOM_TILE_DEFAULT_ICON)
                .put("action", CustomTileAction.NONE.name)
                .put("url", "")
            root.put(newId, node)

            val order = sanitize(
                current.dashboardMetricOrder,
                knownDashboardMetrics,
                listCompositeMetrics(current).keys + root.keys().asSequence().toSet()
            ).toMutableList()
            val safeIdx = insertAtIndex.coerceIn(0, order.size)
            if (safeIdx < order.size) {
                val displaced = order.removeAt(safeIdx)
                order.add(safeIdx, newId)
                order.add(displaced)
            } else {
                order.add(newId)
            }

            settingsRepository.update(
                current.copy(
                    dashboardCustomTiles = root.toString(),
                    dashboardMetricOrder = order.joinToString(",")
                )
            )
        }
        return newId
    }

    fun updateCustomTile(id: String, text: String, icon: String, action: CustomTileAction, url: String) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCustomTiles)
            val node = root.optJSONObject(id) ?: org.json.JSONObject()
            node.put("text", text)
            node.put("icon", icon.ifEmpty { CUSTOM_TILE_DEFAULT_ICON })
            node.put("action", action.name)
            node.put("url", url)
            root.put(id, node)
            settingsRepository.update(current.copy(dashboardCustomTiles = root.toString()))
        }
    }

    /**
     * Demotes an active-grid metric to the pool and spawns a new empty
     * custom tile in the slot it vacated. The rider then taps the new tile
     * to fill in their text / URL / QR. No-op when [metricKey] isn't in the
     * active portion of the grid — dragging a pool pill back to the pool
     * shouldn't create custom tiles.
     */
    fun demoteMetricToCustomTile(metricKey: String) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(
                current.dashboardMetricOrder,
                knownDashboardMetrics,
                listCompositeMetrics(current).keys + listCustomTiles(current).keys
            ).toMutableList()
            val activeCount = ACTIVE_DASHBOARD_TILE_COUNT
            val idx = items.indexOf(metricKey)
            if (idx < 0 || idx >= activeCount) return@launch

            // Create the new custom tile with defaults; rider edits it via tap.
            val newId = "${CUSTOM_TILE_PREFIX}${java.util.UUID.randomUUID().toString().take(8)}"
            val root = parseSlotStatsRoot(current.dashboardCustomTiles)
            val node = org.json.JSONObject()
                .put("text", "")
                .put("icon", CUSTOM_TILE_DEFAULT_ICON)
                .put("action", CustomTileAction.NONE.name)
                .put("url", "")
            root.put(newId, node)

            // Swap: new custom tile takes the vacated slot, demoted metric
            // appends to the end (lands in the pool because it's past
            // activeCount in the order list).
            items[idx] = newId
            items.add(metricKey)

            settingsRepository.update(
                current.copy(
                    dashboardCustomTiles = root.toString(),
                    dashboardMetricOrder = items.joinToString(",")
                )
            )
        }
    }

    fun deleteCustomTile(id: String) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardCustomTiles)
            root.remove(id)
            val order = current.dashboardMetricOrder
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != id }
                .joinToString(",")
            settingsRepository.update(
                current.copy(
                    dashboardCustomTiles = root.toString(),
                    dashboardMetricOrder = order
                )
            )
        }
    }

    /**
     * Catalog-model copy: write [key] into grid slot [slotIndex], leaving
     * the pool catalog untouched. Used when the drag source is a pool
     * pill (i.e. `sourceFromGrid = false` on the controller). Whatever
     * was at [slotIndex] is discarded — for dynamic instances (composite
     * / custom tile) the definition is deleted too. The displaced static
     * metric is NOT added to the pool because the pool is the always-
     * present catalog of known metrics.
     */
    fun setDashboardMetricAtIndex(key: String, slotIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(
                current.dashboardMetricOrder,
                knownDashboardMetrics,
                listCompositeMetrics(current).keys + listCustomTiles(current).keys
            ).toMutableList()
            if (slotIndex !in 0 until ACTIVE_DASHBOARD_TILE_COUNT) return@launch
            if (slotIndex !in items.indices) return@launch
            val displaced = items[slotIndex]
            if (displaced == key) return@launch

            // Replace the slot with the new key.
            items[slotIndex] = key
            // If the displaced was a dynamic instance, drop its order
            // entry past the active region too (it's getting deleted).
            if (isCompositeMetricKey(displaced) || isCustomTileKey(displaced)) {
                items.removeAll { it == displaced }
            }

            val newComposites = if (isCompositeMetricKey(displaced))
                parseSlotStatsRoot(current.dashboardCompositeMetrics)
                    .also { it.remove(displaced) }.toString()
            else current.dashboardCompositeMetrics
            val newCustomTiles = if (isCustomTileKey(displaced))
                parseSlotStatsRoot(current.dashboardCustomTiles)
                    .also { it.remove(displaced) }.toString()
            else current.dashboardCustomTiles

            settingsRepository.update(
                current.copy(
                    dashboardMetricOrder = items.joinToString(","),
                    dashboardCompositeMetrics = newComposites,
                    dashboardCustomTiles = newCustomTiles
                )
            )
        }
    }

    /**
     * Catalog-model copy for actions — symmetric counterpart to
     * [setDashboardMetricAtIndex]. Deletes the displaced action group's
     * definition if any.
     */
    fun setDashboardActionAtIndex(key: String, slotIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(
                current.dashboardActionOrder,
                knownDashboardActions,
                listActionGroups(current).keys + listCustomBle(current).keys
            ).toMutableList()
            if (slotIndex !in 0 until ACTIVE_DASHBOARD_TILE_COUNT) return@launch
            if (slotIndex !in items.indices) return@launch
            val displaced = items[slotIndex]
            if (displaced == key) return@launch

            items[slotIndex] = key
            if (isActionGroupKey(displaced) || isCustomBleKey(displaced)) {
                items.removeAll { it == displaced }
            }

            val newGroups = if (isActionGroupKey(displaced))
                parseSlotStatsRoot(current.dashboardActionGroups)
                    .also { it.remove(displaced) }.toString()
            else current.dashboardActionGroups

            val newBle = if (isCustomBleKey(displaced))
                parseSlotStatsRoot(current.dashboardCustomBle)
                    .also { it.remove(displaced) }.toString()
            else current.dashboardCustomBle

            settingsRepository.update(
                current.copy(
                    dashboardActionOrder = items.joinToString(","),
                    dashboardActionGroups = newGroups,
                    dashboardCustomBle = newBle
                )
            )
        }
    }

    /**
     * Drops [key] into position [toIndex] of the metric order, swapping with
     * whatever previously occupied that slot. Works for both grid-to-grid
     * reorder and pool-to-grid promotion: anything pushed past the active-slot
     * count (cols * 3) naturally lands in the pool again.
     */
    fun moveDashboardMetricToIndex(key: String, toIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            // Pass composite + custom-tile IDs as `dynamic` so sanitize keeps
            // them in the order list — otherwise a stack or custom-link tile
            // dragged between slots would silently vanish from the persisted
            // layout.
            val items = sanitize(
                current.dashboardMetricOrder,
                knownDashboardMetrics,
                listCompositeMetrics(current).keys + listCustomTiles(current).keys
            ).toMutableList()
            val fromIndex = items.indexOf(key)
            if (fromIndex < 0 || toIndex !in items.indices) return@launch
            if (fromIndex == toIndex) return@launch

            // Big-tile-to-big-tile drag = genuine A<->B swap: the dragged tile
            // and the tile it lands on exchange positions. Nothing is created,
            // deleted, or left empty, so composite / custom-tile instances are
            // preserved. (Pool->grid drops go through setDashboardMetricAtIndex.)
            val displaced = items[toIndex]
            items[toIndex] = key
            items[fromIndex] = displaced

            settingsRepository.update(
                current.copy(dashboardMetricOrder = items.joinToString(","))
            )
        }
    }

    fun moveDashboardActionToIndex(key: String, toIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(
                current.dashboardActionOrder,
                knownDashboardActions,
                listActionGroups(current).keys + listCustomBle(current).keys
            ).toMutableList()
            val fromIndex = items.indexOf(key)
            if (fromIndex < 0 || toIndex !in items.indices) return@launch
            if (fromIndex == toIndex) return@launch

            // Big-tile-to-big-tile drag = genuine A<->B swap: the dragged tile
            // and the tile it lands on exchange positions. Nothing is created,
            // deleted, or left empty, so group / custom-BLE instances are
            // preserved. (Pool->grid drops go through setDashboardActionAtIndex.)
            val displaced = items[toIndex]
            items[toIndex] = key
            items[fromIndex] = displaced

            settingsRepository.update(
                current.copy(dashboardActionOrder = items.joinToString(","))
            )
        }
    }

    /**
     * Restore the metric slot at [slotIndex] to the metric the app ships
     * there (`knownDashboardMetrics[slotIndex]`), regardless of what the
     * rider currently has in that slot. If the natural occupant lives in
     * a different position, swap so it returns home and the displaced
     * tile takes its place. Any dynamic tile (composite / custom-tile)
     * pushed past the active region by the swap is cleaned up the same
     * way the swap-via-drag path handles it.
     */
    fun resetDashboardMetricAtIndex(slotIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(
                current.dashboardMetricOrder,
                knownDashboardMetrics,
                listCompositeMetrics(current).keys + listCustomTiles(current).keys
            ).toMutableList()
            val naturalKey = knownDashboardMetrics.getOrNull(slotIndex) ?: return@launch
            if (slotIndex !in items.indices) return@launch
            val currentOccupant = items[slotIndex]

            // Note: don't early-return when currentOccupant == naturalKey.
            // The rider may have customized corner stats (AVG / MIN / etc.)
            // on the natural occupant, and Reset still needs to wipe those
            // so the slot looks like a fresh install.

            val occupantIsDynamic = isCompositeMetricKey(currentOccupant) ||
                isCustomTileKey(currentOccupant)
            val needsReorder = currentOccupant != naturalKey

            if (needsReorder && occupantIsDynamic) {
                // Dynamic occupant (composite / custom tile) gets deleted —
                // the rider's "restore this slot" intent doesn't preserve
                // dynamic instances. Remove it from the order, then move
                // the natural metric into slotIndex (closing any gap).
                items.removeAt(slotIndex)
                val naturalIdx = items.indexOf(naturalKey)
                if (naturalIdx >= 0) items.removeAt(naturalIdx)
                items.add(slotIndex.coerceAtMost(items.size), naturalKey)
            } else if (needsReorder) {
                // Static occupant — swap with the natural metric's current
                // position so neither static metric is lost.
                val naturalIdx = items.indexOf(naturalKey)
                if (naturalIdx >= 0) {
                    items[naturalIdx] = currentOccupant
                    items[slotIndex] = naturalKey
                } else {
                    items[slotIndex] = naturalKey
                    items.add(currentOccupant)
                }
            }

            // Clean up: any dynamic tile sitting in the pool (past
            // activeCount) is implicitly orphaned, plus the one we just
            // deleted at slotIndex (if any). Drop them from the order and
            // their JSON definition map.
            val activeCount = ACTIVE_DASHBOARD_TILE_COUNT
            val orphanedComposites = items.withIndex()
                .filter { (idx, k) -> idx >= activeCount && isCompositeMetricKey(k) }
                .map { it.value }
                .toMutableSet()
            val orphanedCustomTiles = items.withIndex()
                .filter { (idx, k) -> idx >= activeCount && isCustomTileKey(k) }
                .map { it.value }
                .toMutableSet()
            if (occupantIsDynamic) {
                if (isCompositeMetricKey(currentOccupant)) orphanedComposites.add(currentOccupant)
                if (isCustomTileKey(currentOccupant)) orphanedCustomTiles.add(currentOccupant)
            }
            val toRemove = (orphanedComposites + orphanedCustomTiles)
            val cleaned = if (toRemove.isEmpty()) items else items.filter { it !in toRemove }
            val newComposites = if (orphanedComposites.isEmpty()) current.dashboardCompositeMetrics
                else parseSlotStatsRoot(current.dashboardCompositeMetrics)
                    .also { root -> orphanedComposites.forEach { root.remove(it) } }
                    .toString()
            val newCustomTiles = if (orphanedCustomTiles.isEmpty()) current.dashboardCustomTiles
                else parseSlotStatsRoot(current.dashboardCustomTiles)
                    .also { root -> orphanedCustomTiles.forEach { root.remove(it) } }
                    .toString()

            // Also wipe the natural key's per-slot stats so the restored
            // metric shows up with its shipped appearance (current value
            // in the center, no AVG / MIN / sparkline carry-over from the
            // slot it used to live in). Without this, BATTERY brings its
            // previously-configured corner stats along when it returns
            // home, which makes the "Default" button feel half-applied.
            val statsRoot = parseSlotStatsRoot(current.dashboardMetricStats)
            statsRoot.remove(naturalKey)

            settingsRepository.update(
                current.copy(
                    dashboardMetricOrder = cleaned.joinToString(","),
                    dashboardCompositeMetrics = newComposites,
                    dashboardCustomTiles = newCustomTiles,
                    dashboardMetricStats = statsRoot.toString()
                )
            )
        }
    }

    /**
     * Restore the action slot at [slotIndex] to `knownDashboardActions[slotIndex]`
     * — symmetric counterpart to [resetDashboardMetricAtIndex]. Cleans up any
     * action-group definitions pushed past the active region.
     */
    fun resetDashboardActionAtIndex(slotIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(
                current.dashboardActionOrder,
                knownDashboardActions,
                listActionGroups(current).keys + listCustomBle(current).keys
            ).toMutableList()
            val naturalKey = defaultDashboardActions.getOrNull(slotIndex) ?: return@launch
            if (slotIndex !in items.indices) return@launch
            val currentOccupant = items[slotIndex]

            val occupantIsGroup = isActionGroupKey(currentOccupant)
            val needsReorder = currentOccupant != naturalKey

            if (needsReorder && occupantIsGroup) {
                items.removeAt(slotIndex)
                val naturalIdx = items.indexOf(naturalKey)
                if (naturalIdx >= 0) items.removeAt(naturalIdx)
                items.add(slotIndex.coerceAtMost(items.size), naturalKey)
            } else if (needsReorder) {
                val naturalIdx = items.indexOf(naturalKey)
                if (naturalIdx >= 0) {
                    items[naturalIdx] = currentOccupant
                    items[slotIndex] = naturalKey
                } else {
                    items[slotIndex] = naturalKey
                    items.add(currentOccupant)
                }
            }

            val activeCount = ACTIVE_DASHBOARD_TILE_COUNT
            val orphanedGroups = items.withIndex()
                .filter { (idx, k) -> idx >= activeCount && isActionGroupKey(k) }
                .map { it.value }
                .toMutableSet()
            if (occupantIsGroup) orphanedGroups.add(currentOccupant)

            val cleaned = if (orphanedGroups.isEmpty()) items else items.filter { it !in orphanedGroups }
            val newGroups = if (orphanedGroups.isEmpty()) current.dashboardActionGroups
                else parseSlotStatsRoot(current.dashboardActionGroups)
                    .also { root -> orphanedGroups.forEach { root.remove(it) } }
                    .toString()

            settingsRepository.update(
                current.copy(
                    dashboardActionOrder = cleaned.joinToString(","),
                    dashboardActionGroups = newGroups
                )
            )
        }
    }

    // ---- Per-slot stat configuration ----
    //
    // Each metric in the active dashboard grid can put a different reading in
    // its center and four corners: NONE, CURRENT, MIN, MAX or AVG over the
    // configured rolling stats window. Stored as one JSON blob in AppSettings
    // so adding a new corner or stat doesn't require a schema change.

    fun dashboardMetricSlotStats(s: AppSettings, key: String): MetricSlotStats =
        parseSlotStats(s.dashboardMetricStats, key)

    fun updateDashboardMetricSlotStat(key: String, corner: SlotCorner, stat: DashboardStat) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardMetricStats)
            val node = root.optJSONObject(key) ?: org.json.JSONObject()
            node.put(corner.jsonKey, stat.name)
            root.put(key, node)
            settingsRepository.update(current.copy(dashboardMetricStats = root.toString()))
        }
    }

    fun updateDashboardMetricSparkline(key: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardMetricStats)
            val node = root.optJSONObject(key) ?: org.json.JSONObject()
            node.put("spark", enabled)
            root.put(key, node)
            settingsRepository.update(current.copy(dashboardMetricStats = root.toString()))
        }
    }

    fun resetDashboardMetricSlotStats(key: String) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val root = parseSlotStatsRoot(current.dashboardMetricStats)
            root.remove(key)
            settingsRepository.update(current.copy(dashboardMetricStats = root.toString()))
        }
    }

    private fun parseSlotStatsRoot(s: String): org.json.JSONObject =
        try { org.json.JSONObject(s.ifBlank { "{}" }) } catch (_: Exception) { org.json.JSONObject() }

    private fun parseSlotStats(s: String, key: String): MetricSlotStats {
        val root = parseSlotStatsRoot(s)
        val n = root.optJSONObject(key) ?: return MetricSlotStats()
        fun stat(k: String, default: DashboardStat) =
            runCatching { DashboardStat.valueOf(n.optString(k, default.name)) }.getOrDefault(default)
        return MetricSlotStats(
            left = stat("l", DashboardStat.NONE),
            center = stat("c", DashboardStat.CURRENT),
            right = stat("r", DashboardStat.NONE),
            sparkline = n.optBoolean("spark", true)
        )
    }

    fun updateDashboardMetricsColumns(n: Int) = update { copy(dashboardMetricsColumns = n.coerceIn(2, 4)) }
    fun updateDashboardActionsColumns(n: Int) = update { copy(dashboardActionsColumns = n.coerceIn(2, 4)) }
    fun updateDashboardRollingWindowSeconds(seconds: Int) = update {
        // Snap to the nearest preset so the dropdown's choices stay
        // round-trippable even if a stray value sneaks in via JSON migration.
        val snapped = ROLLING_WINDOW_PRESETS_SECONDS.minByOrNull { kotlin.math.abs(it - seconds) }
            ?: ROLLING_WINDOW_DEFAULT_SECONDS
        copy(dashboardRollingWindowSeconds = snapped)
    }

    fun moveDashboardMetric(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(current.dashboardMetricOrder, knownDashboardMetrics).toMutableList()
            if (fromIndex in items.indices && toIndex in items.indices) {
                val item = items.removeAt(fromIndex)
                items.add(toIndex, item)
                settingsRepository.update(current.copy(dashboardMetricOrder = items.joinToString(",")))
            }
        }
    }

    fun moveDashboardAction(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val items = sanitize(current.dashboardActionOrder, knownDashboardActions).toMutableList()
            if (fromIndex in items.indices && toIndex in items.indices) {
                val item = items.removeAt(fromIndex)
                items.add(toIndex, item)
                settingsRepository.update(current.copy(dashboardActionOrder = items.joinToString(",")))
            }
        }
    }

    fun resetDashboardMetrics() = update {
        copy(
            dashboardMetricsColumns = 2,
            dashboardMetricOrder = knownDashboardMetrics.joinToString(","),
            dashboardRollingWindowSeconds = ROLLING_WINDOW_DEFAULT_SECONDS,
            // Wipe composite + custom-tile definitions too — the rider's
            // reset action is a "back to defaults" signal, which includes
            // any custom stacks and personal-link tiles they had.
            dashboardCompositeMetrics = "{}",
            dashboardCustomTiles = "{}"
        )
    }

    fun resetDashboardActions() = update {
        copy(
            dashboardActionsColumns = 3,
            dashboardActionOrder = knownDashboardActions.joinToString(","),
            dashboardActionGroups = "{}"
        )
    }

    fun syncFolderDisplayName(): String? {
        val s = settings.value ?: return null
        return syncManager.getSyncFolderDisplayName(s)
    }

    // ---- EucStats online upload ----

    /** Live rider card from the eucstats backend, null when not registered or not yet fetched. */
    val onlineUploadCard: StateFlow<RiderCard?> = eucStatsRepository.card

    /** True once a card fetch has finished, so the UI can stop showing a spinner
     *  and fall back to a short "couldn't load" line when the card is still null. */
    val onlineUploadCardLoaded: StateFlow<Boolean> = eucStatsRepository.cardLoaded

    /** True when the backend says this rider no longer exists (404) — the UI then
     *  offers to re-register instead of a generic "couldn't load". */
    val onlineUploadCardMissing: StateFlow<Boolean> = eucStatsRepository.cardMissing

    /** Refresh the rider card from the backend (no-op if no store_id is persisted). */
    fun refreshOnlineUploadCard() {
        viewModelScope.launch { eucStatsRepository.refreshCard() }
    }

    /** Register a new eucstats account. [onResult] is called on the main thread with the success flag. */
    fun registerOnlineUpload(
        displayName: String,
        flag: String,
        avatarPngBase64: String,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val ok = eucStatsRepository.register(displayName, flag, avatarPngBase64)
            if (ok) {
                // Persist the rider identity (store_id) to a backup file in the
                // sync folder so it survives a reinstall / new device --
                // findRestorableRider can then recover the rider instead of
                // forcing a fresh registration. Best-effort.
                syncManager.backupSettings()
            }
            onResult(ok)
        }
    }

    /**
     * Enable or disable online upload.
     *
     * Disabling always succeeds and just flips the flag.
     * Enabling is silently skipped when a sync folder or store_id is absent —
     * the UI is responsible for routing the rider through onboarding first.
     */
    fun setOnlineUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            if (!enabled) {
                settingsRepository.update(current.copy(onlineUploadEnabled = false))
                return@launch
            }
            // Preconditions: sync folder configured AND already registered.
            if (current.syncFolderUri == null || current.eucstatsStoreId == null) return@launch
            settingsRepository.update(current.copy(onlineUploadEnabled = true))
            eucStatsRepository.refreshCard()
        }
    }

    /** Enqueue a background upload of all pending eucstats trips via WorkManager. */
    fun retryEucstatsUploads() {
        viewModelScope.launch {
            val s = settingsRepository.get()
            syncManager.enqueueEucStatsUpload(s)
        }
    }

    // ---- Foreground eucstats "Sync all" (leaderboard card) ----
    private val _eucstatsSyncProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** (done, total) while a Sync-all runs; null = indeterminate "checking…". */
    val eucstatsSyncProgress: StateFlow<Pair<Int, Int>?> = _eucstatsSyncProgress.asStateFlow()
    private val _eucstatsSyncRunning = MutableStateFlow(false)
    val eucstatsSyncRunning: StateFlow<Boolean> = _eucstatsSyncRunning.asStateFlow()

    /**
     * Foreground "Sync all" for trip-stats uploads: shows determinate progress
     * like the trips-backup sync, and reports "nothing to sync" (after a brief
     * delay so it reads as having checked) when no uploads are pending.
     */
    fun syncEucstatsNow() {
        if (_eucstatsSyncRunning.value) return
        viewModelScope.launch {
            _eucstatsSyncRunning.value = true
            _eucstatsSyncProgress.value = null
            kotlinx.coroutines.delay(600) // brief "checking…" so a 0-pending tap doesn't just flash
            val count = eucStatsRepository.syncPendingNow { done, total ->
                _eucstatsSyncProgress.value = done to total
            }
            if (count == 0) kotlinx.coroutines.delay(300)
            _eucstatsSyncProgress.value = null
            _eucstatsSyncRunning.value = false
            _cloudEvent.value = if (count == 0) CloudEvent.EucstatsNothingToSync
                                else CloudEvent.EucstatsSyncFinished(count)
            if (count > 0) refreshOnlineUploadCard()
        }
    }

    /**
     * Load the full rider profile (name, flag, hasAvatar, can_change_* gates).
     * [onResult] is called on the main thread with the profile, or null on failure.
     */
    fun loadOnlineProfile(onResult: (com.eried.eucplanet.data.eucstats.RiderProfile?) -> Unit) {
        viewModelScope.launch {
            val profile = eucStatsRepository.fetchProfile()
            onResult(profile)
        }
    }

    /** PATCH the rider's eucstats profile. [onResult] receives the HTTP status code. */
    fun editOnlineProfile(
        displayName: String?,
        flag: String?,
        avatarPngBase64: String?,
        onResult: (Int) -> Unit,
    ) {
        viewModelScope.launch {
            val code = eucStatsRepository.editProfile(displayName, flag, avatarPngBase64)
            onResult(code)
        }
    }

    /** Delete the eucstats account and clear local credentials. [onResult] receives the success flag. */
    fun deleteOnlineAccount(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = eucStatsRepository.deleteAccount()
            onResult(ok)
        }
    }

    /** Fetch the rider's exported data JSON. [onResult] receives the JSON string, or null on failure. */
    fun exportOnlineData(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val json = eucStatsRepository.exportData()
            onResult(json)
        }
    }
}

/**
 * Allowed rolling-window durations (seconds). Stored as a list so the dropdown
 * UI and the snap-to-nearest validation share a single source of truth.
 * Range covers a quick burst (30 s) up to a full commute hour.
 */
val ROLLING_WINDOW_PRESETS_SECONDS: List<Int> = listOf(
    30, 60, 120, 180, 300, 600, 900, 1800, 3600
)

const val ROLLING_WINDOW_DEFAULT_SECONDS: Int = 300

/**
 * Layouts a composite metric tile can take. The cell count drives how many
 * sub-metrics the rider picks in the edit sheet; the orientation drives how
 * those values stack inside a single grid tile.
 */
enum class CompositeLayout(val cellCount: Int) {
    /** Two cells stacked top/bottom — best for long values with units. */
    ROW2(2),
    /** Two cells side-by-side. */
    COL2(2),
    /** Three cells side-by-side. */
    COL3(3)
}

/**
 * Definition of a single composite metric instance. Stored in
 * [AppSettings.dashboardCompositeMetrics] as `{ id: { layout, cells } }`.
 * Sub-metric stats (min/max/avg) are intentionally NOT supported here — a
 * composite always shows current values for each sub-metric.
 */
data class MetricComposite(
    val layout: CompositeLayout = CompositeLayout.ROW2,
    val cells: List<String> = listOf("SPEED", "BATTERY"),
    /**
     * Per-cell stat selector — what each cell displays. Parallel to
     * [cells]. Defaults to [DashboardStat.CURRENT] (live value) so
     * existing composites and freshly-spawned ones look the same as
     * before. The rider can change it per cell in the composite edit
     * sheet (Now / Min / Max / Avg / Sustained peak / Median / P75 /
     * P95 / P99).
     */
    val cellStats: List<DashboardStat> = listOf(DashboardStat.CURRENT, DashboardStat.CURRENT, DashboardStat.CURRENT)
)

/**
 * Tap behaviour for a custom tile. NONE renders as a display-only label;
 * OPEN_URL launches the rider's URL in the default browser; SHOW_QR opens
 * a QR-code popup so other riders can scan and visit the same URL (the
 * Instagram-share use case).
 */
enum class CustomTileAction { NONE, OPEN_URL, SHOW_QR }

/**
 * Rider-defined dashboard tile: a chosen icon + text label, optionally
 * paired with a tap action (open URL in browser, or show QR code popup).
 * Lets the rider drop a contact card / social handle / club page onto the
 * dashboard alongside their telemetry. Stored in
 * [AppSettings.dashboardCustomTiles] keyed by `C:<uuid>`.
 */
data class CustomTile(
    val text: String = "",
    val icon: String = CUSTOM_TILE_DEFAULT_ICON,
    val action: CustomTileAction = CustomTileAction.NONE,
    val url: String = ""
)

/** Default icon for newly-spawned custom tiles. INFO (a filled "i") reads
 *  as "informational placeholder, configure me" -- friendlier than the
 *  outlined checkbox the old default used; "i" is a near-universal
 *  affordance for "this slot holds info, tap to configure". The rider
 *  picks a real icon when they edit the tile. */
const val CUSTOM_TILE_DEFAULT_ICON = "INFO"

/**
 * Definition of a single action group instance. Stored in
 * [AppSettings.dashboardActionGroups] as `{ id: { name, icon, actions } }`.
 * Up to 4 sub-actions; the rider can intentionally duplicate an action
 * (e.g. two `RECORD_TOGGLE` entries if they want it twice in the popover).
 * [icon] is a stable key from a curated set rendered by `groupIconFor` —
 * not a raw image vector, so the storage stays JSON-stable across icon-set
 * upgrades.
 */
data class ActionGroup(
    val name: String = "",
    val icon: String = GROUP_DEFAULT_ICON,
    val actions: List<String> = emptyList()
)

/** Default icon key for new action groups. Matches the "+ GROUP" template
 *  pill so the rider immediately recognises the freshly-spawned tile. */
const val GROUP_DEFAULT_ICON = "FOLDER"

/** Curated icon keys the rider can pick from in the group edit sheet and
 *  the custom-tile edit sheet. The list lives here (not in the screen) so
 *  the icon picker and the tile renderer share a single source of truth —
 *  adding a new entry shows up everywhere automatically. */
val GROUP_ICON_CHOICES: List<String> = listOf(
    "FOLDER", "STAR", "BOLT", "FAVORITE", "DASHBOARD",
    "EXTENSION", "TUNE", "WIDGETS", "APPS", "BUILD",
    "HOME", "PERSON", "SETTINGS", "SHIELD", "MAP",
    "PHOTO_CAMERA", "MUSIC_NOTE", "PHONE", "WIFI", "SEARCH",
    "SAVE", "SEND", "SHARE", "EDIT", "REFRESH",
    "DONE", "INFO", "WARNING", "NOTIFICATIONS", "LINK"
)

/** Number of active tiles in each dashboard grid (metrics + actions). The
 *  rider always picks 6 active items regardless of column count (2×3 vs 3×2
 *  is just a visual layout choice). VM logic uses this to decide whether a
 *  given order-list index lands in the active grid or in the pool. */
const val ACTIVE_DASHBOARD_TILE_COUNT = 6

/** Prefix on order-list entries that identifies a composite metric instance. */
const val COMPOSITE_METRIC_PREFIX = "M:"
/** Prefix on order-list entries that identifies an action group instance. */
const val ACTION_GROUP_PREFIX = "G:"
/** Pool template key for the "+ Stack" composite-metric source. */
const val COMPOSITE_TEMPLATE_KEY = "+M"
/** Pool template key for the "+ Group" action-group source. */
const val ACTION_GROUP_TEMPLATE_KEY = "+G"
/** Pool template key for the "+ BLE" custom-command source. */
const val CUSTOM_BLE_TEMPLATE_KEY = "+B"
/** Default icon key for a freshly-created custom BLE command. */
const val CUSTOM_BLE_DEFAULT_ICON = "BOLT"
/**
 * Prefix marking a composite cell value as rider-typed text rather than a
 * metric key. The string after the prefix is the literal text the cell
 * renders on the grid; blank content reads as "(empty)" faint placeholder.
 * Lets the rider drop a label like "Pack 1" into a stack tile next to the
 * actual battery reading.
 *
 * Examples:
 *  - "TEXT:" → empty text cell, shows "(empty)" placeholder
 *  - "TEXT:Right pack" → cell renders "Right pack"
 */
const val COMPOSITE_TEXT_PREFIX = "TEXT:"

/** Pseudo-metric for an unbound composite cell. Stored as the TEXT prefix
 *  with no content. Picker labels this as "(none)"; cell renders as the
 *  "–" dash glyph. When the rider picks this, the layout COLLAPSES (other
 *  populated cells share the freed space). */
const val COMPOSITE_CELL_EMPTY = COMPOSITE_TEXT_PREFIX

/** Pseudo-metric that reserves a composite cell slot without showing any
 *  glyph. Picker labels as "(empty)", cell renders as a blank space. The
 *  rider picks this when they want to keep the composite's column / row
 *  count visually intact (e.g. a COL3 tile with two reads + a deliberate
 *  blank stays a 3-column tile instead of collapsing into 2 halves). */
const val COMPOSITE_CELL_BLANK = "BLANK:"

/** Sentinel inserted into picker option lists to render a visual divider
 *  between groups (placeholders vs real options). Dropdowns recognise this
 *  and render a non-clickable HorizontalDivider in its place. */
const val PICKER_DIVIDER_SENTINEL = "__DIVIDER__"

/**
 * Sentinel key for an intentionally-blank top-level grid slot. Different from
 * COMPOSITE_CELL_EMPTY (which is the sub-cell placeholder inside a MULTI tile)
 * — this one occupies a row in dashboardMetricOrder / dashboardActionOrder
 * so positions are preserved when the rider drags a tile out of a slot with
 * "move + leave source empty" semantics. Renderers map this key to a blank
 * Box; the pool catalog is the source-of-truth for re-adding the metric.
 */
const val EMPTY_SLOT_KEY = "__EMPTY__"

/** Prefix on order-list entries that identifies a custom tile (rider-typed
 *  text + icon + optional tap action like launching a URL or showing a QR
 *  code). Lives in [AppSettings.dashboardCustomTiles] keyed by `C:<uuid>`. */
const val CUSTOM_TILE_PREFIX = "C:"
/** Pool template key for the "+ Custom" custom-tile source. */
const val CUSTOM_TILE_TEMPLATE_KEY = "+C"

fun isCompositeMetricKey(key: String): Boolean = key.startsWith(COMPOSITE_METRIC_PREFIX)
fun isActionGroupKey(key: String): Boolean = key.startsWith(ACTION_GROUP_PREFIX)
fun isCustomBleKey(key: String): Boolean =
    key.startsWith(com.eried.eucplanet.data.model.CustomBleCommand.ID_PREFIX)
fun isCustomTileKey(key: String): Boolean = key.startsWith(CUSTOM_TILE_PREFIX)
fun isTextCell(key: String): Boolean = key.startsWith(COMPOSITE_TEXT_PREFIX) || key == "EMPTY"
fun textCellContent(key: String): String = when {
    key == "EMPTY" -> ""
    key.startsWith(COMPOSITE_TEXT_PREFIX) -> key.removePrefix(COMPOSITE_TEXT_PREFIX)
    else -> ""
}
fun wrapAsTextCell(content: String): String = COMPOSITE_TEXT_PREFIX + content

// Stats are listed in dropdown order. SUSTAINED_PEAK sits next to MAX because
// it's a softer "Peak ignoring spikes shorter than 2s" companion — the same
// reading Inmotion shows as "Sustained peak". Percentiles ascend so the
// picker reads: None / Now / Min / Max / Sustained peak / Avg / Median (P50)
// / P75 / P95 / P99.
enum class DashboardStat {
    NONE, CURRENT, MIN, MAX, SUSTAINED_PEAK, AVG, MEDIAN, P75, P95, P99,
    /**
     * Visible "(empty)" placeholder. Different from NONE:
     *   NONE  -> "no stat here, collapse the tile's layout"
     *           (e.g. a single side badge re-flows to two halves)
     *   EMPTY -> "reserve this slot but show nothing"
     *           (keeps the tile in 3-zone layout with a blank corner)
     * Lets the rider preserve a tile's visual width when they want one
     * side stat without the auto-2-column re-flow.
     */
    EMPTY
}

enum class SlotCorner(val jsonKey: String) {
    LEFT("l"), CENTER("c"), RIGHT("r")
}

data class MetricSlotStats(
    val left: DashboardStat = DashboardStat.NONE,
    val center: DashboardStat = DashboardStat.CURRENT,
    val right: DashboardStat = DashboardStat.NONE,
    val sparkline: Boolean = true
) {
    fun stat(corner: SlotCorner): DashboardStat = when (corner) {
        SlotCorner.LEFT -> left
        SlotCorner.CENTER -> center
        SlotCorner.RIGHT -> right
    }

    /** True only when the rider hasn't customized any side reading. */
    val isDefault: Boolean
        get() = left == DashboardStat.NONE &&
            center == DashboardStat.CURRENT &&
            right == DashboardStat.NONE
}

sealed interface CloudEvent {
    data object FolderSet : CloudEvent
    data object FolderFailed : CloudEvent
    data object BackupSuccess : CloudEvent
    data class BackupNamedSuccess(val name: String) : CloudEvent
    data object BackupFailed : CloudEvent
    data class BackupExists(val name: String) : CloudEvent
    data object RestoreSuccess : CloudEvent
    data object RestoreFailed : CloudEvent
    data object UploadEnqueued : CloudEvent
    data object SyncNoFolder : CloudEvent
    data class SyncFinished(val count: Int) : CloudEvent
    data object EucstatsNothingToSync : CloudEvent
    data class EucstatsSyncFinished(val count: Int) : CloudEvent
}
