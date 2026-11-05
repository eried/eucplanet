package com.eried.eucplanet.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.cheats.CheatState
import com.eried.eucplanet.ble.IosWheelLink
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
import com.eried.eucplanet.ui.scan.ScanViewModel
import com.eried.eucplanet.ui.settings.AlarmViewModel
import com.eried.eucplanet.weather.WeatherRepository
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
    private val documents: String by lazy {
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
    val theme: ThemeController by lazy {
        ThemeController(settings, IosThemeFiles(documents)) {
            UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
    }

    val link: IosWheelLink by lazy { IosWheelLink(adapter) }

    /** The wheel state every screen reads, as on Android. See IosWheelPorts.kt. */
    val wheel: WheelRepository by lazy {
        WheelRepository(
            bleManager = link,
            wheelAdapter = adapter,
            settingsRepository = settings,
            alarmEngine = NoAlarms,
            tonePlayer = NoRouteKeepAlive,
            voiceService = LogAnnouncer,
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
            phoneSensorRepository = NoPhoneImu,
            externalGpsRepository = NoExternalGps,
            legalLockdown = NoLockdown,
            tpmsRepository = NoTyrePressure,
            accelSplitRepository = NoAccelSplits,
            tripRepositoryLazy = lazyOf(NoRecording),
            appNotifier = LogNotices,
            hornPlayer = NoPhoneHorn,
            phone = IosPhoneDevice,
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
        tripRepository = NoTrips,
        tripMeterRepository = NoTripMeter,
        voiceService = NoVoice,
        automationManager = NoAutomation,
        flicManager = LogActions,
        syncManager = NoFolderSync,
        externalGpsRepository = NoExternalGps,
        experimentalBannerState = IosBannerFlag,
        cheatState = cheats,
        appHealthRepository = MemoryAppHealth(),
        metricsReset = IosMetricsResetter(wheel),
        weatherRepository = weather,
        dropboxRepository = dropbox,
        appNotifier = LogNotices,
        navigationEngine = NoRoute,
        voiceCommands = NoVoiceCommands,
        phone = IosPhoneDevice,
        platform = IosDashboardPlatform(wheel),
    )

    /** The alarm editor's view model, as Koin builds it on Android. */
    fun alarmViewModel(): AlarmViewModel = AlarmViewModel(
        phone = IosPhoneDevice,
        alarmDao = database.alarmDao(),
        tonePlayer = IosAlarmPreview,
        settingsRepository = settings,
        bleConnectionManager = link,
    )

    /** A scan view model, as Koin's viewModelOf builds it on Android. */
    fun scanViewModel(): ScanViewModel = ScanViewModel(
        bleScanner = IosWheelScanner(link),
        settingsRepository = settings,
        bleConnectionManager = link,
        appNotifier = LogNotices,
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
