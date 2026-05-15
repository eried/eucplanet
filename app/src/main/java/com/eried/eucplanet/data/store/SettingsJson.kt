package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AppSettings
import org.json.JSONObject

/**
 * Hand-written AppSettings <-> JSON mapping used by both the on-disk settings
 * store ([SettingsStore]) and the export/restore feature ([com.eried.eucplanet.data.sync.SyncManager]).
 *
 * Fields not present in [toJson] are intentionally omitted from disk-stored
 * settings — they're device-specific (last paired BLE address, external GPS
 * pairing, sync folder URI, last-backup timestamp). Those stay in
 * [AppSettings] for ergonomics but aren't round-tripped.
 *
 * Read path uses [base] as the floor so unknown / older payloads just keep
 * the current default — adding a new AppSettings field never breaks restore.
 */
object SettingsJson {

    fun toJson(s: AppSettings): JSONObject = JSONObject().apply {
        put("tiltbackSpeedKmh", s.tiltbackSpeedKmh)
        put("alarmSpeedKmh", s.alarmSpeedKmh)
        put("safetyTiltbackKmh", s.safetyTiltbackKmh)
        put("safetyAlarmKmh", s.safetyAlarmKmh)
        put("speedCalibrationOffsetPct", s.speedCalibrationOffsetPct)
        put("autoConnect", s.autoConnect)
        put("voiceEnabled", s.voiceEnabled)
        put("voicePeriodicEnabled", s.voicePeriodicEnabled)
        put("voiceOnlyWhenConnected", s.voiceOnlyWhenConnected)
        put("voiceIntervalSeconds", s.voiceIntervalSeconds)
        put("voiceSpeechRate", s.voiceSpeechRate)
        put("voiceLocale", s.voiceLocale)
        put("voiceAudioFocus", s.voiceAudioFocus)
        put("voiceOutputChannel", s.voiceOutputChannel)
        put("voiceReportSpeed", s.voiceReportSpeed)
        put("voiceReportBattery", s.voiceReportBattery)
        put("voiceReportTemp", s.voiceReportTemp)
        put("voiceReportPwm", s.voiceReportPwm)
        put("voiceReportDistance", s.voiceReportDistance)
        put("voiceReportTime", s.voiceReportTime)
        put("triggerReportSpeed", s.triggerReportSpeed)
        put("triggerReportBattery", s.triggerReportBattery)
        put("triggerReportTemp", s.triggerReportTemp)
        put("triggerReportPwm", s.triggerReportPwm)
        put("triggerReportDistance", s.triggerReportDistance)
        put("triggerReportTime", s.triggerReportTime)
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
        put("autoLightsEnabled", s.autoLightsEnabled)
        put("autoLightsOnMinutesBefore", s.autoLightsOnMinutesBefore)
        put("autoLightsOffMinutesAfter", s.autoLightsOffMinutesAfter)
        put("autoVolumeEnabled", s.autoVolumeEnabled)
        put("autoVolumeCurve", s.autoVolumeCurve)
        put("autoVolumeBaselinePercent", s.autoVolumeBaselinePercent)
        put("imperialUnits", s.imperialUnits)
        put("phoneKeepScreenOn", s.phoneKeepScreenOn)
        put("volumeKeysEnabled", s.volumeKeysEnabled)
        put("volumeUpClick", s.volumeUpClick)
        put("volumeUpHold", s.volumeUpHold)
        put("volumeDownClick", s.volumeDownClick)
        put("volumeDownHold", s.volumeDownHold)
        put("language", s.language)
        put("themeMode", s.themeMode)
        put("accentColor", s.accentColor)
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
        put("engineSafetyShown", s.engineSafetyShown)
        put("raceboxMapX", s.raceboxMapX)
        put("raceboxMapY", s.raceboxMapY)
        put("raceboxMapZ", s.raceboxMapZ)
        put("gpsLogAdditional", s.gpsLogAdditional)
        put("gpsPrioritizeExternal", s.gpsPrioritizeExternal)
        put("gpsShowOnDashboard", s.gpsShowOnDashboard)
    }

    fun fromJson(j: JSONObject, base: AppSettings = AppSettings()): AppSettings = base.copy(
        tiltbackSpeedKmh = j.optDouble("tiltbackSpeedKmh", base.tiltbackSpeedKmh.toDouble()).toFloat(),
        alarmSpeedKmh = j.optDouble("alarmSpeedKmh", base.alarmSpeedKmh.toDouble()).toFloat(),
        safetyTiltbackKmh = j.optDouble("safetyTiltbackKmh", base.safetyTiltbackKmh.toDouble()).toFloat(),
        safetyAlarmKmh = j.optDouble("safetyAlarmKmh", base.safetyAlarmKmh.toDouble()).toFloat(),
        speedCalibrationOffsetPct = j.optDouble("speedCalibrationOffsetPct", base.speedCalibrationOffsetPct.toDouble()).toFloat(),
        autoConnect = j.optBoolean("autoConnect", base.autoConnect),
        voiceEnabled = j.optBoolean("voiceEnabled", base.voiceEnabled),
        voicePeriodicEnabled = j.optBoolean("voicePeriodicEnabled", base.voicePeriodicEnabled),
        voiceOnlyWhenConnected = j.optBoolean("voiceOnlyWhenConnected", base.voiceOnlyWhenConnected),
        voiceIntervalSeconds = j.optInt("voiceIntervalSeconds", base.voiceIntervalSeconds),
        voiceSpeechRate = j.optDouble("voiceSpeechRate", base.voiceSpeechRate.toDouble()).toFloat(),
        voiceLocale = j.optString("voiceLocale", base.voiceLocale),
        voiceAudioFocus = j.optString("voiceAudioFocus", base.voiceAudioFocus),
        voiceOutputChannel = j.optString("voiceOutputChannel", base.voiceOutputChannel),
        voiceReportSpeed = j.optBoolean("voiceReportSpeed", base.voiceReportSpeed),
        voiceReportBattery = j.optBoolean("voiceReportBattery", base.voiceReportBattery),
        voiceReportTemp = j.optBoolean("voiceReportTemp", base.voiceReportTemp),
        voiceReportPwm = j.optBoolean("voiceReportPwm", base.voiceReportPwm),
        voiceReportDistance = j.optBoolean("voiceReportDistance", base.voiceReportDistance),
        voiceReportTime = j.optBoolean("voiceReportTime", base.voiceReportTime),
        triggerReportSpeed = j.optBoolean("triggerReportSpeed", base.triggerReportSpeed),
        triggerReportBattery = j.optBoolean("triggerReportBattery", base.triggerReportBattery),
        triggerReportTemp = j.optBoolean("triggerReportTemp", base.triggerReportTemp),
        triggerReportPwm = j.optBoolean("triggerReportPwm", base.triggerReportPwm),
        triggerReportDistance = j.optBoolean("triggerReportDistance", base.triggerReportDistance),
        triggerReportTime = j.optBoolean("triggerReportTime", base.triggerReportTime),
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
        autoLightsEnabled = j.optBoolean("autoLightsEnabled", base.autoLightsEnabled),
        autoLightsOnMinutesBefore = j.optInt("autoLightsOnMinutesBefore", base.autoLightsOnMinutesBefore),
        autoLightsOffMinutesAfter = j.optInt("autoLightsOffMinutesAfter", base.autoLightsOffMinutesAfter),
        autoVolumeEnabled = j.optBoolean("autoVolumeEnabled", base.autoVolumeEnabled),
        autoVolumeCurve = j.optString("autoVolumeCurve", base.autoVolumeCurve),
        autoVolumeBaselinePercent = j.optInt("autoVolumeBaselinePercent", base.autoVolumeBaselinePercent),
        imperialUnits = j.optBoolean("imperialUnits", base.imperialUnits),
        phoneKeepScreenOn = j.optBoolean("phoneKeepScreenOn", base.phoneKeepScreenOn),
        volumeKeysEnabled = j.optBoolean("volumeKeysEnabled", base.volumeKeysEnabled),
        volumeUpClick = j.optString("volumeUpClick", base.volumeUpClick),
        volumeUpHold = j.optString("volumeUpHold", base.volumeUpHold),
        volumeDownClick = j.optString("volumeDownClick", base.volumeDownClick),
        volumeDownHold = j.optString("volumeDownHold", base.volumeDownHold),
        language = j.optString("language", base.language),
        themeMode = j.optString("themeMode", base.themeMode),
        accentColor = j.optString("accentColor", base.accentColor),
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
        engineSafetyShown = j.optBoolean("engineSafetyShown", base.engineSafetyShown),
        raceboxMapX = j.optString("raceboxMapX", base.raceboxMapX),
        raceboxMapY = j.optString("raceboxMapY", base.raceboxMapY),
        raceboxMapZ = j.optString("raceboxMapZ", base.raceboxMapZ),
        gpsLogAdditional = j.optBoolean("gpsLogAdditional", base.gpsLogAdditional),
        gpsPrioritizeExternal = j.optBoolean("gpsPrioritizeExternal", base.gpsPrioritizeExternal),
        gpsShowOnDashboard = j.optBoolean("gpsShowOnDashboard", base.gpsShowOnDashboard)
    )
}
