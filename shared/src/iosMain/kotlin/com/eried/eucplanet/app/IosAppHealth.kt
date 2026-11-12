package com.eried.eucplanet.app

import com.eried.eucplanet.ble.BluetoothRadio
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.AppWarning
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.dashboard.AppHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** What the iPhone's "Needs attention" list is decided from. */
internal data class IosHealthState(
    val location: LocationAccess = LocationAccess.NOT_ASKED,
    val locationServicesOn: Boolean = true,
    /** Auto-record starts trips on motion, often with the app in the background. */
    val autoRecordOnMotion: Boolean = false,
    val radio: BluetoothRadio = BluetoothRadio.UNKNOWN,
    /** A wheel is remembered, so the app means to connect to one. */
    val hasWheel: Boolean = false,
    val notificationsDenied: Boolean = false,
    val chargeAlertsOn: Boolean = false,
    val hasTrips: Boolean = false,
    val dropboxLinked: Boolean = false,
)

/** The iPhone's own warnings, in the order the list shows them. */
internal enum class IosWarning(val id: String) {
    // The ids Android's AppHealthRepository uses where the warning is the same.
    LOCATION_SERVICES_OFF("location.services-off"),
    LOCATION_DENIED("perm.location"),
    LOCATION_NOT_ALWAYS("location.not-always"),
    BLUETOOTH_NOT_ALLOWED("bluetooth.not-allowed"),
    BLUETOOTH_OFF("bluetooth.off"),
    NOTIFICATIONS_OFF("perm.notifications"),
    NO_BACKUP("backup.folder"),
}

/**
 * Which warnings [s] raises. Android's rules where the iPhone has the same
 * thing (location, the trips with no backup), and the ones only the iPhone
 * needs: CoreBluetooth asks for its own permission, location has an "Always"
 * that background auto-record depends on, and notifications are asked for
 * only by the charge alerts, so only those make a refusal worth naming.
 */
internal fun iosWarnings(s: IosHealthState): List<IosWarning> = buildList {
    // One location warning at a time: with Location Services off the app's
    // own grant reads as refused, and the switch is the thing to fix.
    if (!s.locationServicesOn) add(IosWarning.LOCATION_SERVICES_OFF)
    else if (s.location == LocationAccess.DENIED) add(IosWarning.LOCATION_DENIED)
    else if (s.location == LocationAccess.WHILE_IN_USE && s.autoRecordOnMotion) add(IosWarning.LOCATION_NOT_ALWAYS)
    // Not allowed outranks off: switching the radio on would not help.
    if (s.radio == BluetoothRadio.NOT_ALLOWED) add(IosWarning.BLUETOOTH_NOT_ALLOWED)
    else if (s.radio == BluetoothRadio.OFF && s.hasWheel) add(IosWarning.BLUETOOTH_OFF)
    if (s.notificationsDenied && s.chargeAlertsOn) add(IosWarning.NOTIFICATIONS_OFF)
    // Android's no-backup-folder warning; Dropbox is the iPhone's backup.
    if (s.hasTrips && !s.dropboxLinked) add(IosWarning.NO_BACKUP)
}

/**
 * The "Needs attention" list on the iPhone, Android's AppHealthRepository:
 * the same upsert / dismiss list the shared dashboard reads (its own
 * warnings, like the backup conflicts and the paused automations, go in it
 * too), with the platform checks re-read at launch, on every return to the
 * front and whenever a grant, the radio or a setting they depend on changes.
 */
internal object IosAppHealth : AppHealth {
    /** "Backups and leaderboards" in the settings tab order, as on Android. */
    private const val SETTINGS_TAB_BACKUPS = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val list = MutableStateFlow<List<AppWarning>>(emptyList())
    override val warnings: StateFlow<List<AppWarning>> = list.asStateFlow()

    private val state = MutableStateFlow(IosHealthState())
    private var started = false

    override fun upsert(warning: AppWarning) {
        val current = list.value
        val idx = current.indexOfFirst { it.id == warning.id }
        // In place, so a warning keeps its slot across refreshes, as on Android.
        list.value = if (idx == -1) current + warning else current.toMutableList().also { it[idx] = warning }
    }

    override fun dismiss(id: String) {
        list.value = list.value.filterNot { it.id == id }
    }

    // The trips half of Android's no-backup warning; the Dropbox half is read here.
    override fun refreshBackupWarning(hasFolder: Boolean, hasTrips: Boolean) {
        state.value = state.value.copy(hasTrips = hasTrips)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(IosLocation.access, IosLocation.servicesOn) { a, on -> a to on }.collect { (a, on) ->
                state.value = state.value.copy(location = a, locationServicesOn = on)
            }
        }
        scope.launch {
            IosAppGraph.link.radio.collect { state.value = state.value.copy(radio = it) }
        }
        scope.launch {
            IosAppGraph.settings.settings.map(::fromSettings).distinctUntilChanged().collect { f ->
                state.value = f.applyTo(state.value)
                // A charge alert just switched on asks for notifications; read the answer.
                readNotifications()
            }
        }
        scope.launch {
            IosTrips.newestTripId.map { it != null }.distinctUntilChanged().collect {
                state.value = state.value.copy(hasTrips = it)
            }
        }
        scope.launch { state.collect(::apply) }
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> readNotifications() }
        readNotifications()
    }

    /** The settings the checks read, as one comparable value. */
    private data class FromSettings(
        val autoRecordOnMotion: Boolean, val hasWheel: Boolean,
        val chargeAlertsOn: Boolean, val dropboxLinked: Boolean,
    ) {
        fun applyTo(s: IosHealthState) = s.copy(
            autoRecordOnMotion = autoRecordOnMotion, hasWheel = hasWheel,
            chargeAlertsOn = chargeAlertsOn, dropboxLinked = dropboxLinked,
        )
    }

    private fun fromSettings(s: AppSettings) = FromSettings(
        autoRecordOnMotion = s.autoRecord && s.autoRecordStartInMotion,
        hasWheel = !s.lastDeviceAddress.isNullOrBlank(),
        chargeAlertsOn = s.chargingNotify80 || s.chargingNotifyFull || s.chargingNotifyCustom,
        dropboxLinked = s.dropboxAccessToken.isNotBlank(),
    )

    /** UNUserNotificationCenter answers off the main thread; the state is set on it. */
    private fun readNotifications() {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val denied = settings?.authorizationStatus == UNAuthorizationStatusDenied
            dispatch_async(dispatch_get_main_queue()) {
                state.value = state.value.copy(notificationsDenied = denied)
            }
        }
    }

    private fun apply(s: IosHealthState) {
        val raised = iosWarnings(s).toSet()
        for (w in IosWarning.entries) {
            if (w in raised) upsert(warningFor(w)) else dismiss(w.id)
        }
    }

    private fun warningFor(w: IosWarning): AppWarning = when (w) {
        IosWarning.LOCATION_SERVICES_OFF -> AppWarning(
            w.id, Res.string.warnings_location_services_title, Res.string.warnings_location_services_body,
            fix = ::openAppSettings,
        )
        IosWarning.LOCATION_DENIED -> AppWarning(
            w.id, Res.string.warnings_perm_location_title, Res.string.warnings_perm_location_body,
            fix = ::openAppSettings,
        )
        IosWarning.LOCATION_NOT_ALWAYS -> AppWarning(
            w.id, Res.string.warnings_location_always_title, Res.string.warnings_location_always_body,
            fix = ::askForAlways,
        )
        IosWarning.BLUETOOTH_NOT_ALLOWED -> AppWarning(
            w.id, Res.string.warnings_bluetooth_denied_title, Res.string.warnings_bluetooth_denied_body,
            fix = ::openAppSettings,
        )
        // No public way to open the Bluetooth switch: iOS's own turn-on alert
        // goes there, and Control Center is named when iOS will not show it.
        IosWarning.BLUETOOTH_OFF -> AppWarning(
            w.id, Res.string.scan_bluetooth_off_title, Res.string.warnings_bluetooth_off_body,
            fix = IosBluetoothPrompt::askToTurnOn,
        )
        IosWarning.NOTIFICATIONS_OFF -> AppWarning(
            w.id, Res.string.warnings_perm_notifications_title, Res.string.warnings_perm_notifications_alerts_body,
            fix = ::openAppSettings,
        )
        IosWarning.NO_BACKUP -> AppWarning(
            w.id, Res.string.warnings_no_backup_title, Res.string.warnings_no_backup_dropbox_body,
            settingsTab = SETTINGS_TAB_BACKUPS,
        )
    }

    /**
     * iOS offers the upgrade to Always once, as a prompt; after that only the
     * app's page in Settings can change it.
     */
    private fun askForAlways() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (!defaults.boolForKey(ASKED_ALWAYS_KEY)) {
            defaults.setBool(true, forKey = ASKED_ALWAYS_KEY)
            IosLocation.requestAlways()
        } else {
            openAppSettings()
        }
    }

    private const val ASKED_ALWAYS_KEY = "health_asked_location_always"

    private fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
    }
}
