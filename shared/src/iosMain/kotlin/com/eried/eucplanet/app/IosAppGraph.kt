package com.eried.eucplanet.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.cheats.CheatState
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.ble.radar.IosRadarLink
import com.eried.eucplanet.ble.radar.IosRadarScan
import com.eried.eucplanet.ble.radar.RadarAdapter
import com.eried.eucplanet.ble.radar.VariaAdapter
import com.eried.eucplanet.ble.gps.DragyAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsAdapter
import com.eried.eucplanet.ble.gps.IosExternalGpsLink
import com.eried.eucplanet.ble.gps.IosExternalGpsScan
import com.eried.eucplanet.ble.gps.RaceBoxAdapter
import com.eried.eucplanet.data.repository.AccelSplitRepository
import com.eried.eucplanet.data.repository.ExternalGpsRepository
import com.eried.eucplanet.data.repository.RadarRepository
import com.eried.eucplanet.ui.dashboard.sources.DataSourcesViewModel
import com.eried.eucplanet.ui.settings.ExternalGpsViewModel
import com.eried.eucplanet.tpms.IosTpmsRadio
import com.eried.eucplanet.tpms.SettingsTpmsPairingStore
import com.eried.eucplanet.tpms.TpmsRepository
import com.eried.eucplanet.tpms.TpmsScanner
import com.eried.eucplanet.ui.radar.RadarOverlayViewModel
import com.eried.eucplanet.ui.settings.RadarViewModel
import com.eried.eucplanet.ui.settings.TpmsViewModel
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.repository.DropboxLinkScratch
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.store.AlarmBackupJson
import com.eried.eucplanet.data.store.SettingsStore
import com.eried.eucplanet.data.sync.AlarmRuleStore
import com.eried.eucplanet.data.sync.DropboxSettingsSync
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eried.eucplanet.data.db.AppDatabase
import com.eried.eucplanet.data.model.WheelProfile
import com.eried.eucplanet.data.repository.WheelProfiles
import com.eried.eucplanet.di.SharedKoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import com.eried.eucplanet.ui.dashboard.DashboardViewModel
import com.eried.eucplanet.ui.charging.ChargingMonitorViewModel
import com.eried.eucplanet.flic.ActionExecutor
import com.eried.eucplanet.service.AlarmEngine
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.asAutomationWheel
import com.eried.eucplanet.ui.scan.ScanViewModel
import com.eried.eucplanet.ui.settings.AlarmViewModel
import com.eried.eucplanet.ui.recording.RecordingViewModel
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.weather.WeatherRepository
import com.eried.eucplanet.util.AppNotifier
import com.eried.eucplanet.ui.theme.ThemeController
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
import com.eried.eucplanet.platform.JSONArray
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.stringWithContentsOfFile

/**
 * The iOS app's singletons: what Koin builds on Android. Same classes, iOS
 * storage underneath.
 */
object IosAppGraph {
    internal val documents: String by lazy {
        @OptIn(ExperimentalForeignApi::class)
        val url = NSFileManager.defaultManager.URLForDirectory(
            NSDocumentDirectory, NSUserDomainMask, null, true, null,
        ) as NSURL
        url.path!!
    }

    val settingsStore: SettingsStore by lazy {
        SettingsStore(
            PreferenceDataStoreFactory.createWithPath(
                produceFile = { "$documents/datastore/eucplanet_settings.preferences_pb".toPath() },
            ),
            // Android also asks the cell network; iOS has no reliable carrier
            // country, so the region the phone is set to decides alone.
            isImperialLocale = { NSLocale.currentLocale.countryCode?.uppercase() in setOf("US", "LR", "MM") },
        )
    }

    val settings: SettingsRepository by lazy { SettingsRepository(settingsStore) }

    /**
     * The trip / alarm / wheel profile database, the same Room schema as
     * Android's eucplanet.db, on the bundled SQLite. An iOS install starts at
     * the current version, so it has no migrations yet; a schema bump needs
     * one in common code for iOS.
     */
    val database: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(name = "$documents/eucplanet.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    val adapter: CompositeWheelAdapter by lazy { SharedKoin.koin.get() }

    /** The hidden debug switches, shared with the wheel state as on Android. */
    val cheats: CheatState by lazy { CheatState() }

    val weather: WeatherRepository by lazy { WeatherRepository() }

    /**
     * The active theme, resolved from the same activeThemeName Android keeps in
     * settings.json, so a theme picked on one phone shows on the other.
     */
    internal val themeFiles: IosThemeFiles by lazy { IosThemeFiles(documents) }

    /** The Overlay Studio's draft and presets (Documents/overlays), and its starters. */
    internal val overlayPresets: IosOverlayPresets by lazy { IosOverlayPresets(documents) }

    /** The rider's own horn sound, kept in Documents/horn. */
    internal val horn: IosHorn by lazy { IosHorn("$documents/horn") }

    val theme: ThemeController by lazy {
        ThemeController(settings, themeFiles) {
            UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
    }

    val link: IosWheelLink by lazy { IosWheelLink(adapter) }

    /**
     * The rear-view radar, as Android's radarModule builds it: one adapter
     * set, a link and a pairing scan of their own over CoreBluetooth, and the
     * shared repository, which reconnects a paired radar once it exists.
     */
    private val radarAdapters: Set<RadarAdapter> by lazy { linkedSetOf(VariaAdapter()) }
    val radarLink: IosRadarLink by lazy { IosRadarLink() }
    val radarScan: IosRadarScan by lazy { IosRadarScan(radarAdapters) }
    val radar: RadarRepository by lazy {
        RadarRepository(
            settingsRepository = settings,
            connectionManager = radarLink,
            adapters = radarAdapters,
            alarmEngine = alarmEngine,
        )
    }

    /**
     * The external GPS box (RaceBox, Dragy), as Android's externalGpsModule
     * builds it: both adapters, a link and a pairing scan of their own over
     * CoreBluetooth, and the shared repository, which reconnects a paired box
     * while the app is in front or a ride runs. The virtual RaceBox (Service
     * Mode) runs through the same repository.
     */
    private val externalGpsAdapters: Set<ExternalGpsAdapter> by lazy { linkedSetOf(RaceBoxAdapter(), DragyAdapter()) }
    val externalGpsLink: IosExternalGpsLink by lazy { IosExternalGpsLink() }
    val externalGpsScan: IosExternalGpsScan by lazy { IosExternalGpsScan(externalGpsAdapters) }
    val externalGps: ExternalGpsRepository by lazy {
        ExternalGpsRepository(
            settingsRepository = settings,
            link = externalGpsLink,
            adapters = externalGpsAdapters,
            phoneFix = { IosLocation.fix.value ?: IosLocation.lastKnown.value },
            alarmEngineProvider = lazy { alarmEngine },
            foreground = IosForeground.visible,
            // Android's WheelService runs while a ride does: a wheel or a trip.
            rideRunning = {
                wheel.connectionState.value == com.eried.eucplanet.ble.ConnectionState.CONNECTED ||
                    IosTrips.recording.value
            },
            wheelSpeedKmh = { ExternalGpsRepository.rawWheelSpeedKmh(wheel, settings) },
        )
    }

    /**
     * The tyre caps, as Android's tpmsModule builds them: paired caps kept in
     * settings, the shared repository and scanner, and the CoreBluetooth radio
     * (IosTpmsRadio, which reads each cap's MAC from its payload).
     */
    val tpms: TpmsRepository by lazy { TpmsRepository(SettingsTpmsPairingStore(settings)) }
    val tpmsScanner: TpmsScanner by lazy { TpmsScanner(IosTpmsRadio(), tpms) }

    /**
     * Notices from code with no screen of its own (the wheel, Dropbox, the
     * trip tools), shown by the root snackbar host in MainViewController, as
     * MainActivity shows AppNotifier's on Android.
     */
    val notices: AppNotifier by lazy { AppNotifier() }

    /**
     * Every rider action (dashboard tiles, and Flic once its iOS SDK is in),
     * the same table Android's FlicManager hands its presses to.
     */
    val actions: ActionExecutor by lazy {
        ActionExecutor(
            settings, wheel, IosTrips, voice, automation, notices, IosLockdown.controller,
            NoVoiceListening, IosMedia, IosPhoneControls,
        )
    }

    /**
     * The motor sound, the shared engine Android's WheelService drives, on an
     * AVAudioEngine output (IosEngineAudio). IosRideSession feeds it.
     */
    val engineSound: com.eried.eucplanet.audio.EngineSoundEngine by lazy { IosEngineAudio.newEngine() }

    /** Spoken announcements, the shared reports through AVSpeechSynthesizer. */
    internal val voice: IosVoice by lazy { IosVoice(settings) { wheel } }

    /**
     * The alarm engine, the same rules as Android's. Fires through
     * IosAlarmOutputs (the shared tones, voice and vibration); the GPS speed
     * alarms read the phone's fix through IosTrips.
     */
    val alarmEngine: AlarmEngine by lazy {
        AlarmEngine(IosPhoneDevice, database.alarmDao(), IosAlarmOutputs, cheats, settings, link, lazyOf(IosTrips))
    }

    /** The speed splits' session: fed by the ride session, shown and cleared in Settings. */
    val splits: AccelSplitRepository by lazy { AccelSplitRepository() }

    /** The wheel state every screen reads, as on Android. See IosWheelPorts.kt. */
    val wheel: WheelRepository by lazy {
        WheelRepository(
            bleManager = link,
            wheelAdapter = adapter,
            settingsRepository = settings,
            alarmEngine = alarmEngine,
            tonePlayer = NoRouteKeepAlive,
            voiceService = voice,
            wheelProfileDao = object : WheelProfiles {
                // WheelRepository swallows a failed read, so say it here.
                override suspend fun getByName(name: String) =
                    try { database.wheelProfileDao().getByName(name) } catch (e: Exception) {
                        com.eried.eucplanet.platform.Log.w("WheelProfiles", "read failed", e)
                        throw e
                    }
                override suspend fun upsert(profile: WheelProfile) = database.wheelProfileDao().upsert(profile)
            },
            cheatState = cheats,
            phoneSensorRepository = IosPhoneImu,
            externalGpsRepository = externalGps,
            legalLockdown = IosLockdown.controller,
            tpmsRepository = tpms,
            accelSplitRepository = splits,
            tripRepositoryLazy = lazyOf(IosRideRecording),
            appNotifier = notices,
            hornPlayer = horn,
            phone = IosPhoneDevice,
        )
    }

    /**
     * The light, beeper and proximity lock automations, the same class
     * Android runs. iOS has no media automation (MediaAutomation.None): no app
     * may set the media volume or pause another app's music.
     */
    val automation: AutomationManager by lazy {
        AutomationManager(
            wheel = wheel.asAutomationWheel(),
            lockedDown = IosLockdown.controller::isEngaged,
            location = { IosLocation.fix.value },
        )
    }

    val dropbox: DropboxRepository by lazy {
        DropboxRepository(settings, object : DropboxLinkScratch {
            private val defaults = NSUserDefaults(suiteName = "dropbox_link")
            override var verifier: String?
                get() = defaults.stringForKey("verifier")
                set(value) {
                    if (value == null) defaults.removeObjectForKey("verifier")
                    else defaults.setObject(value, forKey = "verifier")
                }
        })
    }

    /** Alarm rules in the database, as Android's SyncManager keeps them. */
    val alarms: AlarmRuleStore by lazy {
        val dao = database.alarmDao()
        object : AlarmRuleStore {
            override suspend fun getAll(): List<AlarmRule> = dao.getAll()
            override suspend fun replaceAll(rules: List<AlarmRule>) {
                dao.deleteAll()
                rules.forEach { dao.insert(it.copy(id = 0)) }
            }
        }
    }

    internal val legacyAlarmsPath: String get() = "$documents/alarm_rules.json"

    val dropboxSettings: DropboxSettingsSync by lazy {
        // Language follows the iPhone's own setting for now; the in-app
        // language picker is not on iOS yet.
        DropboxSettingsSync(settings, dropbox, alarms, applyLanguage = {})
    }

    /**
     * A dashboard view model, built the way Koin's viewModelOf builds it on
     * Android. See IosDashboardPorts.kt for the ports still switched off.
     */
    fun dashboardViewModel(): DashboardViewModel = DashboardViewModel(
        wheelRepository = wheel,
        settingsRepository = settings,
        tripRepository = IosTrips,
        tripMeterRepository = IosTripMeter.feed,
        voiceService = voice,
        automationManager = automation,
        flicManager = actions,
        syncManager = IosSettingsSync,
        externalGpsRepository = externalGps,
        experimentalBannerState = IosBannerFlag,
        cheatState = cheats,
        appHealthRepository = IosAppHealth,
        metricsReset = IosMetricsResetter(wheel, IosTripMeter.meter),
        weatherRepository = weather,
        dropboxRepository = dropbox,
        appNotifier = notices,
        navigationEngine = activeRoute,
        voiceCommands = NoVoiceCommands,
        phone = IosPhoneDevice,
        platform = IosDashboardPlatform(wheel, IosTripMeter.meter),
    )

    // --- Navigation, as Android's navModule builds it, with the offline
    // areas: BRouter on the downloaded tiles, their downloads and the map. ---

    /** The downloads' User-Agent, as Android's "EUCPlanet/<version>". */
    private val offlineUserAgent: String by lazy {
        "EUCPlanet/" + (platform.Foundation.NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "?")
    }

    val offlineStore: com.eried.eucplanet.nav.offline.OfflineTileStore get() = IosOfflineStorage.store

    internal val offlineJobs: IosTileJobs by lazy { IosTileJobs(offlineStore, offlineUserAgent) }

    val offlineTiles: com.eried.eucplanet.nav.offline.OfflineTiles by lazy {
        com.eried.eucplanet.nav.offline.OfflineTiles(offlineStore, settings, navHttp, offlineJobs, offlineUserAgent)
            .also { offlineJobs.tiles = it }
    }

    val offlineRouter: com.eried.eucplanet.nav.offline.OfflineRouter by lazy {
        com.eried.eucplanet.nav.offline.OfflineRouter(
            engine = bigStack(com.eried.eucplanet.nav.offline.OfflineRouter.brouterEngine(offlineStore.dir, IosBrouterProfiles(offlineStore))),
        )
    }

    val offlineNav: com.eried.eucplanet.nav.OfflineNav by lazy {
        IosOfflineNav(offlineRouter, offlineStore, IosOfflineMap.drawer)
    }

    /** The offline areas screen's view model, as Koin builds it on Android. */
    fun offlineDataViewModel(): com.eried.eucplanet.ui.settings.offline.OfflineDataViewModel =
        com.eried.eucplanet.ui.settings.offline.OfflineDataViewModel(offlineTiles, settings, currentRoute, IosOfflineDataPorts)

    val navHttp: com.eried.eucplanet.nav.NavHttp by lazy { com.eried.eucplanet.nav.PlatformNavHttp() }

    val routing: com.eried.eucplanet.nav.RoutingService by lazy {
        com.eried.eucplanet.nav.RoutingService(
            IosNavStrings, notices, settings, offlineNav, navHttp,
        )
    }

    val poi: com.eried.eucplanet.nav.PoiService by lazy { com.eried.eucplanet.nav.PoiService(navHttp) }

    val chargers: com.eried.eucplanet.nav.OcmService by lazy { com.eried.eucplanet.nav.OcmService(navHttp) }

    val currentRoute: com.eried.eucplanet.nav.CurrentRouteStore by lazy { com.eried.eucplanet.nav.CurrentRouteStore() }

    val navigation: com.eried.eucplanet.nav.NavigationEngine by lazy {
        com.eried.eucplanet.nav.NavigationEngine(
            host = IosNavigationHost,
            strings = IosNavStrings,
            wheelRepository = wheel,
            settingsRepository = settings,
            routingService = routing,
            voiceService = voice,
            currentRouteStore = currentRoute,
            legalLockdown = IosLockdown.controller,
        )
    }

    private val activeRoute: IosActiveRoute by lazy { IosActiveRoute(navigation) }

    /** What a linked HUD asks of the phone (its buttons, its version), through the shared actions. */
    val hudSink: com.eried.eucplanet.service.hud.HudCommandSink by lazy {
        com.eried.eucplanet.service.hud.HudCommandSink(wheel, navigation, settings, actions)
    }

    /**
     * The link to the HUD app on glasses or a second phone, the same HudServer
     * Android runs, on the iPhone's ports (IosHudLink.kt). IosHudLink starts
     * and stops it with its setting.
     */
    val hud: com.eried.eucplanet.service.hud.HudServer by lazy {
        com.eried.eucplanet.service.hud.HudServer(
            wheelRepository = wheel,
            settingsRepository = settings,
            externalGpsRepository = externalGps,
            tripMeterRepository = IosTripMeter.meter,
            radarRepository = radar,
            navigationEngine = navigation,
            commandSink = hudSink,
            themeController = theme,
            phone = IosHudPhone,
            sockets = IosHudSockets,
            mdns = IosHudMdns,
            udpListener = IosHudBeacons,
            network = IosHudNetwork,
            subnetProbe = com.eried.eucplanet.service.hud.HudSubnetProbe(IosHudNetwork),
            tileServer = IosHudTiles(com.eried.eucplanet.hud.protocol.HudDiscovery.TILE_PORT) { z, x, y, layer ->
                if (layer == null) IosOfflineMap.png(z, x, y, 256) else IosHudLayerTile.png(layer, z, x, y)
            },
        )
    }

    /** A place or route handed to the app from outside, for the route builder to open. */
    val incomingShares: com.eried.eucplanet.data.repository.IncomingShareRepository by lazy {
        com.eried.eucplanet.data.repository.IncomingShareRepository()
    }

    val pendingShareJoin: com.eried.eucplanet.share.PendingShareJoin by lazy {
        com.eried.eucplanet.share.PendingShareJoin()
    }

    val navMarkers: com.eried.eucplanet.data.store.NavMarkerStore by lazy {
        com.eried.eucplanet.data.store.NavMarkerStore(IosMarkerFile)
    }

    val liveShare: com.eried.eucplanet.share.LiveShare by lazy { IosShareSession(settings, wheel) }

    internal val routeBuilderPorts: IosRouteBuilderPorts by lazy { IosRouteBuilderPorts() }

    /** The route builder's view model, as Koin's viewModelOf builds it on Android. */
    fun routeBuilderViewModel(): com.eried.eucplanet.ui.navigator.RouteBuilderViewModel =
        com.eried.eucplanet.ui.navigator.RouteBuilderViewModel(
            routingService = routing,
            poiService = poi,
            ocmService = chargers,
            settingsRepository = settings,
            ports = routeBuilderPorts,
            navigationEngine = navigation,
            incomingShareRepository = incomingShares,
            currentRouteStore = currentRoute,
            navMarkerStore = navMarkers,
            shareSession = liveShare,
            pendingShareJoin = pendingShareJoin,
            wheelRepository = wheel,
            tripMeterRepository = IosTripMeter.meter,
            strings = IosNavStrings,
        )

    /** The dashboard's cue overlay's view model. */
    fun navigationOverlayViewModel(): com.eried.eucplanet.ui.navigator.NavigationOverlayViewModel =
        com.eried.eucplanet.ui.navigator.NavigationOverlayViewModel(
            navigationEngine = navigation,
            settingsRepository = settings,
            host = IosNavigationHost,
            routingService = routing,
            currentRouteStore = currentRoute,
        )

    /** The charging monitor's view model, as Koin's viewModelOf builds it on Android. */
    fun chargingViewModel(): ChargingMonitorViewModel = ChargingMonitorViewModel(
        wheelRepository = wheel,
        settingsRepository = settings,
        csvFiles = IosChargingCsvFiles,
        phone = IosPhoneDevice,
        appNotifier = notices,
    )

    /** The alarm editor's view model, as Koin builds it on Android. */
    fun alarmViewModel(): AlarmViewModel = AlarmViewModel(
        phone = IosPhoneDevice,
        alarmDao = database.alarmDao(),
        tonePlayer = IosAlarmOutputs,
        settingsRepository = settings,
        bleConnectionManager = link,
    )

    /** The settings view model, as Koin's viewModelOf builds it on Android. */
    fun settingsViewModel(): SettingsViewModel = SettingsViewModel(
        settingsRepository = settings,
        wheelRepository = wheel,
        accelSplitRepository = IosSplitTimes(splits),
        legalLockdown = com.eried.eucplanet.ui.settings.ControllerLockdownSwitch(IosLockdown.controller),
        voiceService = IosSettingsVoice(voice),
        tonePlayer = IosPromptTones,
        tripRepository = IosTrips,
        syncManager = IosSettingsSync,
        automationManager = automation,
        watches = NoWatches,
        engineSoundEngine = IosEngineSoundPreview(engineSound),
        cheatState = cheats,
        hud = com.eried.eucplanet.ui.settings.SinkHudSettingsLink(hudSink, overlayPresets),
        themeController = theme,
        eucStatsRepository = IosOnlineLeaderboard,
        dropboxRepository = dropbox,
        platform = IosSettingsPlatform,
        hornPlayer = horn,
    )

    /** The radar section's view model, as Koin's viewModelOf builds it on Android. */
    fun radarViewModel(): RadarViewModel = RadarViewModel(radar, radarScan, settings)

    /** The dashboard radar lane's view model. */
    fun radarOverlayViewModel(): RadarOverlayViewModel = RadarOverlayViewModel(settings, radar)

    /** The GPS and sensors section's view model, as Koin's viewModelOf builds it on Android. */
    fun externalGpsViewModel(): ExternalGpsViewModel = ExternalGpsViewModel(externalGps, externalGpsScan, settings)

    /**
     * The dashboard's Data sources sheet: the phone's CoreLocation fix and its
     * Core Motion acceleration (the studio's), the wheel and the GPS box.
     */
    fun dataSourcesViewModel(): DataSourcesViewModel = DataSourcesViewModel(
        phoneSensors = IosPhoneImu,
        phoneFix = IosLocation.fix,
        wheelRepository = wheel,
        externalGpsRepository = externalGps,
        settingsRepository = settings,
    )

    /** The TPMS section's view model. */
    fun tpmsViewModel(): TpmsViewModel = TpmsViewModel(settings, tpmsScanner, tpms)

    /** The Overlay Studio's view model, as Koin's viewModelOf builds it on Android. */
    fun overlayStudioViewModel(): com.eried.eucplanet.ui.studio.OverlayStudioViewModel =
        com.eried.eucplanet.ui.studio.OverlayStudioViewModel(
            wheelRepository = wheel,
            settingsRepository = settings,
            presetStore = overlayPresets,
            tripRepository = IosStudioTrips,
            tripMeterRepository = IosTripMeter.meter,
            phoneSensorRepository = IosPhoneImu,
            externalGpsRepository = externalGps,
            radarRepository = radar,
            navMarkerStore = navMarkers,
        )

    /** The trip list's view model, as Koin's viewModelOf builds it on Android. */
    fun recordingViewModel(): RecordingViewModel = RecordingViewModel(
        tripRepository = IosTrips,
        wheelRepository = wheel,
        settingsRepository = settings,
        online = IosRecordingOnline,
        dropboxRepository = dropbox,
        platform = IosRecordingPlatform,
    )

    /** A scan view model, as Koin's viewModelOf builds it on Android. */
    fun scanViewModel(): ScanViewModel = ScanViewModel(
        bleScanner = IosWheelScanner(link),
        settingsRepository = settings,
        bleConnectionManager = link,
        appNotifier = notices,
        phone = IosPhoneDevice,
        platform = IosScanPlatform(link),
    )

    suspend fun seed() {
        settingsStore.seedDefaultsIfAbsent()
        importLegacyAlarms()
    }
}

/**
 * Earlier iOS builds kept alarm rules in alarm_rules.json until the database
 * moved to the shared module. Move them in once, then drop the file.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun IosAppGraph.importLegacyAlarms() {
    val path = legacyAlarmsPath
    val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return
    val rules = runCatching { AlarmBackupJson.jsonToAlarms(JSONArray(text)) }.getOrDefault(emptyList())
    if (rules.isNotEmpty() && alarms.getAll().isEmpty()) alarms.replaceAll(rules)
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}
