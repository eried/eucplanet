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
import com.eried.eucplanet.R
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.model.withUnitsToggled
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val garminBridge: com.eried.eucplanet.garmin.GarminBridge,
    private val appHealthRepository: com.eried.eucplanet.data.repository.AppHealthRepository,
    private val dropboxRepository: com.eried.eucplanet.data.repository.DropboxRepository,
    private val appNotifier: com.eried.eucplanet.util.AppNotifier,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** Live list of user-actionable warnings (e.g. denied notification
     *  permission). The dashboard top-bar shows a warning icon when this is
     *  non-empty and the dialog renders each entry as a Fix-able card. */
    val warnings: StateFlow<List<com.eried.eucplanet.data.repository.AppWarning>> =
        appHealthRepository.warnings

    companion object {
        private const val SPARKLINE_SIZE = 300  // 5 minutes at 1 sample/sec
    }

    // Synchronous initial settings read so StateFlows start with the user's persisted values
    // instead of hardcoded defaults (prevents a visible flash on app open).
    private val initialSettings: com.eried.eucplanet.data.model.AppSettings =
        runBlocking(Dispatchers.IO) { settingsRepository.get() }

    val wheelData: StateFlow<com.eried.eucplanet.data.model.WheelData> = wheelRepository.wheelData

    val connectionState: StateFlow<ConnectionState> = wheelRepository.connectionState

    /** Hardware top-speed cap from the detected wheel model (BegodeModel /
     *  VeteranModel / InMotionV2Model / KingsongModel). Stays at the
     *  WheelRepository DEFAULT_MAX_SPEED_KMH (90) when no wheel is connected
     *  or the model isn't recognised - the dashboard treats that sentinel
     *  as "don't cap" so unknown wheels keep the rider-tilt-back-driven
     *  scale they have today. */
    val wheelMaxSpeedCap: StateFlow<Float> = wheelRepository.maxSpeedCap

    val safetySpeedActive: StateFlow<Boolean> = wheelRepository.safetySpeedActive

    val locked: StateFlow<Boolean> = wheelRepository.locked
    val lockBusy: StateFlow<Boolean> = wheelRepository.lockBusy
    /** True when the connected wheel's adapter implements a BLE lock command.
     *  Drives the dashboard lock button to fall back to a "not supported" hint
     *  on wheels (Veteran / LeaperKim, Begode, etc.) whose firmware doesn't
     *  expose lock over BLE today. */
    val wheelHasLock: StateFlow<Boolean> = wheelRepository.wheelHasLock

    /** Charging state for the dashboard spark icon (hint + tap-to-open). */
    val chargeStatus: StateFlow<com.eried.eucplanet.data.model.ChargeStatus> =
        wheelRepository.chargeStatus
    val lightBusy: StateFlow<Boolean> = wheelRepository.lightBusy

    val recording: StateFlow<Boolean> = tripRepository.recording

    val tripCount: StateFlow<Int> = tripRepository.tripCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Newest trip id (first row of trips sorted by startTime DESC), or null if none.
    val latestTripId: StateFlow<Long?> = tripRepository.allTrips
        .map { it.firstOrNull()?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentTripId: StateFlow<Long?> = tripRepository.currentTripId

    /** True when the rider has an external GPS paired in settings, regardless
     *  of whether it's currently connected or sending samples. Drives the
     *  visibility of the "E" indicator on the dashboard so users without an
     *  external GPS don't see a placeholder for a feature they don't use. */
    val externalGpsPaired: StateFlow<Boolean> = settingsRepository.settings
        .map { it.externalGpsAddress != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Extra speed indicator on the dial. Honors the GPS settings:
     *  - [AppSettings.gpsShowOnDashboard] off → no indicator
     *  - [AppSettings.gpsPrioritizeExternal] on AND external sample fresh → external
     *  - else → phone GPS (when fix available)
     *
     * Emits a `Pair<speedKmh, sourceKey>` where sourceKey is "EXTERNAL" or
     * "PHONE" so the dashboard can pick the colour. Null when nothing to show.
     * The speedometer renders a small accent-coloured marker on the dial and a
     * numeric readout under the main speed when this is non-null.
     */
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

    /** External GPS box battery percent [0..100] for the optional dashboard
     *  tile, freshness-gated to 5 s so a dropped box doesn't leave a stale
     *  number on screen. Null when unpaired, stale, or the box (or its current
     *  frame) reports no battery. */
    val externalGpsBatteryPercent: StateFlow<Int?> = externalGpsRepository.currentSample
        .map { s ->
            val pct = s?.batteryPercent ?: return@map null
            if (System.currentTimeMillis() - s.timestamp < 5_000L) pct else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gpsFix: StateFlow<Boolean> = tripRepository.currentLocation
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Raw location for catalog metrics (altitude / accuracy / GPS speed / heading). */
    val currentLocation = tripRepository.currentLocation

    /**
     * Phone battery percentage (0-100). Polled from the system service
     * via a 30-second tick, since it doesn't change fast enough to
     * justify a registered receiver here. Returns -1 when unavailable.
     */
    val phoneBatteryPercent: StateFlow<Int> = kotlinx.coroutines.flow.flow {
        while (true) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            emit(pct)
            kotlinx.coroutines.delay(30_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

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

    /** What the dashboard's BackHandler should do, "ASK" / "BACKGROUND" / "STOP_ALL". */
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

    /** Whether the dashboard top-bar Flic indicator renders at all.
     *  Controlled by Settings -> Integration -> Flic -> "Show on dashboard". */
    val flicShowOnDashboard: StateFlow<Boolean> = settingsRepository.settings
        .map { it.flicShowOnDashboard }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.flicShowOnDashboard)

    val voicePeriodicEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.voicePeriodicEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.voicePeriodicEnabled)

    /** Whether the dashboard top-bar Battery-monitor (spark) icon renders at all. */
    val chargingDashboardIcon: StateFlow<Boolean> = settingsRepository.settings
        .map { it.chargingDashboardIcon }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.chargingDashboardIcon)

    /** Auto-open the Battery monitor when the wheel starts charging. */
    val chargingAutoOpen: StateFlow<Boolean> = settingsRepository.settings
        .map { it.chargingAutoOpen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.chargingAutoOpen)

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

    /** Whether the first-launch dashboard welcome tour still needs showing.
     *
     *  Seeded from initialSettings (synchronous DataStore read at the top of
     *  this VM) instead of a hardcoded `true`. A `true` seed reads as "tour
     *  already done, hide it" - so on a first-launch cold start the rider
     *  saw an interactive dashboard for ~50-200ms before the upstream Flow
     *  emitted the real `false` and the tour finally appeared. That window
     *  was long enough to tap a destructive action (the "reset" report
     *  from the rider). Now frame zero already has the correct value. */
    val welcomeTutorialSeen: StateFlow<Boolean> = settingsRepository.settings
        .map { it.welcomeTutorialSeen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.welcomeTutorialSeen)

    /** Records that the rider finished or skipped the welcome tour, so it
     *  never auto-shows again. */
    fun markWelcomeTutorialSeen() {
        viewModelScope.launch {
            val current = settingsRepository.get()
            if (!current.welcomeTutorialSeen) {
                settingsRepository.update(current.copy(welcomeTutorialSeen = true))
            }
        }
    }

    /** Flip every spoken-event flag in one shot. Called from the welcome
     *  wizard's voice toggle: turning it on immediately enables every spoken
     *  alert and speaks the welcome greeting as a live preview; turning it
     *  off (still inside the wizard) reverts. The per-event toggles remain
     *  individually adjustable in Settings → Voice afterwards. */
    fun setAllVoiceAnnouncements(enabled: Boolean) {
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(
                s.copy(
                    voicePeriodicEnabled = enabled,
                    announceWheelLock = enabled,
                    announceLights = enabled,
                    announceRecording = enabled,
                    announceConnection = enabled,
                    announceGps = enabled,
                    announceSafetyMode = enabled,
                    announceWelcome = enabled,
                )
            )
            if (enabled) voiceService.speakWelcomeNow()
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

    // ---- Customizable dashboard layout (Phase 2B+) ----
    //
    // The editor in Settings writes these fields; the live dashboard
    // reads them here to drive which tile renders in each slot, with
    // what per-slot stats (sparkline on/off, corner readouts) and
    // dynamic-instance state (composites, custom tiles, action groups).
    //
    // Order strings sanitize against [com.eried.eucplanet.data.model.MetricCatalog]
    // / ActionCatalog upstream; here they're just raw strings until the
    // dashboard composable parses them.
    //
    // Seed each layout StateFlow with the rider's persisted value from
    // initialSettings (read synchronously above), not with an empty
    // string / "{}" placeholder. Otherwise the first composition reads
    // the placeholder, falls back to AppSettings hardcoded defaults
    // (BATTERY/TEMP/VOLT/CURRENT/LOAD/TRIP for metrics, HORN/LIGHT/…
    // for actions) and the rider sees a one-frame flash of the default
    // layout before the upstream Flow emits their saved layout. With
    // these seeded correctly the cold-start render is the rider's own
    // layout from frame zero.
    // "POWER" was retired from the metric catalog as a duplicate of
    // BATTERY_POWER (both read wheelData.batteryPower). Dashboards saved before
    // that still carry the "POWER" token, which now has no catalog spec and
    // renders as a dead placeholder tile -- the value only appears when the
    // rider taps in (the detail screen still maps "POWER"). Rewrite the token
    // to BATTERY_POWER so those tiles come back to life; duplicates collapse via
    // the distinct() in the grid builder.
    private fun migrateLegacyMetricKeys(order: String): String =
        order.split(",").joinToString(",") { tok ->
            if (tok.trim() == "POWER") "BATTERY_POWER" else tok
        }

    val dashboardMetricOrder: StateFlow<String> = settingsRepository.settings
        .map { migrateLegacyMetricKeys(it.dashboardMetricOrder) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            migrateLegacyMetricKeys(initialSettings.dashboardMetricOrder))
    // Screen geometry: compact-mode activation, cover lens cutout side and
    // the optional gauge ring in compact. The compact layout itself reuses
    // the dashboardMetricOrder / dashboardActionOrder lists above.
    // The numeric knobs (compact threshold, cutout inset, speedo scale) come
    // from the Advanced registry via [advanced].
    val advanced: StateFlow<com.eried.eucplanet.data.model.AdvancedSettings> =
        settingsRepository.settings
            .map { it.advanced }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
                initialSettings.advanced)
    val compactModeWhen: StateFlow<String> = settingsRepository.settings
        .map { it.compactModeWhen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.compactModeWhen)
    val coverCameraCutout: StateFlow<String> = settingsRepository.settings
        .map { it.coverCameraCutout }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.coverCameraCutout)
    val compactSpeedoStyle: StateFlow<String> = settingsRepository.settings
        .map { it.compactSpeedoStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.compactSpeedoStyle)
    val landscapeSpeedoStyle: StateFlow<String> = settingsRepository.settings
        .map { it.landscapeSpeedoStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.landscapeSpeedoStyle)
    val landscapeMirrored: StateFlow<Boolean> = settingsRepository.settings
        .map { it.landscapeMirrored }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.landscapeMirrored)
    val dashboardMetricStats: StateFlow<String> = settingsRepository.settings
        .map { it.dashboardMetricStats }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.dashboardMetricStats)
    val dashboardMetricsColumns: StateFlow<Int> = settingsRepository.settings
        .map { it.dashboardMetricsColumns }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.dashboardMetricsColumns)
    val dashboardCompositeMetrics: StateFlow<String> = settingsRepository.settings
        .map { it.dashboardCompositeMetrics }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.dashboardCompositeMetrics)
    val dashboardCustomTiles: StateFlow<String> = settingsRepository.settings
        .map { it.dashboardCustomTiles }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.dashboardCustomTiles)
    val dashboardActionOrder: StateFlow<String> = settingsRepository.settings
        .map { it.dashboardActionOrder }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.dashboardActionOrder)
    val dashboardActionGroups: StateFlow<String> = settingsRepository.settings
        .map { it.dashboardActionGroups }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.dashboardActionGroups)
    val dashboardCustomBle: StateFlow<String> = settingsRepository.settings
        .map { it.dashboardCustomBle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            initialSettings.dashboardCustomBle)

    /** Fires an action by its catalog key via FlicManager - shared dispatch with Flic / volume / watch. */
    fun dispatchActionByName(key: String) = flicManager.dispatchActionByName(key)

    /**
     * Flip the three per-unit fields between metric and imperial in one
     * write. Metric is the reference state, so anything that isn't already
     * a clean imperial trio flips to imperial; a clean imperial trio flips
     * back to metric. Custom mixes (e.g. knots + Norwegian mile from the
     * Settings preset) snap to metric on first tap.
     */
    fun toggleUnits() {
        viewModelScope.launch {
            settingsRepository.update(settingsRepository.get().withUnitsToggled())
        }
    }

    /** Flip the persisted alarm mute. AlarmEngine reads this on every
     *  evaluate() so the change takes effect on the next telemetry frame. */
    fun toggleAlarmsMuted() {
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(current.copy(alarmsMuted = !current.alarmsMuted))
        }
    }

    /** Whether a backup folder is configured. Once set, the dev wizard reveals the
     *  Join and Sync buttons. */
    val backupFolderSet: StateFlow<Boolean> = settingsRepository.settings
        .map { !it.syncFolderUri.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Whether the configured folder actually holds a settings backup. Gates the
     *  dev wizard's Restore button so it appears only when there is something to
     *  restore. Re-checked whenever the folder changes. */
    val hasSettingsBackup: StateFlow<Boolean> = settingsRepository.settings
        .map { it.syncFolderUri }
        .distinctUntilChanged()
        .map { uri -> !uri.isNullOrBlank() && syncManager.listSettingsBackups().isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Dev welcome-wizard backup/restore: thin wrappers over SyncManager,
    // the same calls the Cloud settings screen uses. ---
    fun setBackupFolder(uri: android.net.Uri) {
        viewModelScope.launch { syncManager.setSyncFolder(uri) }
    }

    suspend fun listSettingsBackups(): List<com.eried.eucplanet.data.sync.BackupEntry> =
        syncManager.listSettingsBackups()

    fun restoreSettingsFrom(fileName: String) {
        viewModelScope.launch { syncManager.restoreSettingsFrom(fileName) }
    }

    fun restoreFactoryDefaults() {
        viewModelScope.launch { syncManager.restoreFactoryDefaults() }
    }

    /** Whether a trip sync is running (disables the dev wizard's Sync button while
     *  it works). */
    val syncRunning: StateFlow<Boolean> = syncManager.syncRunning

    /** Whether the rider has already joined leaderboards (online upload enabled).
     *  Gates the dev wizard's Join button so it greys out and stays greyed once
     *  joined, the same disabled-after-action treatment Sync trips gets. */
    val leaderboardsJoined: StateFlow<Boolean> = settingsRepository.settings
        .map { it.onlineUploadEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Fire-and-forget dev "sync trips": the same folder sync the Cloud screen runs.
     *  Posts a toast so the trigger is visible even when there is nothing to sync. */
    fun syncAllTrips() {
        appNotifier.post(context.getString(R.string.welcome_tut_dev_syncing))
        syncManager.startSync()
    }

    /** Whether Dropbox is linked (gates the dev wizard's Link Dropbox button). */
    val dropboxLinked: StateFlow<Boolean> = dropboxRepository.linked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Start the Dropbox OAuth link flow. Needs an Activity context for the Custom
     *  Tab, so the caller passes it down from Compose. */
    fun linkDropbox(activityContext: android.content.Context) =
        dropboxRepository.startLinkFlow(activityContext)

    /** Fire-and-forget dev "join leaderboards": recover the rider from the linked
     *  backup folder if one is there, then enable online upload. The full
     *  onboarding for a brand-new rider stays in Cloud settings; this is the quick
     *  path for a dev whose folder already holds their rider. */
    fun joinLeaderboards() {
        viewModelScope.launch {
            val hasRider = syncManager.riderStoreId.value != null ||
                syncManager.findRestorableRider()?.also { syncManager.writeRiderId(it.storeId) } != null
            settingsRepository.update(settingsRepository.get().copy(onlineUploadEnabled = true))
            appNotifier.post(context.getString(
                if (hasRider) R.string.welcome_tut_dev_joined
                else R.string.welcome_tut_dev_joined_norider
            ))
        }
    }

    /**
     * Send the family-specific "reset onboard trip meter" command to the
     * wheel. Returns true on Veteran (CLEARMETER); false on every other
     * family until a documented reset command is added. Callers should
     * snackbar the result so riders know whether the tap took effect.
     */
    suspend fun resetWheelTrip(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) { wheelRepository.resetTripMeter() }

    val fullHistory: StateFlow<FullMetricHistory> = wheelRepository.fullHistory

    // Sparklines: last SPARKLINE_SIZE samples from full history
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
            // the process dies, and on a first-run flow with a backup-restored
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

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun stopEverything() {
        // Ask the paired watch(es) to close, THEN tear ourselves down.
        //
        // This runs on a process-lifetime scope, NOT viewModelScope: the
        // performStopAllAndExit caller finishes the activity right after this
        // returns (which cancels viewModelScope), and the service SIGKILLs the
        // process a moment later. The Garmin QUIT used to be fire-and-forget on
        // viewModelScope and so lost that race every time -- the watch was left
        // sitting on a stale dial. We now send it on a scope that survives the
        // finish, block until the CIQ hand-off completes, and only THEN fire
        // the kill.
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.get()
                if (settings.watchCloseOnExit) {
                    // Wear hands the QUIT to Google Play Services, which delivers
                    // it even after our process dies, so fire-and-forget is fine
                    // and we don't want to block the kill waiting on it.
                    launch { try { wearBridge.sendCloseToWatchBlocking() } catch (_: Exception) {} }
                    // Garmin's hand-off (Connect Mobile, or the tethered
                    // simulator socket) does NOT survive our death, so this MUST
                    // finish before we SIGKILL. sendCloseToWatchBlocking now
                    // actually blocks until the SDK reports the send.
                    garminBridge.sendCloseToWatchBlocking()
                }
            } catch (_: Exception) { /* best effort */ }
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
                putExtra(WheelService.EXTRA_AUTO, true)
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
