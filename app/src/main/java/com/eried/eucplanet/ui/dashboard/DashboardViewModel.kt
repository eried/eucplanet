package com.eried.eucplanet.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.FullMetricHistory
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.R
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class MetricHistory(
    val battery: List<Float> = emptyList(),
    val temperature: List<Float> = emptyList(),
    val voltage: List<Float> = emptyList(),
    val current: List<Float> = emptyList(),
    val load: List<Float> = emptyList(),
    val speed: List<Float> = emptyList()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val voiceService: VoiceService,
    private val automationManager: AutomationManager,
    private val flicManager: FlicManager,
    private val syncManager: SyncManager,
    private val externalGpsRepository: com.eried.eucplanet.data.repository.ExternalGpsRepository,
    val experimentalBannerState: com.eried.eucplanet.ui.common.ExperimentalBannerState,
    val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val wearBridge: com.eried.eucplanet.wear.WearBridge,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val SPARKLINE_SIZE = 300  // 5 minutes at 1 sample/sec
    }

    // Synchronous initial settings read so StateFlows start with the user's persisted values
    // instead of hardcoded defaults (prevents a visible flash on app open).
    private val initialSettings: com.eried.eucplanet.data.model.AppSettings =
        runBlocking(Dispatchers.IO) { settingsRepository.get() }

    val wheelData: StateFlow<com.eried.eucplanet.data.model.WheelData> = wheelRepository.wheelData

    val connectionState: StateFlow<ConnectionState> = wheelRepository.connectionState

    val safetySpeedActive: StateFlow<Boolean> = wheelRepository.safetySpeedActive

    val locked: StateFlow<Boolean> = wheelRepository.locked
    val lockBusy: StateFlow<Boolean> = wheelRepository.lockBusy
    val lightBusy: StateFlow<Boolean> = wheelRepository.lightBusy

    val recording: StateFlow<Boolean> = tripRepository.recording

    val tripCount: StateFlow<Int> = tripRepository.tripCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Newest trip id (first row of trips sorted by startTime DESC), or null if none.
    val latestTripId: StateFlow<Long?> = tripRepository.allTrips
        .map { it.firstOrNull()?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentTripId: StateFlow<Long?> = tripRepository.currentTripId

    /**
     * Live external-GPS speed in km/h, or null when no device is paired or no
     * sample has arrived recently. The dashboard's speedometer renders a small
     * accent-coloured marker on the dial and a numeric readout under the main
     * speed when this is non-null.
     */
    /**
     * Extra speed indicator on the dial. Honors the GPS settings:
     *  - [AppSettings.gpsShowOnDashboard] off → no indicator
     *  - [AppSettings.gpsPrioritizeExternal] on AND external sample fresh → external
     *  - else → phone GPS (when fix available)
     *
     * Emits a `Pair<speedKmh, sourceKey>` where sourceKey is "EXTERNAL" or
     * "PHONE" so the dashboard can pick the colour. Null when nothing to show.
     */
    /** True when the rider has an external GPS paired in settings, regardless
     *  of whether it's currently connected or sending samples. Drives the
     *  visibility of the "E" indicator on the dashboard so users without an
     *  external GPS don't see a placeholder for a feature they don't use. */
    val externalGpsPaired: StateFlow<Boolean> = settingsRepository.settings
        .map { it.externalGpsAddress != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val gpsExtraSpeed: StateFlow<Pair<Float, String>?> = kotlinx.coroutines.flow.combine(
        settingsRepository.settings,
        externalGpsRepository.currentSample,
        tripRepository.currentLocation
    ) { settings, externalSample, location ->
        if (!settings.gpsShowOnDashboard) return@combine null
        val externalFresh = externalSample != null &&
            System.currentTimeMillis() - externalSample.timestamp < 5_000L
        when {
            settings.gpsPrioritizeExternal && externalFresh ->
                externalSample!!.speedKmh to "EXTERNAL"
            location != null && location.hasSpeed() ->
                (location.speed * 3.6f) to "PHONE"
            !settings.gpsPrioritizeExternal && externalFresh ->
                externalSample!!.speedKmh to "EXTERNAL"
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Convenience: just the speed part of [gpsExtraSpeed] for legacy callers. */
    val externalGpsSpeedKmh: StateFlow<Float?> = gpsExtraSpeed
        .map { it?.first }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gpsFix: StateFlow<Boolean> = tripRepository.currentLocation
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _locationPermissionGranted = kotlinx.coroutines.flow.MutableStateFlow(hasLocationPermission())
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun refreshLocationPermission() {
        _locationPermissionGranted.value = hasLocationPermission()
    }

    val tiltbackSpeed: StateFlow<Float> = settingsRepository.settings
        .map { it.tiltbackSpeedKmh }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.tiltbackSpeedKmh)

    val safetyTiltbackSpeed: StateFlow<Float> = settingsRepository.settings
        .map { it.safetyTiltbackKmh }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.safetyTiltbackKmh)

    val speedUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveSpeedUnit(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            com.eried.eucplanet.util.Units.effectiveSpeedUnit(initialSettings))

    val distanceUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveDistanceUnit(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            com.eried.eucplanet.util.Units.effectiveDistanceUnit(initialSettings))

    val tempUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveTempUnit(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            com.eried.eucplanet.util.Units.effectiveTempUnit(initialSettings))

    /** What the dashboard's BackHandler should do — "ASK" / "BACKGROUND" / "STOP_ALL". */
    val backButtonAction: StateFlow<String> = settingsRepository.settings
        .map { it.backButtonAction }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.backButtonAction)

    val accentKey: StateFlow<String> = settingsRepository.settings
        .map { it.accentColor }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.accentColor)

    val showGaugeColorBand: StateFlow<Boolean> = settingsRepository.settings
        .map { it.showGaugeColorBand }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.showGaugeColorBand)

    // Orange/red threshold percentages for the gauge color band (both 0-100).
    val gaugeOrangePct: StateFlow<Int> = settingsRepository.settings
        .map { it.gaugeOrangeThresholdPct }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.gaugeOrangeThresholdPct)

    val gaugeRedPct: StateFlow<Int> = settingsRepository.settings
        .map { it.gaugeRedThresholdPct }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.gaugeRedThresholdPct)

    val currentDisplayMode: StateFlow<String> = settingsRepository.settings
        .map { it.currentDisplayMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.currentDisplayMode)

    val hasFlicConfigured: StateFlow<Boolean> = settingsRepository.settings
        .map { it.flic1Address != null || it.flic2Address != null || it.flic3Address != null || it.flic4Address != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            initialSettings.flic1Address != null || initialSettings.flic2Address != null ||
                initialSettings.flic3Address != null || initialSettings.flic4Address != null)

    val voicePeriodicEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.voicePeriodicEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.voicePeriodicEnabled)

    val flicFlashAt: StateFlow<Long> = flicManager.lastActionAt

    val hasSyncFolder: StateFlow<Boolean> = settingsRepository.settings
        .map { it.syncFolderUri != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.syncFolderUri != null)

    private val _cloudToasts = Channel<Int>(capacity = Channel.BUFFERED)
    val cloudToasts: Flow<Int> = _cloudToasts.receiveAsFlow()

    fun backupSettingsNow() {
        viewModelScope.launch {
            val ok = syncManager.backupSettings()
            _cloudToasts.send(if (ok) R.string.cloud_backup_success else R.string.cloud_backup_failed)
        }
    }

    fun restoreSettingsNow() {
        viewModelScope.launch {
            val ok = syncManager.restoreSettings()
            _cloudToasts.send(if (ok) R.string.cloud_restore_success else R.string.cloud_restore_failed)
        }
    }

    fun toggleCurrentDisplayMode() {
        viewModelScope.launch {
            val current = settingsRepository.get()
            val next = if (current.currentDisplayMode == "WATTS") "AMPS" else "WATTS"
            settingsRepository.update(current.copy(currentDisplayMode = next))
        }
    }

    fun toggleVoicePeriodic() {
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(current.copy(voicePeriodicEnabled = !current.voicePeriodicEnabled))
        }
    }

    fun startRecording() {
        viewModelScope.launch { tripRepository.startRecording() }
    }

    fun stopRecording() {
        viewModelScope.launch { tripRepository.stopRecording() }
    }

    val modelName: StateFlow<String?> = wheelRepository.modelName

    /** Connected wheel's BLE name (with " (virtual)" for simulators), shown in the top bar. */
    val connectedDeviceName: StateFlow<String?> = wheelRepository.connectedDeviceName

    /** Connected wheel's brand (InMotion / Begode / ...). */
    val connectedBrand: StateFlow<String?> = wheelRepository.connectedBrand

    /** How the top bar labels the connected wheel: MODEL / BRAND / NONE. */
    val wheelNameDisplay: StateFlow<String> = settingsRepository.settings
        .map { it.wheelNameDisplay }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MODEL")

    val firmwareVersion: StateFlow<String?> = wheelRepository.firmwareVersion

    val fullHistory: StateFlow<FullMetricHistory> = wheelRepository.fullHistory

    // Sparklines: last 60 samples from full history
    val history: StateFlow<MetricHistory> = wheelRepository.fullHistory
        .map { full ->
            MetricHistory(
                battery = full.battery.takeLast(SPARKLINE_SIZE).map { it.value },
                temperature = full.temperature.takeLast(SPARKLINE_SIZE).map { it.value },
                voltage = full.voltage.takeLast(SPARKLINE_SIZE).map { it.value },
                current = full.current.takeLast(SPARKLINE_SIZE).map { it.value },
                load = full.load.takeLast(SPARKLINE_SIZE).map { it.value },
                speed = full.speed.takeLast(SPARKLINE_SIZE).map { it.value }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MetricHistory())

    init {
        voiceService.initialize()
        voiceService.welcomeOnce()
        autoConnectIfNeeded()
    }

    private fun autoConnectIfNeeded() {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            // Skip auto-connect entirely until the rider has granted
            // BLUETOOTH_CONNECT. Without it, BleConnectionManager.connect()
            // throws a SecurityException out of the BluetoothGatt binder and
            // the process dies — and on a first-run flow with a backup-restored
            // lastDeviceAddress this fires before the rider has seen the
            // permission dialog, taking the activity down with it and
            // leaving the system permission UI orphaned over the launcher.
            // The Dashboard re-arms auto-connect on the next launch once
            // the permission is in place (this VM is created fresh each
            // process start), so no manual retry is needed.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }
            if (settings.autoConnect && settings.lastDeviceAddress != null &&
                connectionState.value == ConnectionState.DISCONNECTED
            ) {
                connectToLastDevice()
            }
        }
    }

    fun stopEverything() {
        // If the user opted in, ask the paired watch to close its app too so
        // its dial doesn't sit on a stale frame after we tear the session
        // down. Fire-and-forget on a background dispatcher so the activity
        // finish() that follows isn't blocked on the message round-trip.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.get()
                if (settings.watchCloseOnExit) {
                    wearBridge.sendCloseToWatchBlocking()
                }
            } catch (_: Exception) { /* best effort */ }
        }
        val intent = Intent(context, WheelService::class.java)
        context.stopService(intent)
    }

    fun onHornPress() {
        wheelRepository.sendHorn()
    }

    fun onLightToggle() {
        automationManager.notifyManualLightChange()
        wheelRepository.toggleLight()
    }

    fun onLockToggle() {
        wheelRepository.toggleLock()
    }

    fun onSafetySpeedToggle() {
        viewModelScope.launch {
            wheelRepository.toggleSafetySpeed()
        }
    }

    fun onVoiceAnnounce() {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            voiceService.announceTrigger(
                wheelRepository.wheelData.value,
                settings,
                isRecording = tripRepository.recording.value
            )
        }
    }

    fun connectToLastDevice() {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            val address = settings.lastDeviceAddress ?: return@launch
            val intent = Intent(context, WheelService::class.java).apply {
                action = WheelService.ACTION_CONNECT
                putExtra(WheelService.EXTRA_ADDRESS, address)
                putExtra(WheelService.EXTRA_NAME, settings.lastDeviceName)
            }
            context.startForegroundService(intent)
        }
    }

    fun disconnect() {
        val intent = Intent(context, WheelService::class.java).apply {
            action = WheelService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    override fun onCleared() {
        voiceService.shutdown()
        super.onCleared()
    }
}
