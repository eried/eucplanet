package com.eried.eucplanet.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.eried.eucplanet.amazfit.AmazfitBridge
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.AppHealthRepository
import com.eried.eucplanet.data.repository.AppWarning
import com.eried.eucplanet.data.repository.MetricsReset
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.TripMeterRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.startLinkFlow
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.garmin.GarminBridge
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.WheelService
import com.eried.eucplanet.voice.VoiceCommandController
import com.eried.eucplanet.wear.WearBridge
import com.eried.eucplanet.weather.WeatherForecast
import com.eried.eucplanet.widget.WeatherWidgetPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The Android side of DashboardPorts: each adapter hands the shared
// DashboardViewModel the existing Android class, unchanged.

/** The Dropbox link flow needs an Activity for its Custom Tab. */
fun DashboardViewModel.linkDropbox(activityContext: Context) =
    dropboxRepository.startLinkFlow(activityContext)

internal fun Location.toPhoneLocation() = PhoneLocation(
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    speed = speed,
    bearing = bearing,
    accuracy = accuracy,
    speedKnown = hasSpeed(),
    bearingKnown = hasBearing(),
    accuracyKnown = hasAccuracy(),
    altitudeKnown = hasAltitude(),
    time = time,
)

class AndroidDashboardTrips(private val trips: TripRepository) : DashboardTrips {
    // App-lifetime, like the repository whose fixes it mirrors.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val recording = trips.recording
    override val tripCount = trips.tripCount
    override val newestTripId = trips.allTrips.map { it.firstOrNull()?.id }
    override val currentTripId = trips.currentTripId
    override val currentFix = trips.currentLocation.map { it?.toPhoneLocation() }
        .stateIn(scope, SharingStarted.Eagerly, trips.currentLocation.value?.toPhoneLocation())
    override val lastKnownFix = trips.lastKnownLocation.map { it?.toPhoneLocation() }
        .stateIn(scope, SharingStarted.Eagerly, trips.lastKnownLocation.value?.toPhoneLocation())
    override suspend fun startRecording() = trips.startRecording()
    override suspend fun stopRecording() = trips.stopRecording()
}

class AndroidTripMeterFeed(meter: TripMeterRepository) : TripMeterFeed {
    override val state = meter.state
}

class AndroidDashboardVoiceCommands(private val commands: VoiceCommandController) : DashboardVoiceCommands {
    override val state = commands.state
    override val showVocabulary = commands.showVocabulary
    override fun listen() = commands.listen()
    override fun ask() = commands.ask()
    override fun showingListeningCue(): () -> Unit = commands.showingListeningCue()
}

class AndroidDashboardAutomation(private val automation: AutomationManager) : DashboardAutomation {
    override val autoLightsSuspended = automation.autoLightsSuspended
    override val autoLockSuspended = automation.autoLockSuspended
    override fun notifyManualLightChange() = automation.notifyManualLightChange()
    override fun notifyManualLockChange() = automation.notifyManualLockChange()
    override fun clearLightsSuspension() = automation.clearLightsSuspension()
    override fun clearLockSuspension() = automation.clearLockSuspension()
    override fun triggerImmediateLightEvaluation() = automation.triggerImmediateLightEvaluation()
}

class AndroidActionDispatcher(private val flic: FlicManager) : ActionDispatcher {
    override val lastActionAt = flic.lastActionAt
    override fun dispatchActionByName(actionName: String) = flic.dispatchActionByName(actionName)
}

class AndroidDashboardSync(private val sync: SyncManager) : DashboardSync {
    override val syncRunning = sync.syncRunning
    override val riderStoreId = sync.riderStoreId
    override suspend fun backupSettings() = sync.backupSettings()
    override suspend fun restoreSettings() = sync.restoreSettings()
    override suspend fun restoreSettingsFrom(fileName: String) = sync.restoreSettingsFrom(fileName)
    override suspend fun restoreFactoryDefaults() = sync.restoreFactoryDefaults()
    override suspend fun listSettingsBackups() = sync.listSettingsBackups()
    override suspend fun setSyncFolder(uri: String) = sync.setSyncFolder(android.net.Uri.parse(uri))
    override suspend fun findRestorableRider() = sync.findRestorableRider()
    override suspend fun writeRiderId(storeId: String) = sync.writeRiderId(storeId)
    override fun startSync() = sync.startSync()
}

class AndroidAppHealth(private val health: AppHealthRepository) : AppHealth {
    override val warnings = health.warnings
    override fun upsert(warning: AppWarning) = health.upsert(warning)
    override fun dismiss(id: String) = health.dismiss(id)
    override fun refreshBackupWarning(hasFolder: Boolean, hasTrips: Boolean) =
        health.refreshBackupWarning(hasFolder, hasTrips)
}

class AndroidMetricsResetter(private val reset: MetricsReset) : MetricsResetter {
    override suspend fun resetAll() = MetricsResetResult(reset.resetAll().wheelTripCleared)
}

class AndroidActiveRoute(nav: NavigationEngine) : ActiveRoute {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val activeWaypoints = nav.activeLeg.map { it?.waypoints }
        .stateIn(scope, SharingStarted.Eagerly, nav.activeLeg.value?.waypoints)
}

class AndroidDashboardPlatform(
    private val context: Context,
    private val wearBridge: WearBridge,
    private val garminBridge: GarminBridge,
    private val amazfitBridge: AmazfitBridge,
) : DashboardPlatform {

    override fun placeName(lat: Double, lon: Double): String? {
        @Suppress("DEPRECATION")
        val a = android.location.Geocoder(context, java.util.Locale.getDefault())
            .getFromLocation(lat, lon, 1)?.firstOrNull()
        // A PLACE, never a house number. featureName and even
        // subLocality come back as the street number from some
        // providers, which is how a rider on number 168 ended up with
        // a chip that just said "168". Anything without a letter in it
        // is not a place name, so it is skipped, and the city is
        // preferred over the street: this labels a forecast, which is
        // a city-scale thing.
        return listOfNotNull(
            a?.locality, a?.subLocality, a?.subAdminArea,
            a?.thoroughfare, a?.adminArea, a?.featureName,
        ).firstOrNull { it.isNotBlank() && it.any { c -> c.isLetter() } }
    }

    override fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    override fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    override fun locationServicesOn(): Boolean =
        (context.getSystemService(Context.LOCATION_SERVICE)
            as? android.location.LocationManager)
            ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true

    override fun publishWeatherWidgets(forecast: WeatherForecast, settings: AppSettings, place: String?) =
        WeatherWidgetPublisher.publish(context, forecast, settings, place)

    // Without BLUETOOTH_CONNECT, BleConnectionManager.connect() throws a
    // SecurityException out of the BluetoothGatt binder and the process dies;
    // see DashboardViewModel.autoConnectIfNeeded.
    override fun mayAutoConnect(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    override fun connect(address: String, name: String?) {
        val intent = Intent(context, WheelService::class.java).apply {
            action = WheelService.ACTION_CONNECT
            putExtra(WheelService.EXTRA_ADDRESS, address)
            putExtra(WheelService.EXTRA_NAME, name)
            putExtra(WheelService.EXTRA_AUTO, true)
        }
        context.startForegroundService(intent)
    }

    override fun disconnect() {
        val intent = Intent(context, WheelService::class.java).apply {
            action = WheelService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    override fun closeWatchesBlocking() {
        // Wear hands the QUIT to Google Play Services, which delivers
        // it even after our process dies, so fire-and-forget is fine
        // and we don't want to block the kill waiting on it.
        CoroutineScope(Dispatchers.IO).launch {
            try { wearBridge.sendCloseToWatchBlocking() } catch (_: Exception) {}
        }
        // Garmin's hand-off (Connect Mobile, or the tethered
        // simulator socket) does NOT survive our death, so this MUST
        // finish before we SIGKILL. sendCloseToWatchBlocking now
        // actually blocks until the SDK reports the send.
        garminBridge.sendCloseToWatchBlocking()
        // Amazfit polls, so the QUIT waits (briefly) to be fetched.
        amazfitBridge.sendCloseToWatchBlocking()
    }

    override fun stopAllAndExit() {
        // Send ACTION_STOP_ALL_AND_KILL via startService so the service's
        // onStartCommand can flip the kill-on-destroy flag before stopSelf
        // triggers onDestroy. A plain stopService(intent) would skip
        // onStartCommand entirely and leave the flag unset, so the kill
        // path wouldn't fire and the OS would keep the process cached
        // (the original "Stop All didn't take" bug).
        val intent = Intent(context, WheelService::class.java).apply {
            action = WheelService.ACTION_STOP_ALL_AND_KILL
        }
        try { context.startService(intent) } catch (_: Exception) {
            // startService can throw if the app is already in the background
            // (Android O+ restrictions). Fall back to plain stopService so
            // the service at least tears down, even if the SIGKILL doesn't
            // fire and the OS keeps the process briefly cached.
            context.stopService(Intent(context, WheelService::class.java))
        }
    }
}
