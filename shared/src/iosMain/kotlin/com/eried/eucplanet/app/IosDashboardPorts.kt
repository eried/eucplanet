package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripMeterState
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.AppWarning
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.BackupEntry
import com.eried.eucplanet.data.sync.RestorableRider
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.ui.dashboard.ActionDispatcher
import com.eried.eucplanet.ui.dashboard.ActiveRoute
import com.eried.eucplanet.ui.dashboard.AppHealth
import com.eried.eucplanet.ui.dashboard.DashboardAutomation
import com.eried.eucplanet.ui.dashboard.DashboardPlatform
import com.eried.eucplanet.ui.dashboard.DashboardSync
import com.eried.eucplanet.ui.dashboard.DashboardTrips
import com.eried.eucplanet.ui.dashboard.DashboardVoice
import com.eried.eucplanet.ui.dashboard.DashboardVoiceCommands
import com.eried.eucplanet.ui.dashboard.ExperimentalBannerFlag
import com.eried.eucplanet.ui.dashboard.MetricsResetResult
import com.eried.eucplanet.ui.dashboard.MetricsResetter
import com.eried.eucplanet.ui.dashboard.TripMeterFeed
import com.eried.eucplanet.voice.VoiceCommandUiState
import com.eried.eucplanet.weather.WeatherForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

// The iOS side of DashboardPorts. Like IosWheelPorts, a feature that is not
// on iOS yet answers as the Android app does with it switched off, and is
// replaced when it lands; docs/IOS_PARITY.md tracks which.

/** Trip recording and phone location are not on iOS yet. */
internal object NoTrips : DashboardTrips {
    override val recording: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val tripCount: Flow<Int> = flowOf(0)
    override val newestTripId: Flow<Long?> = flowOf(null)
    override val currentTripId: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()
    override val currentFix: StateFlow<PhoneLocation?> = MutableStateFlow<PhoneLocation?>(null).asStateFlow()
    override val lastKnownFix: StateFlow<PhoneLocation?> = MutableStateFlow<PhoneLocation?>(null).asStateFlow()
    override suspend fun startRecording() = Unit
    override suspend fun stopRecording() = Unit
}

internal object NoTripMeter : TripMeterFeed {
    override val state: StateFlow<TripMeterState> = MutableStateFlow(TripMeterState()).asStateFlow()
}

/** Voice is not on iOS yet. */
internal object NoVoice : DashboardVoice {
    override fun initialize() = Unit
    override fun welcomeOnce() = Unit
    override fun speakWelcomeNow() = Unit
    override fun announceTrigger(data: WheelData, settings: AppSettings, isRecording: Boolean) = Unit
    override fun shutdown() = Unit
}

internal object NoVoiceCommands : DashboardVoiceCommands {
    override val state: StateFlow<VoiceCommandUiState> =
        MutableStateFlow<VoiceCommandUiState>(VoiceCommandUiState.Idle).asStateFlow()
    override val showVocabulary: SharedFlow<Unit> = MutableSharedFlow<Unit>().asSharedFlow()
    override fun listen() = Unit
    override fun ask() = Unit
    override fun showingListeningCue(): () -> Unit = {}
}

/** The light and lock automations are not on iOS yet: never suspended. */
internal object NoAutomation : DashboardAutomation {
    override val autoLightsSuspended: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val autoLockSuspended: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun notifyManualLightChange() = Unit
    override fun notifyManualLockChange() = Unit
    override fun clearLightsSuspension() = Unit
    override fun clearLockSuspension() = Unit
    override fun triggerImmediateLightEvaluation() = Unit
}

/**
 * The action dispatch (FlicManager on Android) is not shared yet, so a
 * dashboard action that goes through it is logged and dropped. The horn,
 * light and lock buttons call the wheel directly and work.
 */
internal object LogActions : ActionDispatcher {
    private val last = MutableStateFlow(0L)
    override val lastActionAt: StateFlow<Long> = last.asStateFlow()
    override fun dispatchActionByName(actionName: String) {
        last.value = System.currentTimeMillis()
        Log.i("Actions", "not on iOS yet: $actionName")
    }
}

/** Folder backup and trip sync are not on iOS; Dropbox settings sync is, elsewhere. */
internal object NoFolderSync : DashboardSync {
    override val syncRunning: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val riderStoreId: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override suspend fun backupSettings() = false
    override suspend fun restoreSettings() = false
    override suspend fun restoreSettingsFrom(fileName: String) = false
    override suspend fun restoreFactoryDefaults() = false
    override suspend fun listSettingsBackups(): List<BackupEntry> = emptyList()
    override suspend fun setSyncFolder(uri: String) = false
    override suspend fun findRestorableRider(): RestorableRider? = null
    override suspend fun writeRiderId(storeId: String) = false
    override fun startSync() = Unit
}

/** Needs attention: the same upsert / dismiss list as Android's repository. */
internal class MemoryAppHealth : AppHealth {
    private val list = MutableStateFlow<List<AppWarning>>(emptyList())
    override val warnings: StateFlow<List<AppWarning>> = list.asStateFlow()
    override fun upsert(warning: AppWarning) {
        list.value = list.value.filterNot { it.id == warning.id } + warning
    }
    override fun dismiss(id: String) {
        list.value = list.value.filterNot { it.id == id }
    }
    // The no-backup warning needs trips, which iOS does not record yet.
    override fun refreshBackupWarning(hasFolder: Boolean, hasTrips: Boolean) = Unit
}

/** Android's MetricsReset without the trip meter, which is not on iOS yet. */
internal class IosMetricsResetter(private val wheel: WheelRepository) : MetricsResetter {
    override suspend fun resetAll(): MetricsResetResult {
        wheel.resetAllHistory()
        wheel.resetRideEnergy()
        // BLE write, off the main thread like every other command path.
        val cleared = withContext(Dispatchers.IO) { wheel.resetTripMeter() }
        return MetricsResetResult(wheelTripCleared = cleared)
    }
}

internal object NoRoute : ActiveRoute {
    override val activeWaypoints: StateFlow<List<Waypoint>?> =
        MutableStateFlow<List<Waypoint>?>(null).asStateFlow()
}

/** Same flag and key as Android's SharedPreferences, in NSUserDefaults. */
internal object IosBannerFlag : ExperimentalBannerFlag {
    private val defaults = NSUserDefaults(suiteName = "experimental_banner")
    override var explainerSeen: Boolean
        get() = defaults.boolForKey("explainer_seen")
        set(value) = defaults.setBool(value, forKey = "explainer_seen")
}

internal class IosDashboardPlatform(private val wheel: WheelRepository) : DashboardPlatform {
    // Location and reverse geocoding arrive with CoreLocation.
    override fun placeName(lat: Double, lon: Double): String? = null
    override fun hasFineLocationPermission() = false
    override fun hasLocationPermission() = false
    override fun locationServicesOn() = false
    // Home screen widgets are not on iOS yet.
    override fun publishWeatherWidgets(forecast: WeatherForecast, settings: AppSettings, place: String?) = Unit
    // CoreBluetooth asks for permission itself on first use.
    override fun mayAutoConnect() = true
    override fun connect(address: String, name: String?) = wheel.connect(address, name, isAuto = true)
    override fun disconnect() = wheel.disconnect()
    override fun closeWatchesBlocking() = Unit
    /** iOS apps do not quit themselves; stopping everything is dropping the wheel. */
    override fun stopAllAndExit() = wheel.disconnect()
}
