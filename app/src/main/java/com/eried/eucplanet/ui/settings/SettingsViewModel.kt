package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.location.Location
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.BackupEntry
import com.eried.eucplanet.data.sync.BackupOutcome
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
    private val engineSoundEngine: com.eried.eucplanet.audio.EngineSoundEngine,
    val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val overlayPresetStore: com.eried.eucplanet.data.store.OverlayPresetStore,
    hudCommandSink: com.eried.eucplanet.service.hud.HudCommandSink
) : ViewModel() {

    /** Live HUD protocol compatibility for the Settings/Integration card.
     *  Surfaces the "update HUD" / "update phone" hints. EXACT means nothing
     *  to show. */
    val hudVersionCompat = hudCommandSink.hudVersionCompat
    /** APK version string the HUD reported on pairing, e.g. "0.1.6". Null
     *  when no HUD is currently paired. */
    val hudVersion = hudCommandSink.hudVersion

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
            copy(
                tiltbackSpeedKmh = value,
                alarmSpeedKmh = alarmSpeedKmh.coerceAtMost(value),
                safetyTiltbackKmh = safetyTiltbackKmh.coerceAtMost(value - 1f),
                safetyAlarmKmh = safetyAlarmKmh.coerceAtMost((value - 1f).coerceAtLeast(10f))
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
            val capped = value.coerceAtMost(tiltbackSpeedKmh - 1f)
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
        update { copy(voiceLocale = tag) }
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

    /** Screens that are ON by default on a fresh install. Order is the
     *  default carousel order. Anything in [knownHudScreens] but not in
     *  this list ships disabled -- the rider opts in via the Personalize
     *  list in Settings. */
    val defaultEnabledHudScreens: List<String> = listOf(
        "Dashboard", "Camera", "Telemetry", "Custom", "CustomCam", "Map", "Nav"
    )

    /** Stable identifiers for every HUD screen the app knows about,
     *  mirroring the HudUiController.Screen enum on the HUD side. Order
     *  determines where new screens appear in the Personalize list. The
     *  newer "opt-in" screens (Power onward) ship disabled so the rider
     *  doesn't get a fresh suite of unfamiliar screens dropped on the
     *  carousel without asking. */
    val knownHudScreens: List<String> = defaultEnabledHudScreens + listOf(
        "Power", "TripStats", "Compass", "Safety", "BigClock"
    )

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
        val ttsLang = voiceService.currentVoiceLanguage().lowercase()
        if (ttsLang != appLangPrimary) {
            // Defer the locale switch until the user confirms in the dialog , 
            // showing the prompt FIRST means the dialog renders in the user's
            // current language, so they can read it (and Cancel) even if the
            // requested language is one they don't actually speak.
            _ttsSwitchPrompt.value = v
        } else {
            // TTS already matches, apply the language switch immediately.
            update { copy(language = v) }
            com.eried.eucplanet.util.LocaleHelper.apply(v)
        }
    }

    /**
     * User accepted: switch the app language and ALSO switch the TTS voice
     * to match the new language.
     */
    fun acceptTtsSwitch() {
        val v = _ttsSwitchPrompt.value ?: return
        update { copy(language = v) }
        com.eried.eucplanet.util.LocaleHelper.apply(v)
        val appLang = if (v.isBlank()) "en" else v
        val tag = voiceService.pickVoiceForLanguage(appLang)
        if (tag != null) {
            update { copy(voiceLocale = tag) }
            voiceService.setVoiceLocale(tag)
        }
        _ttsSwitchPrompt.value = null
    }

    /**
     * User chose to switch language but keep the existing TTS voice.
     */
    fun dismissTtsSwitch() {
        val v = _ttsSwitchPrompt.value ?: return
        update { copy(language = v) }
        com.eried.eucplanet.util.LocaleHelper.apply(v)
        _ttsSwitchPrompt.value = null
    }

    /**
     * User cancelled the language switch entirely (e.g. picked the wrong
     * language by mistake).
     */
    fun cancelLanguageSwitch() { _ttsSwitchPrompt.value = null }

    fun updateThemeMode(v: String) = update { copy(themeMode = v) }
    fun updateAccentColor(v: String) = update { copy(accentColor = v) }
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
            val ok = syncManager.restoreSettingsFrom(fileName)
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

    fun syncFolderDisplayName(): String? {
        val s = settings.value ?: return null
        return syncManager.getSyncFolderDisplayName(s)
    }
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
}
