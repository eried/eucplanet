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
import com.eried.eucplanet.di.SharedKoin
import com.eried.eucplanet.ui.dashboard.DashboardViewModel
import com.eried.eucplanet.weather.WeatherRepository
import com.eried.eucplanet.platform.JSONArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

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

    val adapter: CompositeWheelAdapter by lazy { SharedKoin.koin.get() }

    /** The hidden debug switches, shared with the wheel state as on Android. */
    val cheats: CheatState by lazy { CheatState() }

    val weather: WeatherRepository by lazy { WeatherRepository() }

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
            wheelProfileDao = MemoryWheelProfiles(),
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

    /**
     * Alarm rules, kept as the same JSON array settings.json carries until the
     * trip database (Room) moves to the shared module and iOS gets AlarmDao.
     */
    val alarms: AlarmRuleStore by lazy { JsonAlarmRuleStore("$documents/alarm_rules.json") }

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

    suspend fun seed() {
        settingsStore.seedDefaultsIfAbsent()
    }
}

private class JsonAlarmRuleStore(private val path: String) : AlarmRuleStore {
    private val lock = Mutex()

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun getAll(): List<AlarmRule> = lock.withLock {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return emptyList()
        runCatching { AlarmBackupJson.jsonToAlarms(JSONArray(text)) }.getOrDefault(emptyList())
            .mapIndexed { i, r -> r.copy(id = i + 1L) }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun replaceAll(rules: List<AlarmRule>) = lock.withLock {
        val text = AlarmBackupJson.alarmsToJson(rules).toString()
        @Suppress("CAST_NEVER_SUCCEEDS")
        (NSString.create(string = text)).writeToFile(path, true, NSUTF8StringEncoding, null)
        Unit
    }
}
