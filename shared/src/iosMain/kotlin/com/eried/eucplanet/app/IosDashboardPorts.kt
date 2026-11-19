package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.TripMeterRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.BackupEntry
import com.eried.eucplanet.data.sync.BackupFolderSync
import com.eried.eucplanet.data.sync.RestorableRider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.flic.MediaControls
import com.eried.eucplanet.flic.PhoneControls
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.hasTorch
import platform.AVFoundation.setTorchMode
import platform.AVFoundation.torchMode
import com.eried.eucplanet.flic.VoiceListening
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

/**
 * The PHONE_* actions on iOS. An app cannot change the iPhone's volume or
 * mute it, so those three only log; the flashlight works through the
 * camera's torch.
 */
internal object IosPhoneControls : PhoneControls {
    override fun stepVolume(direction: Int) { Log.i("Actions", "phone volume not on iOS") }
    override fun toggleMute() { Log.i("Actions", "phone mute not on iOS") }
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override fun toggleTorch() {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return
        if (!device.hasTorch) return
        if (!device.lockForConfiguration(null)) return
        device.setTorchMode(
            if (device.torchMode == AVCaptureTorchModeOn) AVCaptureTorchModeOff else AVCaptureTorchModeOn
        )
        device.unlockForConfiguration()
    }
}

/**
 * The backup folder (Android's SyncManager) on the iPhone, through the
 * shared folder sync ([IosFolderSync]): the settings backups, the folder
 * pick and its Sync all. The rider id is the iPhone's own (IosEucStats),
 * written into the folder too; a rider found in the folder is the one to
 * offer when this phone has none.
 */
internal object IosDashboardSync : DashboardSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val folder get() = IosFolderSync.sync

    /** One Sync all at a time, as SyncManager's: the folder's or Dropbox's. */
    override val syncRunning: StateFlow<Boolean> =
        combine(IosFolderSync.running, IosDropboxSync.running) { f, d -> f || d }
            .stateIn(scope, SharingStarted.Eagerly, false)
    override val riderStoreId: StateFlow<String?> get() = IosEucStats.riderStoreId
    override suspend fun backupSettings() = folder.backupSettings()
    override suspend fun restoreSettings() = folder.restoreSettingsFrom(BackupFolderSync.SETTINGS_BACKUP_NAME)
    override suspend fun restoreSettingsFrom(fileName: String) = folder.restoreSettingsFrom(fileName)
    override suspend fun restoreFactoryDefaults() = folder.restoreFactoryDefaults()
    override suspend fun listSettingsBackups(): List<BackupEntry> = folder.listSettingsBackups()
    override suspend fun setSyncFolder(uri: String) = IosFolderSync.setFolder(uri)
    override suspend fun findRestorableRider(): RestorableRider? = folder.findRestorableRider()
    override suspend fun writeRiderId(storeId: String) = IosEucStats.writeRiderId(storeId)
    override fun startSync() = IosFolderSync.syncNow()
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
    override fun publishWeatherWidgets(forecast: WeatherForecast, settings: AppSettings, place: String?) =
        IosWeatherWidgets.publish(forecast, settings, place)
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
        // As WheelService.onDestroy: the automations forget this session.
        IosRideSession.session.resetAutomations()
        wheel.disconnect()
    }
}
