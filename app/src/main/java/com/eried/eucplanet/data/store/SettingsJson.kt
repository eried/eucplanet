package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AppSettings
import org.json.JSONObject

/**
 * Hand-written AppSettings <-> JSON mapping used by both the on-disk settings
 * store ([SettingsStore]) and the export/restore feature ([com.eried.eucplanet.data.sync.SyncManager]).
 *
 * Includes every persistent field so [SettingsStore] round-trips them, the
 * backup writer in SyncManager scrubs device-specific fields (BLE addresses,
 * SAF folder URI, last-backup timestamp) with [stripDeviceBindings] before
 * writing the cross-device backup file.
 *
 * Read path uses [base] as the floor so unknown / older payloads just keep
 * the current default, adding a new AppSettings field never breaks restore.
 */
object SettingsJson {

    /**
     * Returns a copy of [s] with every device-specific binding nulled out so
     * the resulting JSON is safe to share between phones. Used by the backup
     * file writer; the on-disk DataStore blob keeps the full object so the
     * folder picker, paired Flic addresses, etc. survive an app restart.
     */
    fun stripDeviceBindings(s: AppSettings): AppSettings = s.copy(
        lastDeviceAddress = null,
        lastDeviceName = null,
        flic1Address = null,
        flic2Address = null,
        flic3Address = null,
        flic4Address = null,
        externalGpsAddress = null,
        externalGpsName = null,
        externalGpsSource = null,
        radarAddress = null,
        radarName = null,
        radarVendor = null,
        syncFolderUri = null,
        lastSettingsBackupAt = null,
        lastSettingsBackupName = null
    )

    fun toJson(s: AppSettings): JSONObject = JSONObject().apply {
        // Device-binding fields. Persisted locally; SyncManager nulls these
        // via [stripDeviceBindings] before writing the cross-device backup.
        put("lastDeviceAddress", s.lastDeviceAddress)
        put("lastDeviceName", s.lastDeviceName)
        put("flic1Address", s.flic1Address)
        put("flic2Address", s.flic2Address)
        put("flic3Address", s.flic3Address)
        put("flic4Address", s.flic4Address)
        put("externalGpsAddress", s.externalGpsAddress)
        put("externalGpsName", s.externalGpsName)
        put("externalGpsSource", s.externalGpsSource)
        put("radarAddress", s.radarAddress)
        put("radarName", s.radarName)
        put("radarVendor", s.radarVendor)
        put("radarShowOverlay", s.radarShowOverlay)
        put("radarOverlaySide", s.radarOverlaySide)
        put("syncFolderUri", s.syncFolderUri)
        put("lastSettingsBackupAt", s.lastSettingsBackupAt)
        put("lastSettingsBackupName", s.lastSettingsBackupName)

        // eucstats online upload — NOT device-specific; survive cross-device restore
        put("onlineUploadEnabled", s.onlineUploadEnabled)
        put("eucstatsStoreId", s.eucstatsStoreId)
        put("eucstatsDisplayName", s.eucstatsDisplayName)
        put("eucstatsFlag", s.eucstatsFlag)
        put("eucstatsConsentPublic", s.eucstatsConsentPublic)
        s.eucstatsRegisteredAt?.let { put("eucstatsRegisteredAt", it) }

        put("tiltbackSpeedKmh", s.tiltbackSpeedKmh)
        put("alarmSpeedKmh", s.alarmSpeedKmh)
        put("safetyTiltbackKmh", s.safetyTiltbackKmh)
        put("safetyAlarmKmh", s.safetyAlarmKmh)
        put("speedCalibrationOffsetPct", s.speedCalibrationOffsetPct)
        put("autoConnect", s.autoConnect)
        put("wheelNameDisplay", s.wheelNameDisplay)
        put("voiceEnabled", s.voiceEnabled)
        put("voicePeriodicEnabled", s.voicePeriodicEnabled)
        put("voiceOnlyWhenConnected", s.voiceOnlyWhenConnected)
        put("voiceIntervalSeconds", s.voiceIntervalSeconds)
        put("voiceSpeechRate", s.voiceSpeechRate)
        put("voiceLocale", s.voiceLocale)
        put("voiceLocaleOverridden", s.voiceLocaleOverridden)
        put("voiceAudioFocus", s.voiceAudioFocus)
        put("voiceOutputChannel", s.voiceOutputChannel)
        put("voiceReportSpeed", s.voiceReportSpeed)
        put("voiceReportBattery", s.voiceReportBattery)
        put("voiceReportTemp", s.voiceReportTemp)
        put("voiceReportPwm", s.voiceReportPwm)
        put("voiceReportDistance", s.voiceReportDistance)
        put("voiceReportTime", s.voiceReportTime)
        put("voiceReportNavigation", s.voiceReportNavigation)
        put("triggerReportSpeed", s.triggerReportSpeed)
        put("triggerReportBattery", s.triggerReportBattery)
        put("triggerReportTemp", s.triggerReportTemp)
        put("triggerReportPwm", s.triggerReportPwm)
        put("triggerReportDistance", s.triggerReportDistance)
        put("triggerReportTime", s.triggerReportTime)
        put("triggerReportNavigation", s.triggerReportNavigation)
        put("voiceReportRecording", s.voiceReportRecording)
        put("triggerReportRecording", s.triggerReportRecording)
        put("voiceReportOrder", s.voiceReportOrder)
        put("announceWheelLock", s.announceWheelLock)
        put("announceLights", s.announceLights)
        put("announceRecording", s.announceRecording)
        put("announceConnection", s.announceConnection)
        put("announceGps", s.announceGps)
        put("announceSafetyMode", s.announceSafetyMode)
        put("announceWelcome", s.announceWelcome)
        put("welcomeTutorialSeen", s.welcomeTutorialSeen)
        put("autoRecord", s.autoRecord)
        put("autoRecordStartInMotion", s.autoRecordStartInMotion)
        put("autoRecordStopIdleSeconds", s.autoRecordStopIdleSeconds)
        put("flic1Name", s.flic1Name)
        put("flic1Click", s.flic1Click)
        put("flic1DoubleClick", s.flic1DoubleClick)
        put("flic1Hold", s.flic1Hold)
        put("flic2Name", s.flic2Name)
        put("flic2Click", s.flic2Click)
        put("flic2DoubleClick", s.flic2DoubleClick)
        put("flic2Hold", s.flic2Hold)
        put("flic3Name", s.flic3Name)
        put("flic3Click", s.flic3Click)
        put("flic3DoubleClick", s.flic3DoubleClick)
        put("flic3Hold", s.flic3Hold)
        put("flic4Name", s.flic4Name)
        put("flic4Click", s.flic4Click)
        put("flic4DoubleClick", s.flic4DoubleClick)
        put("flic4Hold", s.flic4Hold)
        put("flicShowOnDashboard", s.flicShowOnDashboard)
        put("autoLightsEnabled", s.autoLightsEnabled)
        put("autoLightsOnMinutesBefore", s.autoLightsOnMinutesBefore)
        put("autoLightsOffMinutesAfter", s.autoLightsOffMinutesAfter)
        put("autoVolumeEnabled", s.autoVolumeEnabled)
        put("autoVolumeCurve", s.autoVolumeCurve)
        put("autoVolumeBaselinePercent", s.autoVolumeBaselinePercent)
        put("alarmsMuted", s.alarmsMuted)
        put("imperialUnits", s.imperialUnits)
        put("unitSpeed", s.unitSpeed)
        put("unitDistance", s.unitDistance)
        put("unitTemp", s.unitTemp)
        put("phoneKeepScreenOn", s.phoneKeepScreenOn)
        put("volumeKeysEnabled", s.volumeKeysEnabled)
        put("volumeUpClick", s.volumeUpClick)
        put("volumeUpHold", s.volumeUpHold)
        put("volumeDownClick", s.volumeDownClick)
        put("volumeDownHold", s.volumeDownHold)
        put("language", s.language)
        put("themeMode", s.themeMode)
        put("accentColor", s.accentColor)
        put("activeThemeColorsJson", s.activeThemeColorsJson)
        put("activeThemeName", s.activeThemeName)
        put("themeDirty", s.themeDirty)
        put("unsavedThemesJson", s.unsavedThemesJson)
        put("themeEditorEnabled", s.themeEditorEnabled)
        put("showGaugeColorBand", s.showGaugeColorBand)
        put("gaugeOrangeThresholdPct", s.gaugeOrangeThresholdPct)
        put("gaugeRedThresholdPct", s.gaugeRedThresholdPct)
        put("hapticFeedback", s.hapticFeedback)
        put("currentDisplayMode", s.currentDisplayMode)
        put("watchKeepScreenOn", s.watchKeepScreenOn)
        put("watchAutoStart", s.watchAutoStart)
        put("watchShowWheelBattery", s.watchShowWheelBattery)
        put("watchShowPhoneBattery", s.watchShowPhoneBattery)
        put("watchShowWatchBattery", s.watchShowWatchBattery)
        put("watchPwmDisplay", s.watchPwmDisplay)
        put("watchShowSpeedUnit", s.watchShowSpeedUnit)
        put("watchEnableGpsSpeed", s.watchEnableGpsSpeed)
        put("watchStem1Click", s.watchStem1Click)
        put("watchStem1Hold", s.watchStem1Hold)
        put("watchStem2Click", s.watchStem2Click)
        put("watchStem2Hold", s.watchStem2Hold)
        put("watchScreen1Click", s.watchScreen1Click)
        put("watchScreen1Hold", s.watchScreen1Hold)
        put("watchScreen2Click", s.watchScreen2Click)
        put("watchScreen2Hold", s.watchScreen2Hold)
        put("watchHapticOnAction", s.watchHapticOnAction)
        put("watchUpdateRate", s.watchUpdateRate)
        put("watchCloseOnExit", s.watchCloseOnExit)
        put("watchPrioritizePwm", s.watchPrioritizePwm)
        put("watchDialRotationDeg", s.watchDialRotationDeg)
        put("backButtonAction", s.backButtonAction)
        put("engineSoundEnabled", s.engineSoundEnabled)
        put("engineType", s.engineType)
        put("engineVolume", s.engineVolume)
        put("engineVolumeAutoEnabled", s.engineVolumeAutoEnabled)
        put("engineVolumeAutoCurve", s.engineVolumeAutoCurve)
        put("engineMuffler", s.engineMuffler)
        put("engineGearbox", s.engineGearbox)
        put("engineIdleBehavior", s.engineIdleBehavior)
        put("engineDecelChar", s.engineDecelChar)
        put("engineBrake", s.engineBrake)
        put("engineDuckOnVoice", s.engineDuckOnVoice)
        put("engineHeadphonesOnly", s.engineHeadphonesOnly)
        put("raceboxMapX", s.raceboxMapX)
        put("raceboxMapY", s.raceboxMapY)
        put("raceboxMapZ", s.raceboxMapZ)
        put("gpsPrioritizeExternal", s.gpsPrioritizeExternal)
        put("gpsShowOnDashboard", s.gpsShowOnDashboard)
        put("navVoiceEnabled", s.navVoiceEnabled)
        put("navArrivalRadiusM", s.navArrivalRadiusM)
        put("navOffRouteToleranceM", s.navOffRouteToleranceM)
        put("navHomeJson", s.navHomeJson)
        put("navWorkJson", s.navWorkJson)
        put("navDefaultTravelMode", s.navDefaultTravelMode)
        put("navGeocoderUrl", s.navGeocoderUrl)
        put("navRouterUrl", s.navRouterUrl)
        put("navCurrentRouteJson", s.navCurrentRouteJson)
        put("navCurrentRouteSavedAt", s.navCurrentRouteSavedAt)
        put("navMapType", s.navMapType)
        put("navUserMarkerPhotoDataUrl", s.navUserMarkerPhotoDataUrl)
        put("navSolveFullPath", s.navSolveFullPath)
        put("watchShowNavigation", s.watchShowNavigation)
        put("hudServerEnabled", s.hudServerEnabled)
        put("hudServerPort", s.hudServerPort)
        put("hudIp", s.hudIp)
        put("hudCustomOverlayName", s.hudCustomOverlayName)
        put("hudCustomOverlayJson", s.hudCustomOverlayJson)
        put("hudScreensEnabled", s.hudScreensEnabled)
        put("hudScreensOrder", s.hudScreensOrder)
        put("hudMapStyle", s.hudMapStyle)
        put("hudMapContrastPct", s.hudMapContrastPct)
        put("hudMapBrightnessPct", s.hudMapBrightnessPct)
        put("studioReplayPhotoFormat", s.studioReplayPhotoFormat)
        put("studioReplayVideoFormat", s.studioReplayVideoFormat)
        put("studioReplayChromaColor", s.studioReplayChromaColor)
        put("studioReplayForceOpaque", s.studioReplayForceOpaque)
        put("dashboardMetricsColumns", s.dashboardMetricsColumns)
        put("dashboardActionsColumns", s.dashboardActionsColumns)
        put("dashboardMetricOrder", s.dashboardMetricOrder)
        put("dashboardActionOrder", s.dashboardActionOrder)
        put("dashboardRollingWindowSeconds", s.dashboardRollingWindowSeconds)
        put("dashboardMetricStats", s.dashboardMetricStats)
        put("dashboardCompositeMetrics", s.dashboardCompositeMetrics)
        put("dashboardActionGroups", s.dashboardActionGroups)
        put("dashboardCustomTiles", s.dashboardCustomTiles)
        put("dashboardCustomBle", s.dashboardCustomBle)
        put("chargingEstimateToFull", s.chargingEstimateToFull)
        put("chargingAutoOpen", s.chargingAutoOpen)
        put("chargingDashboardIcon", s.chargingDashboardIcon)
    }

    fun fromJson(j: JSONObject, base: AppSettings = AppSettings()): AppSettings = base.copy(
        lastDeviceAddress = j.optStringOrNull("lastDeviceAddress", base.lastDeviceAddress),
        lastDeviceName = j.optStringOrNull("lastDeviceName", base.lastDeviceName),
        flic1Address = j.optStringOrNull("flic1Address", base.flic1Address),
        flic2Address = j.optStringOrNull("flic2Address", base.flic2Address),
        flic3Address = j.optStringOrNull("flic3Address", base.flic3Address),
        flic4Address = j.optStringOrNull("flic4Address", base.flic4Address),
        externalGpsAddress = j.optStringOrNull("externalGpsAddress", base.externalGpsAddress),
        externalGpsName = j.optStringOrNull("externalGpsName", base.externalGpsName),
        externalGpsSource = j.optStringOrNull("externalGpsSource", base.externalGpsSource),
        radarAddress = j.optStringOrNull("radarAddress", base.radarAddress),
        radarName = j.optStringOrNull("radarName", base.radarName),
        radarVendor = j.optStringOrNull("radarVendor", base.radarVendor),
        radarShowOverlay = j.optBoolean("radarShowOverlay", base.radarShowOverlay),
        radarOverlaySide = j.optString("radarOverlaySide", base.radarOverlaySide),
        syncFolderUri = j.optStringOrNull("syncFolderUri", base.syncFolderUri),
        lastSettingsBackupAt = if (j.has("lastSettingsBackupAt") && !j.isNull("lastSettingsBackupAt"))
            j.optLong("lastSettingsBackupAt", base.lastSettingsBackupAt ?: 0L)
        else base.lastSettingsBackupAt,
        lastSettingsBackupName = j.optStringOrNull("lastSettingsBackupName", base.lastSettingsBackupName),
        onlineUploadEnabled = j.optBoolean("onlineUploadEnabled", base.onlineUploadEnabled),
        eucstatsStoreId = j.optStringOrNull("eucstatsStoreId", base.eucstatsStoreId),
        eucstatsDisplayName = j.optStringOrNull("eucstatsDisplayName", base.eucstatsDisplayName),
        eucstatsFlag = j.optStringOrNull("eucstatsFlag", base.eucstatsFlag),
        eucstatsConsentPublic = j.optBoolean("eucstatsConsentPublic", base.eucstatsConsentPublic),
        eucstatsRegisteredAt = if (j.has("eucstatsRegisteredAt") && !j.isNull("eucstatsRegisteredAt"))
            j.optLong("eucstatsRegisteredAt", base.eucstatsRegisteredAt ?: 0L)
        else base.eucstatsRegisteredAt,
        tiltbackSpeedKmh = j.optDouble("tiltbackSpeedKmh", base.tiltbackSpeedKmh.toDouble()).toFloat(),
        alarmSpeedKmh = j.optDouble("alarmSpeedKmh", base.alarmSpeedKmh.toDouble()).toFloat(),
        safetyTiltbackKmh = j.optDouble("safetyTiltbackKmh", base.safetyTiltbackKmh.toDouble()).toFloat(),
        safetyAlarmKmh = j.optDouble("safetyAlarmKmh", base.safetyAlarmKmh.toDouble()).toFloat(),
        speedCalibrationOffsetPct = j.optDouble("speedCalibrationOffsetPct", base.speedCalibrationOffsetPct.toDouble()).toFloat(),
        autoConnect = j.optBoolean("autoConnect", base.autoConnect),
        wheelNameDisplay = j.optString("wheelNameDisplay", base.wheelNameDisplay),
        voiceEnabled = j.optBoolean("voiceEnabled", base.voiceEnabled),
        voicePeriodicEnabled = j.optBoolean("voicePeriodicEnabled", base.voicePeriodicEnabled),
        voiceOnlyWhenConnected = j.optBoolean("voiceOnlyWhenConnected", base.voiceOnlyWhenConnected),
        voiceIntervalSeconds = j.optInt("voiceIntervalSeconds", base.voiceIntervalSeconds),
        voiceSpeechRate = j.optDouble("voiceSpeechRate", base.voiceSpeechRate.toDouble()).toFloat(),
        voiceLocale = j.optString("voiceLocale", base.voiceLocale),
        voiceLocaleOverridden = j.optBoolean("voiceLocaleOverridden", base.voiceLocaleOverridden),
        voiceAudioFocus = j.optString("voiceAudioFocus", base.voiceAudioFocus),
        voiceOutputChannel = j.optString("voiceOutputChannel", base.voiceOutputChannel),
        voiceReportSpeed = j.optBoolean("voiceReportSpeed", base.voiceReportSpeed),
        voiceReportBattery = j.optBoolean("voiceReportBattery", base.voiceReportBattery),
        voiceReportTemp = j.optBoolean("voiceReportTemp", base.voiceReportTemp),
        voiceReportPwm = j.optBoolean("voiceReportPwm", base.voiceReportPwm),
        voiceReportDistance = j.optBoolean("voiceReportDistance", base.voiceReportDistance),
        voiceReportTime = j.optBoolean("voiceReportTime", base.voiceReportTime),
        voiceReportNavigation = j.optBoolean("voiceReportNavigation", base.voiceReportNavigation),
        triggerReportSpeed = j.optBoolean("triggerReportSpeed", base.triggerReportSpeed),
        triggerReportBattery = j.optBoolean("triggerReportBattery", base.triggerReportBattery),
        triggerReportTemp = j.optBoolean("triggerReportTemp", base.triggerReportTemp),
        triggerReportPwm = j.optBoolean("triggerReportPwm", base.triggerReportPwm),
        triggerReportDistance = j.optBoolean("triggerReportDistance", base.triggerReportDistance),
        triggerReportTime = j.optBoolean("triggerReportTime", base.triggerReportTime),
        triggerReportNavigation = j.optBoolean("triggerReportNavigation", base.triggerReportNavigation),
        voiceReportRecording = j.optBoolean("voiceReportRecording", base.voiceReportRecording),
        triggerReportRecording = j.optBoolean("triggerReportRecording", base.triggerReportRecording),
        voiceReportOrder = j.optString("voiceReportOrder", base.voiceReportOrder),
        announceWheelLock = j.optBoolean("announceWheelLock", base.announceWheelLock),
        announceLights = j.optBoolean("announceLights", base.announceLights),
        announceRecording = j.optBoolean("announceRecording", base.announceRecording),
        announceConnection = j.optBoolean("announceConnection", base.announceConnection),
        announceGps = j.optBoolean("announceGps", base.announceGps),
        announceSafetyMode = j.optBoolean("announceSafetyMode", base.announceSafetyMode),
        announceWelcome = j.optBoolean("announceWelcome", base.announceWelcome),
        welcomeTutorialSeen = j.optBoolean("welcomeTutorialSeen", base.welcomeTutorialSeen),
        autoRecord = j.optBoolean("autoRecord", base.autoRecord),
        autoRecordStartInMotion = j.optBoolean(
            "autoRecordStartInMotion",
            j.optBoolean("autoRecordOnlyInMotion", base.autoRecordStartInMotion)
        ),
        autoRecordStopIdleSeconds = j.optInt("autoRecordStopIdleSeconds", base.autoRecordStopIdleSeconds),
        flic1Name = j.optString("flic1Name", base.flic1Name),
        flic1Click = j.optString("flic1Click", base.flic1Click),
        flic1DoubleClick = j.optString("flic1DoubleClick", base.flic1DoubleClick),
        flic1Hold = j.optString("flic1Hold", base.flic1Hold),
        flic2Name = j.optString("flic2Name", base.flic2Name),
        flic2Click = j.optString("flic2Click", base.flic2Click),
        flic2DoubleClick = j.optString("flic2DoubleClick", base.flic2DoubleClick),
        flic2Hold = j.optString("flic2Hold", base.flic2Hold),
        flic3Name = j.optString("flic3Name", base.flic3Name),
        flic3Click = j.optString("flic3Click", base.flic3Click),
        flic3DoubleClick = j.optString("flic3DoubleClick", base.flic3DoubleClick),
        flic3Hold = j.optString("flic3Hold", base.flic3Hold),
        flic4Name = j.optString("flic4Name", base.flic4Name),
        flic4Click = j.optString("flic4Click", base.flic4Click),
        flic4DoubleClick = j.optString("flic4DoubleClick", base.flic4DoubleClick),
        flic4Hold = j.optString("flic4Hold", base.flic4Hold),
        flicShowOnDashboard = j.optBoolean("flicShowOnDashboard", base.flicShowOnDashboard),
        autoLightsEnabled = j.optBoolean("autoLightsEnabled", base.autoLightsEnabled),
        autoLightsOnMinutesBefore = j.optInt("autoLightsOnMinutesBefore", base.autoLightsOnMinutesBefore),
        autoLightsOffMinutesAfter = j.optInt("autoLightsOffMinutesAfter", base.autoLightsOffMinutesAfter),
        autoVolumeEnabled = j.optBoolean("autoVolumeEnabled", base.autoVolumeEnabled),
        autoVolumeCurve = j.optString("autoVolumeCurve", base.autoVolumeCurve),
        autoVolumeBaselinePercent = j.optInt("autoVolumeBaselinePercent", base.autoVolumeBaselinePercent),
        alarmsMuted = j.optBoolean("alarmsMuted", base.alarmsMuted),
        imperialUnits = j.optBoolean("imperialUnits", base.imperialUnits),
        unitSpeed = j.optString("unitSpeed", base.unitSpeed),
        unitDistance = j.optString("unitDistance", base.unitDistance),
        unitTemp = j.optString("unitTemp", base.unitTemp),
        phoneKeepScreenOn = j.optBoolean("phoneKeepScreenOn", base.phoneKeepScreenOn),
        volumeKeysEnabled = j.optBoolean("volumeKeysEnabled", base.volumeKeysEnabled),
        volumeUpClick = j.optString("volumeUpClick", base.volumeUpClick),
        volumeUpHold = j.optString("volumeUpHold", base.volumeUpHold),
        volumeDownClick = j.optString("volumeDownClick", base.volumeDownClick),
        volumeDownHold = j.optString("volumeDownHold", base.volumeDownHold),
        language = j.optString("language", base.language),
        themeMode = j.optString("themeMode", base.themeMode),
        accentColor = j.optString("accentColor", base.accentColor),
        activeThemeColorsJson = j.optString("activeThemeColorsJson", base.activeThemeColorsJson),
        activeThemeName = j.optString("activeThemeName", base.activeThemeName),
        themeDirty = j.optBoolean("themeDirty", base.themeDirty),
        unsavedThemesJson = j.optString("unsavedThemesJson", base.unsavedThemesJson),
        themeEditorEnabled = j.optBoolean("themeEditorEnabled", base.themeEditorEnabled),
        showGaugeColorBand = j.optBoolean("showGaugeColorBand", base.showGaugeColorBand),
        gaugeOrangeThresholdPct = j.optInt("gaugeOrangeThresholdPct", base.gaugeOrangeThresholdPct),
        gaugeRedThresholdPct = j.optInt("gaugeRedThresholdPct", base.gaugeRedThresholdPct),
        hapticFeedback = j.optBoolean("hapticFeedback", base.hapticFeedback),
        currentDisplayMode = j.optString("currentDisplayMode", base.currentDisplayMode),
        watchKeepScreenOn = j.optBoolean("watchKeepScreenOn", base.watchKeepScreenOn),
        watchAutoStart = j.optBoolean("watchAutoStart", base.watchAutoStart),
        watchShowWheelBattery = j.optBoolean("watchShowWheelBattery", base.watchShowWheelBattery),
        watchShowPhoneBattery = j.optBoolean("watchShowPhoneBattery", base.watchShowPhoneBattery),
        watchShowWatchBattery = j.optBoolean("watchShowWatchBattery", base.watchShowWatchBattery),
        watchPwmDisplay = j.optString("watchPwmDisplay", base.watchPwmDisplay),
        watchShowSpeedUnit = j.optBoolean("watchShowSpeedUnit", base.watchShowSpeedUnit),
        watchEnableGpsSpeed = j.optBoolean("watchEnableGpsSpeed", base.watchEnableGpsSpeed),
        watchStem1Click = j.optString("watchStem1Click", base.watchStem1Click),
        watchStem1Hold = j.optString("watchStem1Hold", base.watchStem1Hold),
        watchStem2Click = j.optString("watchStem2Click", base.watchStem2Click),
        watchStem2Hold = j.optString("watchStem2Hold", base.watchStem2Hold),
        watchScreen1Click = j.optString("watchScreen1Click", base.watchScreen1Click),
        watchScreen1Hold = j.optString("watchScreen1Hold", base.watchScreen1Hold),
        watchScreen2Click = j.optString("watchScreen2Click", base.watchScreen2Click),
        watchScreen2Hold = j.optString("watchScreen2Hold", base.watchScreen2Hold),
        watchHapticOnAction = j.optBoolean("watchHapticOnAction", base.watchHapticOnAction),
        watchUpdateRate = when {
            j.has("watchUpdateRate") -> j.optString("watchUpdateRate", base.watchUpdateRate)
            // Migrate the legacy boolean: ON used the 150 ms fast poll, OFF the
            // 250 ms default. The new 500 ms "CONSERVATIVE" tier is opt-in only.
            j.has("fasterRefresh") -> if (j.optBoolean("fasterRefresh", false)) "FAST" else "NORMAL"
            else -> base.watchUpdateRate
        },
        watchCloseOnExit = j.optBoolean("watchCloseOnExit", base.watchCloseOnExit),
        watchPrioritizePwm = j.optBoolean("watchPrioritizePwm", base.watchPrioritizePwm),
        watchDialRotationDeg = j.optInt("watchDialRotationDeg", base.watchDialRotationDeg),
        backButtonAction = j.optString("backButtonAction", base.backButtonAction),
        engineSoundEnabled = j.optBoolean("engineSoundEnabled", base.engineSoundEnabled),
        engineType = j.optString("engineType", base.engineType),
        engineVolume = j.optDouble("engineVolume", base.engineVolume.toDouble()).toFloat(),
        engineVolumeAutoEnabled = j.optBoolean("engineVolumeAutoEnabled", base.engineVolumeAutoEnabled),
        engineVolumeAutoCurve = j.optString("engineVolumeAutoCurve", base.engineVolumeAutoCurve),
        engineMuffler = j.optString("engineMuffler", base.engineMuffler),
        engineGearbox = j.optString("engineGearbox", base.engineGearbox),
        engineIdleBehavior = j.optString("engineIdleBehavior", base.engineIdleBehavior),
        engineDecelChar = j.optString("engineDecelChar", base.engineDecelChar),
        engineBrake = j.optString("engineBrake", base.engineBrake),
        engineDuckOnVoice = j.optString("engineDuckOnVoice", base.engineDuckOnVoice),
        engineHeadphonesOnly = j.optBoolean("engineHeadphonesOnly", base.engineHeadphonesOnly),
        raceboxMapX = j.optString("raceboxMapX", base.raceboxMapX),
        raceboxMapY = j.optString("raceboxMapY", base.raceboxMapY),
        raceboxMapZ = j.optString("raceboxMapZ", base.raceboxMapZ),
        gpsPrioritizeExternal = j.optBoolean("gpsPrioritizeExternal", base.gpsPrioritizeExternal),
        gpsShowOnDashboard = j.optBoolean("gpsShowOnDashboard", base.gpsShowOnDashboard),
        navVoiceEnabled = j.optBoolean("navVoiceEnabled", base.navVoiceEnabled),
        navArrivalRadiusM = j.optInt("navArrivalRadiusM", base.navArrivalRadiusM),
        navOffRouteToleranceM = j.optInt("navOffRouteToleranceM", base.navOffRouteToleranceM),
        navHomeJson = j.optString("navHomeJson", base.navHomeJson),
        navWorkJson = j.optString("navWorkJson", base.navWorkJson),
        navDefaultTravelMode = j.optString("navDefaultTravelMode", base.navDefaultTravelMode),
        navGeocoderUrl = j.optString("navGeocoderUrl", base.navGeocoderUrl),
        navRouterUrl = j.optString("navRouterUrl", base.navRouterUrl),
        navCurrentRouteJson = j.optStringOrNull("navCurrentRouteJson", base.navCurrentRouteJson),
        navCurrentRouteSavedAt = j.optLong("navCurrentRouteSavedAt", base.navCurrentRouteSavedAt),
        navMapType = j.optString("navMapType", base.navMapType),
        navUserMarkerPhotoDataUrl = if (j.has("navUserMarkerPhotoDataUrl") && !j.isNull("navUserMarkerPhotoDataUrl"))
            j.optString("navUserMarkerPhotoDataUrl", "").ifBlank { null }
        else base.navUserMarkerPhotoDataUrl,
        navSolveFullPath = j.optBoolean("navSolveFullPath", base.navSolveFullPath),
        watchShowNavigation = j.optBoolean("watchShowNavigation", base.watchShowNavigation),
        hudServerEnabled = j.optBoolean("hudServerEnabled", base.hudServerEnabled),
        hudServerPort = j.optInt("hudServerPort", base.hudServerPort),
        hudIp = j.optString("hudIp", base.hudIp),
        hudCustomOverlayName = j.optString("hudCustomOverlayName", base.hudCustomOverlayName),
        hudCustomOverlayJson = j.optString("hudCustomOverlayJson", base.hudCustomOverlayJson),
        hudScreensEnabled = j.optString("hudScreensEnabled", base.hudScreensEnabled),
        hudScreensOrder = j.optString("hudScreensOrder", base.hudScreensOrder),
        hudMapStyle = j.optString("hudMapStyle", base.hudMapStyle),
        hudMapContrastPct = j.optInt("hudMapContrastPct", base.hudMapContrastPct),
        hudMapBrightnessPct = j.optInt("hudMapBrightnessPct", base.hudMapBrightnessPct),
        studioReplayPhotoFormat = j.optString("studioReplayPhotoFormat", base.studioReplayPhotoFormat),
        studioReplayVideoFormat = j.optString("studioReplayVideoFormat", base.studioReplayVideoFormat),
        studioReplayChromaColor = j.optLong("studioReplayChromaColor", base.studioReplayChromaColor),
        studioReplayForceOpaque = j.optBoolean("studioReplayForceOpaque", base.studioReplayForceOpaque),
        dashboardMetricsColumns = j.optInt("dashboardMetricsColumns", base.dashboardMetricsColumns),
        dashboardActionsColumns = j.optInt("dashboardActionsColumns", base.dashboardActionsColumns),
        dashboardMetricOrder = j.optString("dashboardMetricOrder", base.dashboardMetricOrder),
        dashboardActionOrder = j.optString("dashboardActionOrder", base.dashboardActionOrder),
        dashboardRollingWindowSeconds = j.optInt(
            "dashboardRollingWindowSeconds",
            // Migrate older builds that stored the window in minutes.
            j.optInt("dashboardRollingWindowMinutes", -1)
                .let { if (it > 0) it * 60 else base.dashboardRollingWindowSeconds }
        ),
        dashboardMetricStats = j.optString("dashboardMetricStats", base.dashboardMetricStats),
        dashboardCompositeMetrics = j.optString("dashboardCompositeMetrics", base.dashboardCompositeMetrics),
        dashboardActionGroups = j.optString("dashboardActionGroups", base.dashboardActionGroups),
        dashboardCustomTiles = j.optString("dashboardCustomTiles", base.dashboardCustomTiles),
        dashboardCustomBle = j.optString("dashboardCustomBle", base.dashboardCustomBle),
        chargingEstimateToFull = j.optBoolean("chargingEstimateToFull", base.chargingEstimateToFull),
        chargingAutoOpen = j.optBoolean("chargingAutoOpen", base.chargingAutoOpen),
        chargingDashboardIcon = j.optBoolean("chargingDashboardIcon", base.chargingDashboardIcon)
    )

    /** `optString` returns `""` for null and absent keys, which we cannot
     *  distinguish from a legitimate empty-string value. This helper keeps
     *  null vs explicit value vs absent semantics intact for nullable fields. */
    private fun JSONObject.optStringOrNull(key: String, fallback: String?): String? = when {
        !has(key) -> fallback
        isNull(key) -> null
        else -> optString(key, fallback ?: "")
    }
}
