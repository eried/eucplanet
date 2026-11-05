package com.eried.eucplanet.ui.dashboard

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripMeterState
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.AppWarning
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.sync.BackupEntry
import com.eried.eucplanet.data.sync.RestorableRider
import com.eried.eucplanet.voice.VoiceCommandUiState
import com.eried.eucplanet.weather.WeatherForecast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// What DashboardViewModel needs from the rest of the app, one small interface
// per collaborator with only the members it calls, like WheelRepositoryPorts.
// The Android classes (TripRepository, VoiceService, SyncManager...) implement
// them as they are; iOS supplies its own or a no-op until the feature lands.

/** The trip recorder and the phone's location (TripRepository). */
interface DashboardTrips {
    val recording: StateFlow<Boolean>
    val tripCount: Flow<Int>
    /** The newest trip's id, null when there are none. */
    val newestTripId: Flow<Long?>
    val currentTripId: StateFlow<Long?>
    val currentFix: StateFlow<PhoneLocation?>
    val lastKnownFix: StateFlow<PhoneLocation?>
    suspend fun startRecording()
    suspend fun stopRecording()
}

/** The connect-scoped trip meter (TripMeterRepository). */
interface TripMeterFeed {
    val state: StateFlow<TripMeterState>
}

/** Spoken output (VoiceService). */
interface DashboardVoice {
    fun initialize()
    fun welcomeOnce()
    fun speakWelcomeNow()
    fun announceTrigger(data: WheelData, settings: AppSettings, isRecording: Boolean)
    fun shutdown()
}

/** Spoken commands (VoiceCommandController). */
interface DashboardVoiceCommands {
    val state: StateFlow<VoiceCommandUiState>
    val showVocabulary: SharedFlow<Unit>
    fun listen()
    fun ask()
    fun showingListeningCue(): () -> Unit
}

/** The light and lock automations (AutomationManager). */
interface DashboardAutomation {
    val autoLightsSuspended: StateFlow<Boolean>
    val autoLockSuspended: StateFlow<Boolean>
    fun notifyManualLightChange()
    fun notifyManualLockChange()
    fun clearLightsSuspension()
    fun clearLockSuspension()
    fun triggerImmediateLightEvaluation()
}

/** The shared action dispatch behind Flic, volume keys and the watch (FlicManager). */
interface ActionDispatcher {
    val lastActionAt: StateFlow<Long>
    fun dispatchActionByName(actionName: String)
}

/** Settings backup and trip sync (SyncManager). */
interface DashboardSync {
    val syncRunning: StateFlow<Boolean>
    val riderStoreId: StateFlow<String?>
    suspend fun backupSettings(): Boolean
    suspend fun restoreSettings(): Boolean
    suspend fun restoreSettingsFrom(fileName: String): Boolean
    suspend fun restoreFactoryDefaults(): Boolean
    suspend fun listSettingsBackups(): List<BackupEntry>
    /** [uri] is the picked folder, as the platform spells it. */
    suspend fun setSyncFolder(uri: String): Boolean
    suspend fun findRestorableRider(): RestorableRider?
    suspend fun writeRiderId(storeId: String): Boolean
    fun startSync()
}

/** "Needs attention" (AppHealthRepository). */
interface AppHealth {
    val warnings: StateFlow<List<AppWarning>>
    fun upsert(warning: AppWarning)
    fun dismiss(id: String)
    fun refreshBackupWarning(hasFolder: Boolean, hasTrips: Boolean)
}

/** What a reset cleared; see MetricsReset on Android. */
data class MetricsResetResult(val wheelTripCleared: Boolean)

/** Clears the trip meter and metric history (MetricsReset). */
interface MetricsResetter {
    suspend fun resetAll(): MetricsResetResult
}

/** The navigator's active route (NavigationEngine), for the weather destination. */
interface ActiveRoute {
    /** The active leg's stops, null while no route runs. */
    val activeWaypoints: StateFlow<List<Waypoint>?>
}

/** The one flag the experimental banner keeps. */
interface ExperimentalBannerFlag {
    var explainerSeen: Boolean
}

/** Things only the phone's own platform does for the dashboard. */
interface DashboardPlatform {
    /** A city-scale place name for the forecast chip, or null. Blocking. */
    fun placeName(lat: Double, lon: Double): String?
    fun hasFineLocationPermission(): Boolean
    /** Fine or coarse. */
    fun hasLocationPermission(): Boolean
    fun locationServicesOn(): Boolean
    /** Hands the forecast to the home screen widgets; a no-op without one. */
    fun publishWeatherWidgets(forecast: WeatherForecast, settings: AppSettings, place: String?)
    /** False until the app may open a Bluetooth connection on its own. */
    fun mayAutoConnect(): Boolean
    fun connect(address: String, name: String?)
    fun disconnect()
    /**
     * Asks paired watches to close, best effort, returning once the ones that
     * would not survive the process are told. Blocking.
     */
    fun closeWatchesBlocking()
    /** Stops the wheel service and ends the process. */
    fun stopAllAndExit()
}
