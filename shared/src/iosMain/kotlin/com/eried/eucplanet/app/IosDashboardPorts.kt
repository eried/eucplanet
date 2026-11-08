package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.AppWarning
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.TripMeterRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.BackupEntry
import com.eried.eucplanet.data.sync.RestorableRider
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.flic.MediaControls
import com.eried.eucplanet.flic.VoiceListening
import com.eried.eucplanet.ui.dashboard.ActiveRoute
import com.eried.eucplanet.ui.dashboard.AppHealth
import com.eried.eucplanet.ui.dashboard.DashboardAutomation
import com.eried.eucplanet.ui.dashboard.DashboardPlatform
import com.eried.eucplanet.ui.dashboard.DashboardSync
import com.eried.eucplanet.ui.dashboard.DashboardTrips
import com.eried.eucplanet.ui.dashboard.DashboardVoiceCommands
import com.eried.eucplanet.ui.dashboard.ExperimentalBannerFlag
import com.eried.eucplanet.ui.dashboard.MetricsResetResult
import com.eried.eucplanet.ui.dashboard.MetricsResetter
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLPlacemark
import platform.Foundation.NSUserDefaults
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

// The iOS side of DashboardPorts. Like IosWheelPorts, a feature that is not
// on iOS yet answers as the Android app does with it switched off, and is
// replaced when it lands; docs/IOS_PARITY.md tracks which.

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
 * iOS has no media keys an app can send to another app's player, so the
 * music actions do nothing yet.
 */
internal object NoMedia : MediaControls {
    override fun startMusic() { Log.i("Actions", "music control not on iOS") }
    override fun playPauseOrStart() = startMusic()
    override fun pause() = startMusic()
    override fun next() = startMusic()
    override fun previous() = startMusic()
}

/** Spoken commands are not on iOS yet. */
internal object NoVoiceListening : VoiceListening {
    override fun hasMicPermission() = false
    override fun listen() = Unit
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

/** Android's MetricsReset: the trip meter, the metric history and the ride energy. */
internal class IosMetricsResetter(
    private val wheel: WheelRepository,
    private val tripMeter: TripMeterRepository,
) : MetricsResetter {
    override suspend fun resetAll(): MetricsResetResult {
        tripMeter.resetAndPersist()
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

internal class IosDashboardPlatform(
    private val wheel: WheelRepository,
    private val tripMeter: TripMeterRepository,
) : DashboardPlatform {
    // The weather chip's place, as Android's Geocoder names it. Called off
    // the main thread (the view model's IO dispatcher), so it can wait for
    // CLGeocoder, which answers on the main queue.
    override fun placeName(lat: Double, lon: Double): String? {
        val done = dispatch_semaphore_create(0)
        var name: String? = null
        CLGeocoder().reverseGeocodeLocation(CLLocation(latitude = lat, longitude = lon)) { marks, _ ->
            val p = marks?.firstOrNull() as? CLPlacemark
            // A place, never a house number, the city first: as on Android.
            name = listOfNotNull(
                p?.locality, p?.subLocality, p?.subAdministrativeArea,
                p?.thoroughfare, p?.administrativeArea, p?.name,
            ).firstOrNull { it.isNotBlank() && it.any { c -> c.isLetter() } }
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 10L * NSEC_PER_SEC.toLong()))
        return name
    }

    // CoreLocation through iosApp/LocationBridge.swift. iOS has no coarse-only
    // grant to tell apart, and a phone with location off reports no access.
    override fun hasFineLocationPermission() = IosLocation.authorized.value
    override fun hasLocationPermission() = IosLocation.authorized.value
    override fun locationServicesOn() = IosLocation.authorized.value
    // Home screen widgets are not on iOS yet.
    override fun publishWeatherWidgets(forecast: WeatherForecast, settings: AppSettings, place: String?) = Unit
    // CoreBluetooth asks for permission itself on first use.
    override fun mayAutoConnect() = true
    override fun connect(address: String, name: String?) = wheel.connect(address, name, isAuto = true)
    override fun disconnect() = wheel.disconnect()
    override fun closeWatchesBlocking() = Unit
    /**
     * iOS apps do not quit themselves; stopping everything is dropping the
     * wheel and, as Stop All on Android, clearing the trip meter.
     */
    override fun stopAllAndExit() {
        runBlocking { tripMeter.resetAndPersist() }
        wheel.disconnect()
    }
}
