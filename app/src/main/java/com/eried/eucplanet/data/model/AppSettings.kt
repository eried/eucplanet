package com.eried.eucplanet.data.model

import com.eried.eucplanet.R

/**
 * How the phone finds the network HUD. Three modes so the rider controls
 * whether the saved [AppSettings.hudIp] is ever used:
 *  - [AUTO]  discovery only (UDP beacon / mDNS / subnet probe); the saved
 *            IP is never touched, so a stale address from another network
 *            can't capture the connection.
 *  - [FIXED] the saved IP/port only, no discovery.
 *  - [BOTH]  discovery, with the saved IP/port as a last-resort fallback hint.
 * FIXED and BOTH use the IP/port; AUTO does not. Value strings are stored, so
 * "HYBRID" from an earlier build is normalised to [BOTH] on read.
 */
object HudDiscoveryMode {
    const val AUTO = "AUTO"
    const val FIXED = "FIXED"
    const val BOTH = "BOTH"
    val VALUES = setOf(AUTO, FIXED, BOTH)
}

/**
 * The full set of rider preferences. Lives in DataStore as one JSON blob
 * ([com.eried.eucplanet.data.store.SettingsStore]) so adding a new field is
 * just a one-line data-class change, no DB migration, no risk of losing
 * rider state on upgrade. The `id` field is a no-op legacy artifact kept so
 * older code that copy()'d with `id = 1` still compiles.
 */
data class AppSettings(
    val id: Int = 1,

    // Connection
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val autoConnect: Boolean = true,

    /**
     * What the dashboard shows as the connected wheel's name: "MODEL" (the
     * detected model, falling back to the raw BLE advertised name), "BRAND"
     * (the wheel's brand) or "NONE" (no name, the bar just says "Connected").
     */
    val wheelNameDisplay: String = "MODEL",

    // What happens when the user swipes back from the main dashboard. Values:
    //   "ASK": show the exit dialog (legacy behavior, default)
    //   "BACKGROUND", silently send the activity to background, service keeps running
    //   "STOP_ALL", stop the service and finish the activity
    // Storage keys are language-independent so locale switches don't break the setting.
    val backButtonAction: String = "ASK",

    // Speed settings (sent to wheel) - the "normal" mode values
    val tiltbackSpeedKmh: Float = 50f,
    val alarmSpeedKmh: Float = 40f,

    // Legal-mode speed (applied when legal toggle is ON)
    val safetyTiltbackKmh: Float = 20f,
    val safetyAlarmKmh: Float = 18f,

    /**
     * Per-wheel speed calibration as a percentage offset (-20..+20). Applied
     * at the source where adapters publish telemetry so alarms, voice,
     * dashboard and recording all see the calibrated speed. Stored here for
     * the current session and mirrored to the connected wheel's
     * [com.eried.eucplanet.data.model.WheelProfile] so reconnecting restores
     * the rider's chosen calibration.
     */
    val speedCalibrationOffsetPct: Float = 0f,

    // Voice
    val voiceEnabled: Boolean = true,
    // Independent toggle for the periodic (every N seconds) status announcements. When false,
    // voice still works for triggered events (manual button, Flic, alarms) but the periodic
    // loop is silent. Toggled from the dashboard via long-press on the Voice action.
    val voicePeriodicEnabled: Boolean = false,
    // When the periodic status report may speak, in increasing restriction:
    // "ALWAYS" (even while disconnected), "CONNECTED" (only with a wheel
    // connected), "RIDING" (only while connected and actually moving).
    // New installs default to RIDING; existing installs migrate from the old
    // voiceOnlyWhenConnected boolean (see SettingsJson).
    val voiceAnnounceWhen: String = "RIDING",
    // Extra AND condition on the periodic report: when true, only speak while
    // audio is on an external output (headphones / Bluetooth / wired / USB), not
    // the phone speaker. Independent of voiceAnnounceWhen. On by default so a new
    // install never announces over the phone speaker.
    val voiceAnnounceRequireExternal: Boolean = true,
    val voiceIntervalSeconds: Int = 60,
    val voiceSpeechRate: Float = 1.2f,
    val voiceLocale: String = "en_US",  // locale tag for TTS voice
    // Specific TTS Voice.name within the locale (a language can expose several
    // voices). Empty = let the engine use its default voice for the locale.
    val voiceName: String = "",
    /**
     * True once the rider has explicitly picked a voice (either from the
     * voice picker, or by saying "no, keep my voice" to the language-change
     * prompt). When false, the voice auto-follows the UI language: first
     * launch picks a voice matching the detected system language, and
     * subsequent UI-language changes auto-switch the voice without showing
     * the "switch voice too?" prompt. Set back to false when the rider
     * explicitly accepts the prompt, since saying "yes, switch" signals
     * they want auto-sync going forward.
     */
    val voiceLocaleOverridden: Boolean = false,
    // Audio focus behavior while speaking: "DUCK" (lower other), "PAUSE" (pause other), "OFF" (no focus)
    val voiceAudioFocus: String = "DUCK",
    // Where to route the voice: "MEDIA" (music slider), "NOTIFICATION" (ring slider), "ALARM" (alarm slider, loudest)
    val voiceOutputChannel: String = "MEDIA",
    // Periodic and on-trigger voice report toggles, NESTED. These used to be 18
    // top-level flags, which had AppSettings' copy$default sitting right on the
    // JVM's 255-parameter-slot limit with no room for another report type. Read
    // them through the voiceReport* / triggerReport* accessors further down, and
    // add any new report toggle to [VoiceReportSettings] rather than here.
    // AppSettingsArgLimitTest guards the limit.
    val voiceReports: VoiceReportSettings = VoiceReportSettings(),
    // Home screen widget layout. Nested for the same reason, see WidgetSettings.
    val widget: WidgetSettings = WidgetSettings(),

    // Voice report item order (comma-separated: Speed,Battery,PhoneBattery,Time,Temp,PWM,Distance,Recording)
    val voiceReportOrder: String = "Speed,Battery,PhoneBattery,Time,Temp,PWM,Distance,Recording",

    // RaceBox-style acceleration split announcements. Feature-local group (not a
    // global), nested so AppSettings.copy() stays under the 255-arg dex limit.
    val accelSplit: AccelSplitSettings = AccelSplitSettings(),
    // Speed-driven media (music / podcast) pause & resume - see MediaControlSettings.
    val mediaControl: MediaControlSettings = MediaControlSettings(),
    // Live location share (navigator Share button) - see ShareSettings. Nested: one arg slot.
    val share: ShareSettings = ShareSettings(),
    // Bluetooth-signal proximity lock / unlock - see ProximityLockSettings.
    val proximityLock: ProximityLockSettings = ProximityLockSettings(),
    /** Weather / ridability module (dashboard icon + forecast flyout). Nested
     *  so the whole feature costs one constructor slot; see rule 8. */
    val weather: WeatherSettings = WeatherSettings(),
    val tpms: TpmsSettings = TpmsSettings(),
    val batteryPercent: BatteryPercentSettings = BatteryPercentSettings(),

    // Special announcements (event-driven). All silent by default; the welcome
    // wizard's first step offers a single toggle that flips this whole block on
    // for riders who want spoken alerts.
    val announceWheelLock: Boolean = false,
    val announceLights: Boolean = false,
    val announceRecording: Boolean = false,
    val announceConnection: Boolean = false,
    val announceGps: Boolean = false,
    val announceSafetyMode: Boolean = false,
    val announceWelcome: Boolean = false,
    /**
     * Whether the first-launch dashboard welcome tour has been shown. Starts
     * false; set true once the rider finishes or skips the tour, so it only
     * ever appears once.
     */
    val welcomeTutorialSeen: Boolean = false,

    // Recording
    val autoRecord: Boolean = true,
    // Motion-linked loop: wait for speed > 0 to start recording, auto-stop after idle timeout,
    // restart on next motion. When false, recording starts at connect and runs until disconnect.
    val autoRecordStartInMotion: Boolean = true,
    val autoRecordStopIdleSeconds: Int = 180,

    // Flic button 1
    val flic1Address: String? = null,
    val flic1Name: String = "Button 1",
    val flic1Click: String = "VOICE_ANNOUNCE",
    val flic1DoubleClick: String = "HORN",
    val flic1Hold: String = "LIGHT_TOGGLE",

    // Flic button 2
    val flic2Address: String? = null,
    val flic2Name: String = "Button 2",
    val flic2Click: String = "NONE",
    val flic2DoubleClick: String = "NONE",
    val flic2Hold: String = "SAFETY_ON",

    // Flic button 3
    val flic3Address: String? = null,
    val flic3Name: String = "Button 3",
    val flic3Click: String = "NONE",
    val flic3DoubleClick: String = "NONE",
    val flic3Hold: String = "NONE",

    // Flic button 4
    val flic4Address: String? = null,
    val flic4Name: String = "Button 4",
    val flic4Click: String = "NONE",
    val flic4DoubleClick: String = "NONE",
    val flic4Hold: String = "NONE",

    // Whether the Flic indicator in the dashboard top bar is rendered at all.
    // True (default) preserves the previous always-on behaviour; riders who
    // never use Flic and don't want the icon turn it off in
    // Settings -> Integration -> Flic.
    val flicShowOnDashboard: Boolean = true,

    // Auto-lights (sunset/sunrise based, uses live GPS from trip repository)
    val lights: LightsSettings = LightsSettings(),

    // Speed-based volume boost. Multiplier curve maps speed to 1×–2× of the user's baseline volume.
    // 1× = no boost (baseline), 2× = double the baseline (capped at 100% by the system).
    // 4 control points at 0/25/50/75 km/h. 0 km/h is locked at 1× (no boost at standstill).
    // Baseline starts at -1 (uninitialized) and is captured from the system music volume on first
    // tick after enable. Manual volume changes during motion rebase: baseline = manual / multiplier.

    // Only adjust the media volume while a wheel is connected (i.e. actually
    // riding). On by default so auto-volume never touches the phone's volume
    // when the app is used without a wheel.
    /** When the speed-driven automations may act: "NEVER" (no condition),
     *  "CONNECTED" (a wheel is linked) or "RIDING" (linked and moving). See
     *  [com.eried.eucplanet.service.ApplyWhen]. */
    val autoVolumeApplyWhen: String = ApplyWhenIds.NEVER,
    val autoVolumeCurve: String = "0:1.0,25:1.0,50:1.5,75:2.0",
    val autoVolumeBaselinePercent: Int = -1,

    // Session-level alarm mute. Set true to make AlarmEngine skip evaluation
    // entirely; the dashboard's MUTE_ALARMS action toggles it. Persists across
    // app restarts so a rider who muted on the trail finds it still muted on
    // the next session.
    val alarmsMuted: Boolean = false,

    // Display units
    // imperialUnits is legacy: kept only as the migration fallback for the three
    // per-unit fields below. Never read directly outside the Units.kt resolvers.
    val imperialUnits: Boolean = false,
    val unitSpeed: String = "",     // "" | "kmh" | "mph" | "ms"   ("" = not migrated)
    val unitDistance: String = "",  // "" | "km"  | "mi"  | "m"
    val unitTemp: String = "",      // "" | "C"   | "F"   | "K"

    val phoneKeepScreenOn: Boolean = false,
    /** Show the dashboard over the lock screen so the rider doesn't have to
     *  unlock when the screen turns back on. Applied via Activity.setShowWhenLocked;
     *  the device stays locked underneath (secure content is still protected),
     *  matching how nav / media / alarm apps behave. */
    val phoneShowOverLockScreen: Boolean = false,

    // Per-screen rotation (landscape). The app allows rotation at the manifest
    // level; these gate which screens actually rotate. The main dashboard
    // defaults to portrait-locked; the others default to allowing rotation.
    val rotateDashboard: Boolean = false,
    val rotateNavigator: Boolean = true,
    val rotateOtherScreens: Boolean = true,
    // The Settings screen has its own rotation entry, split out from "other
    // screens". Off by default, so Settings stays portrait unless enabled.
    val rotateSettings: Boolean = false,
    // Trip screens, split out from "other screens" so each rotates on its own.
    // The trip DETAILS screen rotates by default: its landscape layout is a
    // split with the map beside the stats and charts. The trip LIST (recorder)
    // stays portrait-locked by default. tripMapSide docks the details map on the
    // LEFT (default) or RIGHT of that landscape split.
    val rotateTripDetail: Boolean = true,
    val rotateTripList: Boolean = false,
    val tripMapSide: String = "LEFT",
    // The rider's Trip-details base map pick (LIGHT / DARK / SAT). Empty means
    // "follow the active theme's luminance" (dark theme -> dark map); a pick
    // sticks across restarts, like the Route Builder's navMapType.
    val tripMapType: String = "",
    // Trip Details customizer (per the Customize sheet on that screen). Stored
    // compactly as CSV so no schema change is needed. tripHiddenTiles lists the
    // stat-tile keys the rider hid (empty = all shown); tripChartOrder lists the
    // graph keys in display order (empty = default order). Keys not listed keep
    // their default position.
    val tripHiddenTiles: String = "",
    val tripTileOrder: String = "",
    val tripChartOrder: String = "",
    // Hidden graph keys (speed, battery, temp, voltage, current, pwm, and the
    // pinned "extra" details block). Separate from tripHiddenTiles because chart
    // keys collide with tile keys (battery, voltage exist in both). Empty = all
    // shown.
    val tripHiddenCharts: String = "",
    // Opt-in extra Trip Details graphs (smoothed variants, power, altitude).
    // These are OFF until the rider enables one, which the hidden-keys CSV above
    // cannot express: it records only what was hidden, so a key absent from it
    // shows, and a new key would appear for everyone on upgrade. This lists the
    // extra graph keys that were switched ON. Empty = none, the original six
    // charts only.
    val tripExtraCharts: String = "",
    // Same inverted store for stat tiles that ship OFF (start battery, energy,
    // consumption): lists the extra tile keys the rider switched ON, so a new
    // optional tile never appears for everyone on upgrade. Empty = none.
    val tripExtraTiles: String = "",

    // Screen geometry. Compact mode is the tiny dashboard (speedo + one
    // swipeable buttons/metrics area) used on flip cover screens; it reuses
    // the rider's normal dashboard configuration. AUTO activates it when both
    // screen dimensions are small; ALWAYS / NEVER override the detection.
    // coverCameraCutout keeps a corner of the compact layout empty where the
    // cover lenses sit over the panel (no API reports their area): OFF, LEFT
    // or RIGHT.
    val compactModeWhen: String = "AUTO",
    val coverCameraCutout: String = "OFF",
    // Main gauge style per surface, an open key so future styles (a
    // PWM-primary gauge, combined readouts) are one new key + renderer, not a
    // schema change: DIAL (classic ring) or NUMBER (plain value). Unknown
    // keys render as DIAL. Compact defaults to NUMBER for tiny-panel
    // readability; landscape keeps the dial.
    val compactSpeedoStyle: String = "NUMBER",
    val landscapeSpeedoStyle: String = "DIAL",
    // Landscape dashboard: swap the metric and button columns (left-hand
    // mounts).
    val landscapeMirrored: Boolean = false,
    // App-wide: block reverse portrait when rotation is allowed. One flag for
    // the whole app because the orientation policy is per activity window.
    val blockUpsideDown: Boolean = false,
    // App-wide: rotate from the sensor even when the system auto-rotate
    // toggle is off (riders often lock system rotation for pocket carry but
    // want the mounted app to follow the wheel mount anyway).
    val ignoreSystemRotateLock: Boolean = false,
    // Landscape navigator stops panel: DEFAULT keeps the bottom panel exactly
    // like portrait; LEFT / RIGHT dock it as an always-open sidebar.
    val navStopsSide: String = "RIGHT",

    // Volume keys (work while app is in foreground)
    val volumeKeysEnabled: Boolean = false,
    val volumeUpClick: String = "HORN",
    val volumeUpHold: String = "VOICE_ANNOUNCE",
    val volumeDownClick: String = "LIGHT_TOGGLE",
    val volumeDownHold: String = "SAFETY_TOGGLE",

    // Appearance
    // language: BCP-47 tag (e.g. "en", "es", "es-419", "no", "pt-BR"). Empty string
    // means "not set yet", MainActivity picks a default from the system locale on
    // first launch and persists the choice.
    val language: String = "",
    // themeMode: LEGACY. Was "black"|"dark"|"light"|"system". Kept only for backup
    // compatibility and the one-time migration into the custom theme system (see
    // ui/theme/ThemeMigration). New installs default to "system" so the install
    // pick applies (OS-light -> Light, OS-dark -> Pure Black); existing users keep
    // their stored value, so migrating them is invisible.
    val themeMode: String = "system",
    // accentColor: LEGACY accent palette key. Kept for backup compat + migration
    // into the active theme's `primary` token. The accent picker UI is removed.
    val accentColor: String = "default",

    // --- Custom theme system ---
    /**
     * Name of the active theme: a built-in (Light / Dark / Pure Black) or a saved
     * custom. This is the ONLY theme state that is persisted — the resolved colors
     * are re-derived from it on launch (see ui/theme/ThemeController), a built-in
     * from code or a saved `.json` from the themes folder, falling back to a preset
     * if the file is gone. The dirty flag and unsaved working drafts are in-memory
     * only and intentionally lost on app kill.
     */
    val activeThemeName: String = "",
    /** Master switch for the floating theme editor widget. Off = theme combo only. */
    val themeEditorEnabled: Boolean = false,
    // Colored danger-zone band behind the speed arc (yellow/orange/red thresholds).
    val showGaugeColorBand: Boolean = false,
    // Percentages of the full speed sweep where orange and red zones begin (yellow fills below orange).
    val gaugeOrangeThresholdPct: Int = 65,
    val gaugeRedThresholdPct: Int = 85,
    // Haptic feedback on dashboard action button taps.
    val hapticFeedback: Boolean = true,
    // "AMPS" or "WATTS", long-press the amps card to switch.
    val currentDisplayMode: String = "AMPS",

    // --- eucstats online upload ---
    // `onlineUploadEnabled` is the only eucstats setting we persist on-device.
    // The rider's store_id is read at runtime from the `eucstats_riderid.txt`
    // file in the sync folder (via SyncManager.riderStoreId), and the rest of
    // the profile (display name, flag, registered-at, public-consent flag,
    // stats) is fetched on demand from `api.getCard(storeId)`. Everything
    // about the rider that isn't local intent lives on the server.
    val onlineUploadEnabled: Boolean = false,

    // Backup folder (SAF tree URI on local storage; companion sync app handles cloud upload)
    val syncFolderUri: String? = null,
    val lastSettingsBackupAt: Long? = null,
    /** Snapshot name of the most-recent backup, null for the unnamed default. */
    val lastSettingsBackupName: String? = null,

    // External BLE GPS pairing (RaceBox today; future Draggy/VBox/etc. share this slot).
    // Three values stored: BLE MAC, advertised name (for display), and the source-family
    // enum name as a string ("RACEBOX") so we know which adapter to instantiate on connect.
    val externalGpsAddress: String? = null,
    val externalGpsName: String? = null,
    val externalGpsSource: String? = null,

    // RaceBox accelerometer axis remap. The device can be mounted in any
    // orientation; the rider's notion of "left/right", "forward/back" and
    // "up/down" may not align with the physical X / Y / Z the box reports.
    // Each entry says which raw axis (signed) becomes the corresponding
    // output axis. Allowed values: "X", "-X", "Y", "-Y", "Z", "-Z".
    // Identity (X→X, Y→Y, Z→Z) is the default and covers a wheel-pedal mount
    // with the box's logo facing up.
    val raceboxMapX: String = "X",
    val raceboxMapY: String = "Y",
    val raceboxMapZ: String = "Z",
    /**
     * When ON and an external GPS box is connected, its samples are the
     * dashboard's "extra speed" source. When OFF (or when no external box is
     * available) the phone's own GPS speed is used instead.
     */
    val gpsPrioritizeExternal: Boolean = true,
    /** Show the extra-GPS speed indicator on the dashboard speed dial. */
    val gpsShowOnDashboard: Boolean = false,

    // --- Rear-view radar (Garmin Varia today) ---
    // Same persistence shape as External GPS: BLE MAC, advertised name, vendor
    // enum name as a string so we know which adapter to instantiate on connect.
    val radarAddress: String? = null,
    val radarName: String? = null,
    val radarVendor: String? = null,
    /**
     * Show the radar threat overlay (lane bar with dots per detected vehicle)
     * on top of every screen while a radar is paired and connected. The user
     * can hide it without unpairing.
     */
    val radarShowOverlay: Boolean = true,
    /** Which screen edge the overlay lives on: "LEFT" or "RIGHT". */
    val radarOverlaySide: String = "RIGHT",

    // --- Navigator ---
    // In-app navigation: the route builder, live turn-by-turn guidance and the
    // Treasure Hunt proximity-hint mode.
    /** Speak turn-by-turn / Treasure Hunt instructions through TTS. */
    val navVoiceEnabled: Boolean = true,
    /** Radius (meters) within which a waypoint / goal counts as "reached". */
    val navArrivalRadiusM: Int = 50,
    /** Perpendicular distance (meters) off the route before the off-route alert triggers. */
    val navOffRouteToleranceM: Int = 40,
    /** Default travel mode for new routes: CYCLING / DRIVING / WALKING / STRAIGHT. */
    val navDefaultTravelMode: String = "STRAIGHT",
    /** Saved Home place as JSON {name,lat,lng}; blank when unset. */
    val navHomeJson: String = "",
    /** Saved Work place as JSON {name,lat,lng}; blank when unset. */
    val navWorkJson: String = "",
    /** Geocoder (address search) endpoint, overridable for self-hosting. */
    val navGeocoderUrl: String = "https://nominatim.openstreetmap.org/search",
    /** Routing endpoint, overridable for self-hosting. */
    val navRouterUrl: String = "https://routing.openstreetmap.de",
    /** Overpass (chargers / stations POI source) endpoint, overridable for self-hosting. */
    val navOverpassUrl: String = "https://overpass-api.de/api/interpreter",
    /**
     * Open Charge Map API key (free, from openchargemap.org). Blank by default —
     * when set, the charger flyout enriches with OCM community data (rating,
     * comments, connectors, photos). Only used in advanced map mode for chargers.
     */
    val navOcmApiKey: String = "",
    // Two nav things are intentionally NOT settings, so they never bloat the
    // settings JSON / backup:
    //  - the current navigation route -> in memory only
    //    (com.eried.eucplanet.nav.CurrentRouteStore); a reinstall starts at zero.
    //  - the custom user-marker photo -> its own PNG file in noBackupFilesDir
    //    (com.eried.eucplanet.data.store.NavMarkerStore); survives app updates but
    //    not a full uninstall / new device (never recovered).
    /** Route Builder map style: DARK / LIGHT / SATELLITE. */
    /** Route Builder base layer. Plain OSM, which draws paths and tracks;
     *  the Carto styles mute them by design, so trails read as missing. */
    val navMapType: String = "OSM",
    /**
     * When true (the default) the route builder solves the WHOLE multi-stop
     * tour in one routing request -- a single solid line, a whole-tour distance
     * readout, and the complete route handed to live navigation. When false
     * ("Next segment") only the next leg (origin -> first non-passed stop) is
     * routed and the remaining stops are drawn as a dashed straight-line
     * preview, which is lighter on the router and on a flaky connection.
     * Has no routing effect in STRAIGHT/Direct mode (which never calls the
     * router); there it only flips the remaining legs between solid and dashed.
     */
    val navSolveFullPath: Boolean = true,
    /**
     * Advanced map features (off by default). When off the route builder shows
     * just the route and stops, and the routing-service URL fields are disabled.
     * Turn it on to unlock the on-map charger and places layers and the custom
     * source endpoints.
     */
    val navAdvancedMap: Boolean = false,
    /** On-map ⚡ charger layer enabled (electric charging only). Ignored unless advanced map is on. */
    val navShowChargers: Boolean = false,
    /**
     * Enabled "places" categories as a CSV of PoiKind names (STORE, FOOD, REST,
     * SIGHTS). Empty = the places layer is off. The places FAB toggles the whole
     * group; long-press picks individual categories.
     */
    val navPlaceCategories: String = "",
    /** True once the "hold for place categories" hint toast has been shown. */
    val navPlacesHintShown: Boolean = false,
    // Route avoidances. All default false -> avoid nothing, identical to the
    // historic behaviour. When any is true the route is solved by the key-less
    // FOSSGIS Valhalla backend (the default OSRM service can't honour
    // avoidances); see com.eried.eucplanet.nav.RoutingService. Which flags
    // actually bite depends on the travel mode's Valhalla costing
    // (highways/tolls only apply to DRIVING; ferries to all; unpaved to
    // CYCLING) -- a flag with no effect in the current mode is simply ignored.
    /** Avoid motorways / highways (DRIVING). */
    val navAvoidHighways: Boolean = false,
    /** Avoid toll roads (DRIVING). */
    val navAvoidTolls: Boolean = false,
    /** Avoid ferries (all modes). */
    val navAvoidFerries: Boolean = false,
    /** Prefer paved roads, avoid unpaved / bad surfaces (CYCLING). */
    val navAvoidUnpaved: Boolean = false,

    // --- Wear OS companion (only takes effect when a Wear OS watch is paired) ---
    val watchKeepScreenOn: Boolean = true,
    val watchAutoStart: Boolean = true,
    /**
     * When the user picks "Stop all" from the phone exit dialog, also close
     * the watch companion app so its dial doesn't sit on a stale frame after
     * the phone tears the session down. On by default since the watch app
     * has no value without the phone feeding it telemetry.
     */
    val watchCloseOnExit: Boolean = true,
    val watchShowWheelBattery: Boolean = true,
    val watchShowPhoneBattery: Boolean = true,
    val watchShowWatchBattery: Boolean = true,
    /** "BAR", "NUMBERS", or "BOTH". */
    val watchPwmDisplay: String = "BOTH",
    val watchShowSpeedUnit: Boolean = true,
    val watchEnableGpsSpeed: Boolean = false,
    /**
     * When true the watch dial inverts the size hierarchy on its first screen:
     * the PWM bar + number become the focal element, the speed reading shrinks.
     * Useful when the rider cares more about cutout headroom than current speed.
     */
    val watchPrioritizePwm: Boolean = false,
    /**
     * Virtual rotation applied to the watch's first screen only, in degrees
     * (–90..+90, step 5). Lets the rider tilt the dial so it reads naturally with
     * their wrist orientation when the wheel is in motion. Doesn't affect the
     * other watch screens or any phone UI.
     */
    val watchDialRotationDeg: Int = 0,

    /**
     * Hardware-button bindings on the watch (Galaxy Watch Ultra exposes the
     * orange Action button as STEM_1 and the bottom side button as STEM_2;
     * Pixel Watch only has one). Stored as the [FlicAction] enum name so the
     * picker can reuse the same UI/string set as Flic and Volume keys. The
     * Wear OS side reads these via the Data Layer publish, intercepts
     * KEYCODE_STEM_* in MainActivity, and either fires a local control
     * intent or routes to the phone over /euc/control.
     */
    val watchStem1Click: String = "NONE",
    val watchStem1Hold: String = "NONE",
    val watchStem2Click: String = "NONE",
    // Third hardware button. Garmin only (the Down key); Wear watches have
    // two stems, so the wear bridge never sends it. Click only: the watch
    // system can claim Down's long press for its own shortcut.
    val watchStem3Click: String = "NONE",
    val watchStem2Hold: String = "NONE",

    /**
     * On-screen watch button bindings. Two configurable buttons; tap fires the
     * "click" action, long-press fires the "hold" action. Same FlicAction
     * vocabulary as Flic / Volume / Stem buttons. Defaults match the wheel's
     * most-used controls (Horn, Light) so out-of-the-box behavior matches
     * the previous hardcoded buttons.
     */
    val watchScreen1Click: String = "HORN",
    val watchScreen1Hold: String = "NONE",
    val watchScreen2Click: String = "LIGHT_TOGGLE",
    val watchScreen2Hold: String = "NONE",

    /**
     * If true, the watch vibrates briefly whenever a button-bound action
     * fires (tap or hold) so the user gets tactile confirmation.
     */
    val watchHapticOnAction: Boolean = true,

    /**
     * Live-data update rate for the dashboard and watch. Drives the realtime
     * poll-and-push loop interval: "CONSERVATIVE" (750 ms, easiest on phone /
     * watch battery), "NORMAL" (250 ms, the default) or "FAST" (150 ms, most
     * responsive). Stored as a stable key so the millisecond mapping can be
     * retuned later without a settings migration.
     */
    val watchUpdateRate: String = "NORMAL",
    /**
     * Advanced power-user timing / threshold settings, grouped into a nested
     * object on purpose. As 46 more top-level fields, AppSettings' generated
     * copy()/copy$default blew past the JVM/dex 255-argument limit, so the app
     * crashed at class verification (VerifyError on any .copy() caller, e.g.
     * FlicManager). The delegating getters in the class body keep
     * `settings.wheelPollIntervalMs` etc. working unchanged everywhere.
     */
    val advanced: AdvancedSettings = AdvancedSettings(),
    /** Settings-screen layout the rider arranged: section display order and which
     *  sections are tucked into the "More" bucket. See [SettingsLayout]. */
    val settingsLayout: SettingsLayout = SettingsLayout(),
    /**
     * Mirror the live navigation popup (turn arrow + distance) on the paired
     * watch. On by default; the rider can turn it off to keep the watch dial
     * as the only glance surface.
     */
    val watchShowNavigation: Boolean = true,

    // --- HUD companion (paired by typing the HUD IP, see HudServer) ---
    /**
     * Master switch for the phone-side WebSocket dialer that pushes telemetry
     * to an external HUD (e.g. an aftermarket E6-class motorcycle HUD). Off
     * by default. The HUD itself is a separate APK (`:hud` module) and acts
     * as the listener; we connect out to it because phone hotspots routinely
     * block multicast and inbound peer traffic.
     *
     * Storage key kept as `hudServerEnabled` for backwards compat with
     * existing rider settings -- the meaning is "HUD link active", role was
     * inverted in v0.1.4.
     *
     * Default: false in release, true in debug builds. Debug-only opt-in
     * by default means a fresh sideload-for-testing install dials the HUD
     * immediately without the rider having to find the toggle in Settings —
     * which is exactly the flow the dev loop runs every reinstall. Release
     * users still see it disabled so a HUDless rider doesn't burn battery
     * on a dial loop they'll never use.
     */
    /**
     * Link master switch. Always OFF by default -- the rider has to opt
     * in by flipping it on. Used to default to BuildConfig.DEBUG so debug
     * builds came pre-armed, but that hid a real-world quirk (the rider
     * never saw the toggle) and conflated "is this a debug APK?" with
     * "should the radio be running?". The two should be independent.
     */
    val hudServerEnabled: Boolean = false,
    /** Keep the foreground service (ongoing notification) alive even with no wheel
     *  connected, so background trip sync and voice keep running. Default on. */
    val keepAppAlive: Boolean = true,
    /** Show quick-action buttons on the ongoing notification. */
    val notificationActionsEnabled: Boolean = true,
    /** Which actions (comma-separated keys, max 3) appear on the notification.
     *  See [NotificationActionType]. Default: Stop all, Lock/Unlock, Stop nav. */
    val notificationActions: String = "STOP_ALL,LOCK,STOP_NAV",
    /**
     * HUD joystick long-press bindings. The HUD's IR remote / joystick fires a
     * long-press in one of four directions; the HUD sends an
     * [com.eried.eucplanet.hud.protocol.HudCommand.Action] with the slot name and
     * the PHONE decides what to do, so the action vocabulary matches Flic / Volume
     * keys / Wear. Stored as an ActionCatalog key (e.g. "HORN", "VOICE_ANNOUNCE")
     * or "NONE" for unbound. All default to "NONE" so the joystick keeps its
     * existing short-press carousel behaviour until the rider binds something.
     */
    val hudActionUp: String = "NONE",
    val hudActionDown: String = "NONE",
    val hudActionLeft: String = "NONE",
    val hudActionRight: String = "NONE",
    /**
     * TCP port to dial on the HUD. Default mirrors `HudDiscovery.DEFAULT_PORT`.
     * Exposed as a setting because some carrier-grade hotspots refuse to
     * route certain port ranges; riders rarely need to touch it.
     */
    val hudServerPort: Int = 28080,
    /**
     * IPv4 of the HUD the rider reads off its on-screen banner and types into
     * the phone settings. Blank means "no HUD configured"; we won't try to
     * dial out until the rider fills this in. mDNS auto-discovery may
     * populate this in a future build, but right now manual entry is the
     * only path because softAP multicast filtering kills discovery on too
     * many phones.
     */
    val hudIp: String = "",
    /**
     * How the phone finds the HUD (see [HudDiscoveryMode]). AUTO (default)
     * races UDP beacon, mDNS browse and a subnet probe of its own /24 and
     * never touches [hudIp] - the settings row that holds it is hidden, so
     * nothing is dialled that the rider cannot see. BOTH adds the saved
     * [hudIp] as a fallback hint in that race. FIXED uses only [hudIp] - the
     * escape hatch for when every auto path is broken. The winning channel is
     * published on the HUD-settings status line so the rider sees how it linked.
     */
    val hudDiscoveryMode: String = HudDiscoveryMode.AUTO,
    /**
     * Name of the Overlay Studio preset the rider chose to mirror on the
     * HUD as a "Custom" screen. Empty = no custom overlay configured.
     * Resolved against bundled assets + the rider's backup folder by the
     * OverlayPresetStore; the resolved JSON travels over the wire via
     * [hudCustomOverlayJson] so the HUD doesn't need filesystem access.
     */
    val hudCustomOverlayName: String = "",
    /**
     * Cached JSON of the resolved custom overlay preset. Updated whenever
     * [hudCustomOverlayName] changes; the HUD reads this directly and
     * renders the elements (no viewport backgrounds -- this is meant to
     * overlay on the HUD's transparent panel like a video stream's
     * lower-third).
     */
    val hudCustomOverlayJson: String = "",
    /**
     * Phone HUD: draw an Overlay Studio preset in a window on top of whatever
     * else is on screen, so the rider can see telemetry over maps or music.
     *
     * Same idea as the HUD above, different destination: that one ships the
     * preset to a companion screen, this one draws it here. Needs the system
     * "Display over other apps" permission, which cannot be granted silently,
     * so this flag only means the rider asked for it - the window still checks
     * Settings.canDrawOverlays every time it goes up.
     */
    val phoneHudEnabled: Boolean = false,
    /** Preset the Phone HUD draws. Empty = none chosen, so nothing is shown. */
    val phoneHudOverlayName: String = "",
    /** Resolved JSON for [phoneHudOverlayName], same caching as the HUD's. */
    val phoneHudOverlayJson: String = "",
    /**
     * Hide the Phone HUD while EUC Planet itself is in front.
     *
     * On by default. The point of the overlay is seeing the wheel while in
     * some OTHER app; drawn over the app's own dashboard it covers a better
     * version of itself. A rider who wants it everywhere can turn this off.
     */
    val phoneHudOnlyWhenAway: Boolean = true,
    /**
     * Picture-in-picture: what to shrink into the system's floating window
     * when the rider leaves the app mid-ride.
     *
     * "OFF" (default), "SIMPLE" for four big readings, or "DASHBOARD" for the
     * gauge beside the rider's own metrics. One setting rather than a switch
     * plus a mode, because "off" is just the third choice.
     *
     * Unlike the Phone HUD this needs no permission and the system owns the
     * window, so it can be dragged and flicked away like a video PIP. It only
     * exists while the activity does, which is the difference between the two:
     * PIP is "I just left the app", the overlay is "the app is not running".
     */
    val pipMode: String = "OFF",
    /**
     * Ordered list of HUD screens the rider has enabled, by stable id
     * ("Dashboard", "Camera", "Telemetry", "Custom", "CustomCam",
     * "Map", "Nav"). Stored as a comma-separated string so it slots
     * cleanly into the existing key/value DataStore.
     *
     * Empty string = "use the default carousel" (= all seven screens in
     * declaration order). Non-empty = each comma-separated id is one
     * screen and the order is the carousel order.
     *
     * The phone-side UI enforces a minimum of one screen so the rider
     * can't disable everything and lose access to the HUD; the HUD
     * also falls back to the default seven on an empty-list wire frame
     * as belt-and-suspenders.
     */
    val hudScreensEnabled: String = "",
    /**
     * Rider's preferred FULL display order of all known HUD screens,
     * comma-separated. Used to keep disabled screens in their current
     * row when the rider toggles a Switch off in the Personalize list:
     * the row's enabled state changes, the row's POSITION doesn't.
     *
     * Empty string = default order (the defaults followed by the opt-in
     * screens in declaration order). When set, contains every known
     * screen id in the order the rider arranged them. Any future-added
     * screens not in the saved value are appended at the end.
     *
     * The wire-format `enabledHudScreens` field is computed by walking
     * THIS order and filtering by the enabled set, so the HUD's
     * carousel order matches the order the rider sees in Settings.
     */
    val hudScreensOrder: String = "",
    /**
     * Which raster map style the HUD should use for its Map screen
     * and the MAP element inside a Custom overlay. Empty = the HUD picks
     * its compiled-in default (a light basemap). Supported codes: "osm",
     * "cyclosm", "topo", "hot", "satellite", "light", "dark"; legacy Carto
     * slugs riders have saved ("voyager", "dark_all", ...) still resolve
     * to the matching Esri style, and anything else falls back to light so
     * the rider never gets a blank map.
     */
    val hudMapStyle: String = "",
    /**
     * Per-axis tile post-processing. Both run as a single ColorMatrix on
     * the HUD; at the neutral values (contrast=100, brightness=0) the
     * matrix is identity and we skip the ColorFilter entirely so there's
     * no GPU cost for the common case.
     *
     * Contrast: 50..200 percent, 100 = neutral (no gain).
     * Brightness: -100..100, 0 = neutral. Negative darkens, positive
     * lightens. Applied on a 0..255 channel scale -- -100 means subtract
     * 100 from each channel before clamping.
     */
    val hudMapContrastPct: Int = 100,
    val hudMapBrightnessPct: Int = 0,

    // --- Motor Sound generator ---
    //
    // Synthesises a virtual engine driven by live (speed, pwm) telemetry. Goes
    // through the media stream so it mixes with music; the user controls how it
    // behaves under voice announces via [engineDuckOnVoice].
    val engineSoundEnabled: Boolean = false,
    /** Preset key. See [com.eried.eucplanet.audio.EngineProfile.PROFILES]. */
    val engineType: String = "FOUR_STROKE_SINGLE",
    /** In-app gain 0..1 over the media stream. */
    val engineVolume: Float = 0.6f,
    /**
     * Legacy. Was a paired "fixed volume" toggle (with [engineVolume] as the slider) that
     * could disable the speed curve. The current UI always uses the curve so this field
     * is unused, kept only for backup/sync compatibility with v0.5.x exports.
     */
    val engineVolumeAutoEnabled: Boolean = false,
    /**
     * Encoded 4-point curve at 0/25/50/75 km/h, values in 0..1. The curve IS the engine
     * volume, there's no separate fixed-volume slider any more. Format matches
     * [com.eried.eucplanet.service.parseVolumeCurve]: "speed:mult,..."
     * Default: full volume parked for pedestrian awareness, drop to 10% by cruise speed,
     * silent at top.
     */
    val engineVolumeAutoCurve: String = "0:1.00,25:0.10,50:0.10,75:0.00",
    /** "OPEN", "HALF", "MUFFLED", controls high-harmonic rolloff. */
    val engineMuffler: String = "HALF",
    /** "OFF", "FOUR", "SIX". Ignored for engines whose profile is gearless (synth/futuristic). */
    val engineGearbox: String = "FOUR",
    /** "ALWAYS" (always idling when connected), "FADE" (fade after parked), "MOVING" (only when moving). */
    val engineIdleBehavior: String = "FADE",
    /** "SMOOTH" (no pops), "STANDARD", "BACKFIRE" (heavy pops on decel). */
    val engineDecelChar: String = "STANDARD",
    /** "OFF", "LIGHT", "STRONG", engine-brake whine layered during sustained decel/regen. */
    val engineBrake: String = "LIGHT",
    /** When a voice announce plays: "DUCK" (-12 dB), "PAUSE" (engine silent during speech), "MIX" (no ducking). */
    val engineDuckOnVoice: String = "DUCK",
    /** If true, engine only plays when wired/BT audio is routed to headphones (safety). */
    val engineHeadphonesOnly: Boolean = false,

    // --- Overlay Studio replay export ---
    // Output format for the Replay-mode photo / video export. Stored as stable
    // keys so the format set can change without a settings migration.
    /** Replay photo export format: "PNG" (alpha, default), "WEBP" (alpha, fast),
     *  "JPG" (chroma-filled) or "GIF" (1-bit alpha). */
    val studioReplayPhotoFormat: String = "PNG",
    /** Replay video export format: "MP4" (chroma-filled, default), "APNG"
     *  (alpha) or "GIF" (1-bit alpha). The ffmpeg "MOV" (ProRes 4444) path is
     *  disabled app-wide, so it is no longer a valid value. */
    val studioReplayVideoFormat: String = "MP4",
    /**
     * ARGB chroma-key fill colour used when an alpha-less export format (JPG,
     * MP4) is chosen. Default bright green (0xFF00FF00).
     */
    val studioReplayChromaColor: Long = 0xFF00FF00L,
    /**
     * When exporting an alpha-less format (JPG, MP4), force every overlay
     * element to 100% opacity so half-transparent elements don't blend with
     * the chroma fill and look wrong. Default on.
     */
    val studioReplayForceOpaque: Boolean = true,
    // MOV alpha codec (studioReplayMovQtrle) removed: the ffmpeg ProRes/.mov
    // export path is disabled app-wide. Any leftover value in a persisted JSON
    // blob is simply ignored by Gson.

    // Dashboard layout (customizable home screen).
    //
    // Metric / action order is a comma-separated list of enum-name keys; unknown
    // tokens are dropped and known-but-missing tokens are appended in canonical
    // order when read, so adding a new metric or action later just expands the
    // default list without breaking existing rider settings.
    val dashboardMetricsColumns: Int = 2,
    val dashboardActionsColumns: Int = 3,
    val dashboardMetricOrder: String = "BATTERY,TEMPERATURE,VOLTAGE,CURRENT,LOAD,TRIP",
    val dashboardActionOrder: String = "HORN,LIGHT_TOGGLE,VOICE_ANNOUNCE,SAFETY_TOGGLE,LOCK_TOGGLE,RECORD_TOGGLE",
    val dashboardRollingWindowSeconds: Int = 300,

    /**
     * Composite metric definitions as a JSON object keyed by synthetic ID
     * (`M:<uuid>`). Each value is `{ "layout": "ROW2"|"COL2"|"COL3", "cells":
     * [<metric_key>, ...] }`. Composite IDs appear in [dashboardMetricOrder]
     * alongside regular metric keys — a single grid slot renders the composite
     * as a multi-cell tile instead of one metric. Empty object `"{}"` means
     * the rider hasn't dragged the `+ Stack` template onto the grid yet.
     */
    val dashboardCompositeMetrics: String = "{}",

    /**
     * Action group definitions as a JSON object keyed by `G:<uuid>`. Each
     * value is `{ "actions": [<action_key>, ...] }` with up to 4 entries.
     * Group IDs appear in [dashboardActionOrder]; a single action slot
     * renders the group as one button whose tap opens an anchored popover
     * with the sub-actions.
     */
    val dashboardActionGroups: String = "{}",

    /**
     * Custom tile definitions as a JSON object keyed by `C:<uuid>`. Each value
     * is `{ "text": <label>, "icon": <icon_key>, "action": <type>, "url": <url> }`.
     * Action types: NONE (display-only label), OPEN_URL (tap opens default
     * browser), SHOW_QR (tap shows a QR-code popup so other riders can scan
     * and visit the URL — e.g. Instagram handle, club page). Custom tile IDs
     * appear in [dashboardMetricOrder] alongside regular metrics.
     */
    val dashboardCustomTiles: String = "{}",

    /**
     * Custom BLE action definitions as a JSON object keyed by `B:<uuid>`. Each
     * value is `{ "label": <text>, "icon": <icon_key>, "family": <familyId>,
     * "frames": [<hex>, ...] }`. Frames are written verbatim (one BLE write each,
     * in order) to the connected wheel, but only when its family matches; the id
     * appears in [dashboardActionOrder] like a built-in action key. Opt-in for
     * advanced users — empty object until a rider drags the CUSTOM BLE template.
     * See [com.eried.eucplanet.data.model.CustomBleCommand].
     */
    val dashboardCustomBle: String = "{}",

    /**
     * Per-metric corner-stat configuration as a JSON object. Each known metric
     * key maps to a config object with five stat slots (center, top-left,
     * top-right, bottom-left, bottom-right) and a sparkline flag. Defaults are
     * applied at read time when an entry is missing — empty object means every
     * metric uses center=CURRENT, others=NONE, sparkline=true. Persisted as a
     * single string so we don't need to grow AppSettings each time a new stat
     * lands.
     *
     * Schema:
     * `{"BATTERY":{"c":"CURRENT","tl":"MIN","tr":"MAX","bl":"NONE","br":"AVG","spark":true}}`
     *
     * Stat values: NONE | CURRENT | MIN | MAX | AVG.
     */
    val dashboardMetricStats: String = "{}",

    /** Battery screen: estimate straight to 100 % instead of stopping at 80 %. */
    val chargingEstimateToFull: Boolean = false,
    /** Tell the rider when the pack passes 80%, the mark riders unplug at for
     *  pack life, and when it finishes. Both off: a notification nobody asked
     *  for is worse than no feature. Local to the charging monitor rather than
     *  Advanced settings, like [chargingEstimateToFull] beside them. */
    val chargingNotify80: Boolean = false,
    val chargingNotifyFull: Boolean = false,
    /** Auto-open the Battery monitor when the wheel starts charging. */
    val chargingAutoOpen: Boolean = true,
    /** Show the Battery monitor access icon (spark) in the dashboard top bar. */
    val chargingDashboardIcon: Boolean = true,

    // --- Dropbox online backup (Phase 1: link state only) ---------------
    /** Long-lived Dropbox short-lived access token (4h TTL on Dropbox). */
    val dropboxAccessToken: String = "",
    /** Refresh token kept across launches; used to mint new access tokens. */
    val dropboxRefreshToken: String = "",
    /** Wall-clock ms at which [dropboxAccessToken] expires; 0 = unknown. */
    val dropboxAccessTokenExpiresAt: Long = 0L,
    /** Dropbox account display string (e.g. email) shown in Settings while
     *  linked. Cleared on unlink. Purely cosmetic. */
    val dropboxAccountLabel: String = "",
    /** Wall-clock ms of the last successful Dropbox sync. Used by the
     *  Sync all UI to label "Last synced 5 min ago" and by the worker to
     *  decide whether the settings.json on Dropbox is current. */
    val dropboxLastSyncAt: Long = 0L,
    /** True while trips still need uploading to Dropbox; the worker keeps
     *  retrying until it clears. Drives the persistent "Syncing trips…"
     *  indicator so failed / pending uploads are surfaced without an error toast. */
    val dropboxSyncPending: Boolean = false,
    /** Number of trips still to upload to Dropbox; drives the "Syncing N trips…"
     *  indicator, decrementing live as each upload lands. */
    val dropboxPendingCount: Int = 0,
    // Trips whose file exists on both the phone and the backup folder with
    // different content. The folder worker counts them each pass; the
    // dashboard shows the warning while it is non-zero.
    val folderConflictCount: Int = 0,
    /** Trips in the current sync batch, so the pending indicator can show
     *  "X of Y" like the foreground sync (done = total - pending). 0 = no batch. */
    val dropboxSyncTotal: Int = 0,
    /**
     * The rider asked for their Dropbox trips to come down, and some are still
     * missing.
     *
     * Downloading is never something the app decides on its own: it is a lot of
     * data and a lot of battery, and a rider who links Dropbox to back trips
     * *up* should not find a library arriving unasked. So the background worker
     * only pulls while this is set - by pressing Sync all, or by linking, which
     * is a rider saying "bring my trips over".
     *
     * It survives the app being killed, which is the point: a big library takes
     * the better part of an hour, and the phone goes in a pocket long before
     * that. Cleared when nothing is left to fetch, or the moment the rider
     * cancels.
     */
    val dropboxPullRequested: Boolean = false
) {
    // Delegating getters so reads like `settings.wheelPollIntervalMs` keep working
    // after the 46 advanced fields moved into the nested [AdvancedSettings] (which
    // keeps AppSettings' copy() under the JVM/dex 255-argument limit). Writes use
    // copy(advanced = advanced.copy(...)).
    // Voice report toggles, read exactly as before the move into [voiceReports],
    // so every read site stayed untouched. Writes go through the nested copy.
    val voiceReportSpeed: Boolean get() = voiceReports.periodicSpeed
    val voiceReportBattery: Boolean get() = voiceReports.periodicBattery
    val voiceReportTemp: Boolean get() = voiceReports.periodicTemp
    val voiceReportPwm: Boolean get() = voiceReports.periodicPwm
    val voiceReportDistance: Boolean get() = voiceReports.periodicDistance
    val voiceReportTime: Boolean get() = voiceReports.periodicTime
    val voiceReportNavigation: Boolean get() = voiceReports.periodicNavigation
    val voiceReportPhoneBattery: Boolean get() = voiceReports.periodicPhoneBattery
    val voiceReportRecording: Boolean get() = voiceReports.periodicRecording
    val triggerReportSpeed: Boolean get() = voiceReports.triggerSpeed
    val triggerReportBattery: Boolean get() = voiceReports.triggerBattery
    val triggerReportTemp: Boolean get() = voiceReports.triggerTemp
    val triggerReportPwm: Boolean get() = voiceReports.triggerPwm
    val triggerReportDistance: Boolean get() = voiceReports.triggerDistance
    val triggerReportTime: Boolean get() = voiceReports.triggerTime
    val triggerReportNavigation: Boolean get() = voiceReports.triggerNavigation
    val triggerReportPhoneBattery: Boolean get() = voiceReports.triggerPhoneBattery
    val triggerReportRecording: Boolean get() = voiceReports.triggerRecording

    val wheelPollIntervalMs: Int get() = advanced.wheelPollIntervalMs
    val graphSampleIntervalMs: Int get() = advanced.graphSampleIntervalMs
    val smoothingWindowSamples: Int get() = advanced.smoothingWindowSamples
    val tripRecordIntervalMs: Int get() = advanced.tripRecordIntervalMs
    val pendingUploadIntervalMin: Int get() = advanced.pendingUploadIntervalMin
    val tripFinalizeGraceMs: Int get() = advanced.tripFinalizeGraceMs
    val lockMaxSpeedKmh: Int get() = advanced.lockMaxSpeedKmh
    val phoneGpsIntervalMs: Int get() = advanced.phoneGpsIntervalMs
    val phoneGpsIdleIntervalMs: Int get() = advanced.phoneGpsIdleIntervalMs
    val gpsIdleOffDelaySec: Int get() = advanced.gpsIdleOffDelaySec
    val gpsFixMaxAgeSec: Int get() = advanced.gpsFixMaxAgeSec
    val hudReportIntervalMs: Int get() = advanced.hudReportIntervalMs
    val garminReportIntervalMs: Int get() = advanced.garminReportIntervalMs
    val navOffRouteGraceMs: Int get() = advanced.navOffRouteGraceMs
    val navOffRouteVoiceAfterMs: Int get() = advanced.navOffRouteVoiceAfterMs
    val navOffRouteVoiceCooldownMs: Int get() = advanced.navOffRouteVoiceCooldownMs
    val navRerouteAfterMs: Int get() = advanced.navRerouteAfterMs
    val navArrivalDismissMs: Int get() = advanced.navArrivalDismissMs
    val navHuntVoiceIntervalMs: Int get() = advanced.navHuntVoiceIntervalMs
    val navHeadingWindowMs: Int get() = advanced.navHeadingWindowMs
    val navFixBufferMs: Int get() = advanced.navFixBufferMs
    val navIntermediateFlashMs: Int get() = advanced.navIntermediateFlashMs
    val navPopupTimeoutMs: Int get() = advanced.navPopupTimeoutMs
    val alarmSlopeWindowMs: Int get() = advanced.alarmSlopeWindowMs
    val alarmBufferMaxMs: Int get() = advanced.alarmBufferMaxMs
    val alarmSlopeMinSamples: Int get() = advanced.alarmSlopeMinSamples
    val alarmSlopeMinSpanMs: Int get() = advanced.alarmSlopeMinSpanMs
    val radarClearDecayMs: Int get() = advanced.radarClearDecayMs
    val automationLightCheckIntervalMs: Int get() = advanced.automationLightCheckIntervalMs
    val hudBackoffMinMs: Int get() = advanced.hudBackoffMinMs
    val hudBackoffMaxMs: Int get() = advanced.hudBackoffMaxMs
    val hudMdnsTimeoutMs: Int get() = advanced.hudMdnsTimeoutMs
    val hudDiscoverySprintMs: Int get() = advanced.hudDiscoverySprintMs
    val hudUdpProbeTimeoutMs: Int get() = advanced.hudUdpProbeTimeoutMs
    val hudUdpBeaconFreshnessMs: Int get() = advanced.hudUdpBeaconFreshnessMs
    val hudUdpPollIntervalMs: Int get() = advanced.hudUdpPollIntervalMs
    val hudManualHintDelayMs: Int get() = advanced.hudManualHintDelayMs
    val hudDiscoveryTotalTimeoutMs: Int get() = advanced.hudDiscoveryTotalTimeoutMs
    val hudMdnsServiceInfoTimeoutMs: Int get() = advanced.hudMdnsServiceInfoTimeoutMs
    val autoLightNoGpsRetryMs: Int get() = advanced.autoLightNoGpsRetryMs
    val autoToggleGraceMs: Int get() = advanced.autoToggleGraceMs
    val navMovingKmh: Int get() = advanced.navMovingKmh
    val navPrepareDistM: Int get() = advanced.navPrepareDistM
    val navExecuteDistM: Int get() = advanced.navExecuteDistM
    val navProxBandM: Int get() = advanced.navProxBandM
    val navMinInterStopMoveM: Int get() = advanced.navMinInterStopMoveM
    val navMaxStartDistanceKm: Int get() = advanced.navMaxStartDistanceKm
    val radarFastApproachDistM: Int get() = advanced.radarFastApproachDistM
    val radarFastApproachSpeedKmh: Int get() = advanced.radarFastApproachSpeedKmh
    val radarStaticTargetKmh: Int get() = advanced.radarStaticTargetKmh
    val radarFallbackClosingMps: Int get() = advanced.radarFallbackClosingMps
    val radarMinFrameRateMs: Int get() = advanced.radarMinFrameRateMs
    val chargingTargetPercent: Int get() = advanced.chargingTargetPercent
    val chargingTargetTaperX100: Int get() = advanced.chargingTargetTaperX100
    val chargingCvTaperX100: Int get() = advanced.chargingCvTaperX100
    val chargingWarmupMinPercentGain: Int get() = advanced.chargingWarmupMinPercentGain
    val chargingWarmupMinDurationMs: Int get() = advanced.chargingWarmupMinDurationMs
    val chargingWindowMs: Int get() = advanced.chargingWindowMs
    val chargingSanityCapMinutes: Int get() = advanced.chargingSanityCapMinutes
    val chargingMedianFilterSize: Int get() = advanced.chargingMedianFilterSize
    val inmotionV1Pin: Int get() = advanced.inmotionV1Pin
}

/**
 * The rider's Settings-screen arrangement.
 *
 * [order] lists the movable section keys in display order (an empty list means
 * the built-in default order; unknown / newly added keys fall to the end).
 * [hidden] lists the section keys tucked into the "More" bucket. The Advanced
 * section is always pinned last and is never moved or hidden.
 */
data class SettingsLayout(
    val order: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
)

/**
 * RaceBox-style acceleration split announcements. As the rider accelerates, the
 * voice speaks the time to cross each speed step (e.g. "20 to 30, 1.21 seconds"),
 * optionally comparing to the same step in the previous run or to the session
 * best. A feature-local group (not a global), nested so AppSettings.copy() stays
 * under the JVM/dex 255-argument limit. Increment and minSpeed are held in the
 * rider's display speed unit so the announced band numbers stay round.
 */
data class AccelSplitSettings(
    val enabled: Boolean = false,
    // Speed step between announced bands, in the rider's display speed unit.
    val increment: Int = 10,
    // First band's lower edge, in the rider's display speed unit. Accelerations
    // that never reach this speed are ignored.
    val minSpeed: Int = 20,
    // Append a comparison to the same step in the previous run (per direction).
    val compareToPrevious: Boolean = true,
    // Append a comparison to the session's best time for the step (per direction).
    val compareToBest: Boolean = false,
    // Which crossings to announce: "ACCEL" (speeding up only), "BRAKE" (slowing
    // down only, e.g. "40 to 30"), or "BOTH". Braking splits run down to minSpeed.
    val direction: String = "ACCEL",
)

/**
 * Speed-driven media control (music / podcasts). Pauses playback when the rider
 * slows down (e.g. rolling slowly around people) and resumes it when they speed
 * back up. A feature-local group (not a global), nested so AppSettings.copy()
 * stays under the JVM/dex 255-argument limit. Thresholds are in km/h.
 *
 * The gap between [pauseBelowKmh] and [resumeAboveKmh] is a dead-band that stops
 * rapid play/pause flipping near one speed; AutomationManager also requires the
 * condition to hold briefly before acting. Resume only ever restarts playback
 * this feature itself paused, so speeding up never blasts music the rider had
 * deliberately stopped.
 */
/**
 * Headlight control: when the automation may act, the sun schedule it follows,
 * and the walking-pace cut-off.
 *
 * Nested rather than flat because it is five fields: AppSettings sits near the
 * JVM/dex 255-argument limit, and a group like this belongs in one slot.
 */
data class LightsSettings(
    /** NEVER is off; the other two are on, with the condition they name. */
    val applyWhen: String = ApplyWhenIds.NEVER,
    /** Minutes before sunset to turn the light on. */
    val onMinutesBefore: Int = 30,
    /** Minutes after sunrise to turn it off. */
    val offMinutesAfter: Int = 30,
    /** Cut the light when the rider slows to a walk, and restore it when they
     *  ride on. Independent of the sun schedule, which stays in charge of
     *  whether a light is wanted at all. */
    val offWhenSlow: Boolean = false,
    /** Walking pace, stored metric like every other speed. Five is the figure
     *  walking speed is normally quoted at; four was low enough that a rider
     *  rolling gently up to a crossing stayed above it and kept the beam on,
     *  which is the case the whole cutoff exists for. */
    val offBelowKmh: Float = 5f,
)

/** Values for the "apply when" gate shared by the speed-driven automations. */
object ApplyWhenIds {
    const val NEVER = "NEVER"
    const val CONNECTED = "CONNECTED"
    const val RIDING = "RIDING"
    val ALL = listOf(NEVER, CONNECTED, RIDING)
}

data class MediaControlSettings(
    val pauseEnabled: Boolean = false,
    // Pause when speed is at or below this (km/h).
    val pauseBelowKmh: Int = 6,
    val resumeEnabled: Boolean = false,
    // Resume (only what this feature paused) when speed is at or above this (km/h).
    val resumeAboveKmh: Int = 10,
    // When true, RESUME only fires while audio is on an external output
    // (headphones / Bluetooth / wired / USB), never the phone speaker. Pausing is
    // never gated on the route. Only meaningful with resume on. On by default.
    val requireExternalOutput: Boolean = true,
    // --- Media speed control ---
    // Playback rate follows speed, on the same "speed:value" curve shape
    // auto-volume uses, so the same editor drives it. Needs notification
    // access (see MediaAccessService) and a player that accepts a rate.
    /** NEVER is off; the other two are on, with the condition they name. */
    val rateApplyWhen: String = ApplyWhenIds.NEVER,
    val rateCurve: String = "0:1.0,25:1.15,50:1.30,75:1.45",
)

/** Live location share. Feature-local, nested so AppSettings.copy() stays under the dex 255-arg limit. */
data class ShareSettings(
    val trailMinutes: Int = 5,                // 1..30, how long a friend's fading trail is
    val shareStatsDefault: Boolean = true,    // default for the "Share my stats" toggle
    val lastIdentityMode: String = "ANON",    // ANON | SESSION | PROFILE, remembered per rider
    val lastSessionName: String = "",
    val relayUrl: String = DEFAULT_RELAY_URL,
    /** Per-device random secret, generated on first share. The rider's sender
     *  id in a room is HMAC(secret, roomId): stable for this phone in that room
     *  (a rejoin replaces its own ghost instead of adding one), different in
     *  every room (the relay cannot link rooms), and never copied to another
     *  device (two phones with one secret would collide as one rider). */
    val deviceSecret: String = "",
) {
    companion object {
        const val DEFAULT_RELAY_URL = "wss://eucshare.ried.no"
        /** A relay URL is opened as a WebSocket, so anything that is not a
         *  ws / wss URL cannot work. A synced or hand-edited file carrying
         *  something else is reset to the default rather than thrown at
         *  OkHttp, which answers a malformed URL with an exception. */
        fun isValidRelayUrl(url: String): Boolean =
            url.startsWith("ws://") || url.startsWith("wss://")
    }
}

/**
 * Bluetooth-signal proximity lock / unlock. Locks the wheel as the rider walks
 * away (the BT signal fades while still just connected) and, optionally, unlocks
 * it as they return (the signal strengthens). A feature-local group (not a
 * global), nested so AppSettings.copy() stays under the JVM/dex 255-argument
 * limit. Thresholds are RSSI in dBm (higher = stronger / closer, e.g. -50 is
 * near, -85 is far). unlockAboveDbm sits above lockBelowDbm with a gap - a
 * dead-band that stops lock/unlock flipping; AutomationManager also holds the
 * condition a few seconds before acting. Locking needs a live BLE link, so it
 * fires while the signal is fading, not after a full disconnect.
 */
/**
 * How the battery percentage on screen is worked out.
 *
 * Some wheels report a percentage that disagrees with their own display, so
 * this estimates it from pack voltage instead. Display only: nothing here is
 * ever sent to the wheel, and no firmware limit is touched. The wheel's own
 * protection is unaffected either way, and so is the reading the wheel makes
 * its own decisions on.
 *
 * The curve is battery chemistry, not a brand, so it works on any wheel. Cells
 * in series come from the charged pack voltage the wheel's own model states,
 * which every family records; [seriesCells] is asked of the rider only when the
 * model is unrecognised, since a live pack voltage alone cannot distinguish a
 * 20S from a 30S.
 */
data class BatteryPercentSettings(
    /**
     * Where the percentage on screen comes from. One answer to one question,
     * so it is one control: two switches read as independent when they are
     * not, and the older pair had the custom floor quietly overriding the
     * curve whenever both were on.
     */
    val mode: String = MODE_WHEEL,
    /** Lower endpoint of the custom scale, in millivolts per cell. */
    val minimumCellVoltageMv: Int = 3300,
    /** Upper endpoint of the custom scale (full), in millivolts per cell. Lets
     *  the custom scale fit non-Li-ion packs (LFP full ~3.65 V) instead of the
     *  fixed 4.20 V the curve assumes. */
    val maximumCellVoltageMv: Int = 4200,
    /**
     * Cells in series, for wheels whose model does not state it. Ignored when
     * the connected wheel's model knows its own. Saved per wheel: it belongs
     * to the pack, not to the app, so [WheelProfile] carries it across
     * connects the same way the speed calibration offset does.
     */
    val seriesCells: Int = 20,
    /**
     * Pack energy in watt-hours, or 0 when the rider has not said. Never
     * reported by any wheel and carried in no model table, so it is asked of
     * the rider and saved per wheel on [WheelProfile]. Seeds the range estimate
     * from the first km (capacity / 100 = Wh per percent) until the ride learns
     * a truer rate; 0 leaves the estimate blind until then, as before.
     */
    val capacityWh: Int = 0,
) {
    companion object {
        /** The wheel's own number, untouched. */
        const val MODE_WHEEL = "WHEEL"
        /**
         * A lithium pack's discharge shape: flat below 3.20 V per cell, steep
         * through the 3.20 to 3.40 V knee where a pack empties quickly, then
         * shallow to 4.175 V. The number falls at a rate that matches what the
         * rider feels.
         */
        const val MODE_CURVE = "CURVE"
        /** A straight line from [minimumCellVoltageMv] to [maximumCellVoltageMv]. */
        const val MODE_CUSTOM = "CUSTOM"
        val MODE_VALUES = setOf(MODE_WHEEL, MODE_CURVE, MODE_CUSTOM)

        const val MIN_CELL_MV = 2500
        const val MAX_CELL_MV = 4000
        // "Full at" endpoint. LFP packs top out near 3.65 V/cell, standard
        // Li-ion near 4.20 V, a few high-voltage packs reach 4.35 V.
        const val MIN_FULL_MV = 3400
        const val MAX_FULL_MV = 4350
        // Pack energy. 0 = unset. The largest EUC packs are ~3600 Wh today, so
        // the ceiling is headroom rather than a real limit.
        const val MAX_CAPACITY_WH = 6000
        val SERIES_RANGE = 1..60
    }
}

data class ProximityLockSettings(
    val lockEnabled: Boolean = false,
    // Lock when the signal is at or below this (dBm) - the rider is walking away.
    // Default tuned to a real reading (near ~-59, 4 steps ~-65, 9 steps ~-79):
    // -68 locks at roughly 5 steps, only ~6 dBm below unlock so it feels snappy.
    val lockBelowDbm: Int = -68,
    // Unlock when the signal is at or above this (dBm) - the rider is back close.
    // -62 unlocks within ~2-3 steps; must stay reachable (near maxes out ~-59).
    val unlockAboveDbm: Int = -62,
    /**
     * When, if ever, the wheel unlocks itself again.
     *
     * "NEVER" (default): the automation only ever locks. Nothing unlocks the
     * wheel but the rider.
     *
     * "RETURN": only after the signal actually faded first, so the wheel has to
     * have been left behind. A lock the rider made by hand while standing next
     * to the wheel is theirs to undo - the automation treats it as deliberate
     * and keeps its hands off until they have walked away and come back.
     *
     * "NEAR": a strong signal is enough on its own, whatever locked the wheel.
     * Symmetric with the lock half, and what a rider means by "when I am next
     * to my wheel it should be unlocked" - at the cost of undoing a lock they
     * just made by hand.
     *
     * All three are defensible and two testers wanted different ones, which is
     * why this is a choice rather than a judgement call baked into the code.
     * One control rather than a switch plus a mode: "off" is just a third way
     * of answering the same question.
     */
    val unlockWhen: String = UNLOCK_WHEN_NEVER,
) {
    companion object {
        const val UNLOCK_WHEN_NEVER = "NEVER"
        const val UNLOCK_WHEN_RETURN = "RETURN"
        const val UNLOCK_WHEN_NEAR = "NEAR"
        val UNLOCK_WHEN_VALUES =
            setOf(UNLOCK_WHEN_NEVER, UNLOCK_WHEN_RETURN, UNLOCK_WHEN_NEAR)
    }
}

/**
 * Which items the periodic and on-trigger voice reports speak.
 *
 * Nested under [AppSettings.voiceReports] rather than living as top-level
 * flags. Kotlin generates a static `copy$default` taking the receiver, every
 * property, one int bitmask per 32 properties and a marker; cross 255 slots and
 * the class fails to verify AT RUNTIME with an ART VerifyError, so `copy()`
 * crashes on the first settings write. These 18 flags had AppSettings sitting
 * exactly on that limit, with no room for another report type. Moving them here
 * bought back 17 slots. That is what rule 8 in CLAUDE.md is about, and
 * AppSettingsArgLimitTest now guards it.
 *
 * Add new report toggles HERE, never to AppSettings.
 *
 * Current and Power exist because PWM is the only limit-style report a wheel can
 * offer, and plenty of wheels never report PWM at all. Both are spoken as an
 * average over the recent window, not an instantaneous reading, since a
 * momentary peak tells a rider nothing useful about how hard the wheel is
 * working.
 */
/**
 * The weather module's own knobs. Disabled by default: enabling it adds the
 * weather icon above the dashboard's map button. Comfort thresholds are the
 * rider's, stored metric (°C and tenths of m/s so the shared NumberUpDown
 * stepper can drive them as Ints); display follows the unit settings.
 */
/**
 * The rider's tire-pressure sensor and how pressure is shown.
 *
 * [pairedAddress] is a sensor the rider adopted by scanning. One at a time: a
 * wheel has one tyre, and two paired sensors would leave "the tire pressure"
 * meaning whichever spoke last. Pairing another replaces it, which is also
 * what the section has always said about replacing the wheel's own.
 */
data class TpmsSettings(
    /**
     * Kept so a rider downgrading still finds their first sensor. New code
     * reads [pairedAddresses].
     */
    val pairedAddress: String? = null,
    /**
     * Every cap the rider has added, one per wheel.
     *
     * A single slot was the original rule, on the reasoning that a wheel has
     * one tyre. It does, but a rider has more than one wheel, and the second
     * cap was dropped before it could even be listed.
     */
    val pairedAddresses: List<String> = emptyList(),
    /**
     * "psi", "bar", "kPa", or blank to follow the unit system.
     *
     * Blank by default, and blank means Imperial gets psi and Metric gets bar
     * without anyone being asked. Storing a concrete default instead was worse
     * than deriving it: an existing rider on Imperial kept whatever the
     * default happened to be, and nothing could tell "they chose bar" from
     * "they never chose". A rider who picks one gets it everywhere, which is
     * the part deriving alone could not do - psi in a tyre on a phone that
     * measures everything else in kilometres.
     */
    val pressureUnit: String = "",
)

data class WeatherSettings(
    val enabled: Boolean = true,
    /** How many hours ahead the panel shows, 2..168. Free-form rather than
     *  four presets: a rider who wants "the rest of my afternoon" was
     *  choosing between 6 and 24. The dashboard menu still offers presets,
     *  but those are a temporary view, not this. */
    val windowHours: Int = 8,
    /** Open the panel with its detail charts already unfolded. */
    val openExpanded: Boolean = false,
    /** [com.eried.eucplanet.weather.WeatherSource] id. */
    val source: String = "OPEN_METEO",
    // Riding preferences: how each condition should count for this rider.
    // "DISLIKE" | "NEUTRAL" | "LIKE"; the comfort thresholds (Advanced
    // settings, Weather score group) say when a condition applies, these say
    // how it scores. Rain, snow and wind ship disliked; the rest neutral.
    val prefHot: String = "NEUTRAL",
    val prefCold: String = "NEUTRAL",
    val prefRain: String = "DISLIKE",
    val prefSnow: String = "DISLIKE",
    val prefWind: String = "DISLIKE",
    val prefNight: String = "NEUTRAL",
    val prefGolden: String = "NEUTRAL",
)

data class VoiceReportSettings(
    // Periodic report.
    val periodicSpeed: Boolean = true,
    val periodicBattery: Boolean = true,
    val periodicTemp: Boolean = false,
    val periodicPwm: Boolean = false,
    /** Average current. For wheels that never report PWM. */
    val periodicCurrent: Boolean = false,
    /** Average power. For wheels that never report PWM. */
    val periodicPower: Boolean = false,
    val periodicDistance: Boolean = false,
    val periodicTime: Boolean = false,
    val periodicNavigation: Boolean = false,
    val periodicPhoneBattery: Boolean = false,
    val periodicRecording: Boolean = false,
    // On-trigger (manual / Flic) report.
    val triggerSpeed: Boolean = true,
    val triggerBattery: Boolean = true,
    val triggerTemp: Boolean = true,
    val triggerPwm: Boolean = false,
    val triggerCurrent: Boolean = false,
    val triggerPower: Boolean = false,
    val triggerDistance: Boolean = true,
    val triggerTime: Boolean = true,
    val triggerNavigation: Boolean = false,
    val triggerPhoneBattery: Boolean = false,
    val triggerRecording: Boolean = true,
)

/**
 * Power-user "Advanced" timing / threshold settings. Nested under
 * [AppSettings.advanced] so AppSettings' generated copy() stays under the
 * JVM/dex 255-argument limit. All clamped in SettingsRepository.sanitized().
 */
data class AdvancedSettings(
    // Weather score thresholds (see the WEATHER spec group): when an hour
    // reads too cold / too hot (°C) and where wind starts to bite / gets
    // genuinely hard (tenths of m/s).
    val weatherColdC: Int = 14,
    val weatherHotC: Int = 31,
    val weatherBreezyTenthsMs: Int = 20,
    val weatherWindyTenthsMs: Int = 45,
    val wheelPollIntervalMs: Int = 250,
    val graphSampleIntervalMs: Int = 1000,
    // Window, in samples, for the smoothed Trip Details graphs and the smoothed
    // value the periodic voice reports speak. Trips record at roughly 1 Hz, so
    // this reads as seconds. 10 flattens battery sag and current noise without
    // hiding a real sustained climb.
    val smoothingWindowSamples: Int = 10,
    val tripRecordIntervalMs: Int = 1000,
    // Background safety-net interval for retrying trips left pending (e.g. app
    // closed mid-sync). Minutes -- 15 is Android's WorkManager periodic floor.
    val pendingUploadIntervalMin: Int = 15,
    // Grace after a recording stops before the trip is finalized and synced.
    val tripFinalizeGraceMs: Int = 15000,
    // Speed (km/h) above which a lock command is refused, for safety.
    val lockMaxSpeedKmh: Int = 5,
    val phoneGpsIntervalMs: Int = 1000,
    // Slow "keep-warm" GPS interval used when nothing needs the 1 Hz active
    // stream (idle balanced / low-power tiers). See GpsPowerPolicy.
    val phoneGpsIdleIntervalMs: Int = 10000,
    // Ultra battery saving: seconds of pure-idle (backgrounded, no wheel, not
    // recording/navigating) before the GPS is fully released. 0 = immediately;
    // during the grace GPS holds a cheap low-power fix, then goes off.
    val gpsIdleOffDelaySec: Int = 30,
    // A GPS fix older than this (seconds) counts as "no fix", so a recording
    // that starts before GPS is ready never opens with a stale last-known point.
    val gpsFixMaxAgeSec: Int = 10,
    val hudReportIntervalMs: Int = 200,
    val garminReportIntervalMs: Int = 200,
    val navOffRouteGraceMs: Int = 8000,
    val navOffRouteVoiceAfterMs: Int = 14000,
    val navOffRouteVoiceCooldownMs: Int = 35000,
    val navRerouteAfterMs: Int = 22000,
    val navArrivalDismissMs: Int = 9000,
    val navHuntVoiceIntervalMs: Int = 45000,
    val navHeadingWindowMs: Int = 8000,
    val navFixBufferMs: Int = 14000,
    val navIntermediateFlashMs: Int = 1500,
    val navPopupTimeoutMs: Int = 5000,
    val alarmSlopeWindowMs: Int = 1500,
    val alarmBufferMaxMs: Int = 2500,
    val alarmSlopeMinSamples: Int = 3,
    val alarmSlopeMinSpanMs: Int = 300,
    val radarClearDecayMs: Int = 3000,
    val automationLightCheckIntervalMs: Int = 60000,
    val hudBackoffMinMs: Int = 1000,
    val hudBackoffMaxMs: Int = 5000,
    val hudMdnsTimeoutMs: Int = 6000,
    val hudDiscoverySprintMs: Int = 30000,
    val hudUdpProbeTimeoutMs: Int = 8000,
    val hudUdpBeaconFreshnessMs: Int = 10000,
    val hudUdpPollIntervalMs: Int = 200,
    val hudManualHintDelayMs: Int = 1500,
    val hudDiscoveryTotalTimeoutMs: Int = 15000,
    val hudMdnsServiceInfoTimeoutMs: Int = 1000,
    val autoLightNoGpsRetryMs: Int = 2000,
    val autoToggleGraceMs: Int = 4000,
    val navMovingKmh: Int = 4,
    val navPrepareDistM: Int = 200,
    val navExecuteDistM: Int = 30,
    val navProxBandM: Int = 4,
    val navMinInterStopMoveM: Int = 30,
    val navMaxStartDistanceKm: Int = 50,
    val radarFastApproachDistM: Int = 50,
    val radarFastApproachSpeedKmh: Int = 60,
    val radarStaticTargetKmh: Int = 3,
    val radarFallbackClosingMps: Int = 10,
    val radarMinFrameRateMs: Int = 100,
    val chargingTargetPercent: Int = 80,
    val chargingTargetTaperX100: Int = 105,
    val chargingCvTaperX100: Int = 200,
    val chargingWarmupMinPercentGain: Int = 1,
    val chargingWarmupMinDurationMs: Int = 40000,
    val chargingWindowMs: Int = 300000,
    val chargingSanityCapMinutes: Int = 480,
    val chargingMedianFilterSize: Int = 7,
    // Battery cell / pack balance coloring (AdvGroup.CHARGING). How far a cell
    // may drift from the pack MEDIAN before it stops reading as balanced green:
    // below by warn -> yellow, by danger -> red; above by high -> blue. The three
    // mV values drive the per-cell voltage view; packBalanceTolerancePct drives
    // the SoC-only pack fallback (no per-cell voltage available).
    val cellLowWarnMv: Int = 30,
    val cellLowDangerMv: Int = 80,
    val cellHighMv: Int = 40,
    val packBalanceTolerancePct: Int = 5,
    // Screen geometry variables (AdvGroup.GEOMETRY): the thresholds and sizes
    // behind compact mode, the cover lens cutout and the navigator sidebar.
    val compactMaxScreenDp: Int = 500,
    val coverCutoutInsetDp: Int = 96,
    val simpleSpeedoScalePct: Int = 62,
    val navSidebarWidthDp: Int = 400,
    val navSidebarMinScreenDp: Int = 600,
    // InMotion V1 (V5 / V8 / V10 / L6) BLE access PIN, stored as the 6-digit
    // number (0 = "000000", the factory default). Sent on connect so the wheel
    // leaves its identity-only wait and streams; wheels with no PIN ignore it.
    val inmotionV1Pin: Int = 0,
)

// FlicAction enum removed (2026-05). Replaced by
// [com.eried.eucplanet.data.model.ActionCatalog] which is the single source
// of truth for every rider-triggerable command and the surfaces it can be
// bound to. Settings still store the action name as a String (e.g. "HORN",
// "VOICE_ANNOUNCE", or "NONE" for unbound) so there is no schema change.

/**
 * Flip the three unit fields between a clean metric trio and a clean imperial
 * trio in one write. Metric is the reference state, so anything that isn't
 * already a clean imperial trio (incl. custom mixes like knots + Norwegian
 * mile) snaps to metric first. Shared by the TOGGLE_UNITS action so the
 * dashboard tile and the service-mode overlay use one definition.
 */
fun AppSettings.withUnitsToggled(): AppSettings {
    val isImperial = unitSpeed == "mph" && unitDistance == "mi" && unitTemp == "F"
    return if (isImperial) copy(unitSpeed = "kmh", unitDistance = "km", unitTemp = "C")
    else copy(unitSpeed = "mph", unitDistance = "mi", unitTemp = "F")
}
