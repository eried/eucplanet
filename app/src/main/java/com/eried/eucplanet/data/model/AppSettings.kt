package com.eried.eucplanet.data.model

import com.eried.eucplanet.R

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
    val voiceIntervalSeconds: Int = 60,
    val voiceSpeechRate: Float = 1.2f,
    val voiceLocale: String = "en_US",  // locale tag for TTS voice
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
    // Periodic voice report toggles
    val voiceReportSpeed: Boolean = true,
    val voiceReportBattery: Boolean = true,
    val voiceReportTemp: Boolean = false,
    val voiceReportPwm: Boolean = false,
    val voiceReportDistance: Boolean = false,
    val voiceReportTime: Boolean = false,
    val voiceReportNavigation: Boolean = false,
    // On-trigger (manual/flic) voice report toggles
    val triggerReportSpeed: Boolean = true,
    val triggerReportBattery: Boolean = true,
    val triggerReportTemp: Boolean = true,
    val triggerReportPwm: Boolean = false,
    val triggerReportDistance: Boolean = true,
    val triggerReportTime: Boolean = true,
    val triggerReportNavigation: Boolean = false,

    // Voice report: include recording state
    val voiceReportRecording: Boolean = false,
    val triggerReportRecording: Boolean = true,

    // Voice report item order (comma-separated: Speed,Battery,Time,Temp,PWM,Distance,Recording)
    val voiceReportOrder: String = "Speed,Battery,Time,Temp,PWM,Distance,Recording",

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
    val autoLightsEnabled: Boolean = false,
    val autoLightsOnMinutesBefore: Int = 30,   // minutes before sunset to turn lights ON
    val autoLightsOffMinutesAfter: Int = 30,   // minutes after sunrise to turn lights OFF

    // Speed-based volume boost. Multiplier curve maps speed to 1×–2× of the user's baseline volume.
    // 1× = no boost (baseline), 2× = double the baseline (capped at 100% by the system).
    // 4 control points at 0/25/50/75 km/h. 0 km/h is locked at 1× (no boost at standstill).
    // Baseline starts at -1 (uninitialized) and is captured from the system music volume on first
    // tick after enable. Manual volume changes during motion rebase: baseline = manual / multiplier.
    val autoVolumeEnabled: Boolean = false,
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
    val navMapType: String = "LIGHT",
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
     * When ON (default), the phone runs a 4-layer discovery chain to find
     * the HUD's IP automatically: UDP beacon → mDNS browse → manual hint
     * (whatever is in [hudIp]) → subnet probe of the phone's own /24. The
     * winning channel is published on the HUD-settings status line so the
     * rider can see how the link was established. When OFF, only [hudIp]
     * is tried -- legacy behaviour, retained as an escape hatch for cases
     * where every auto path is broken (very rare).
     */
    val hudAutoDiscover: Boolean = true,
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
     * Which CartoCDN raster style the HUD should use for its Map screen
     * and the MAP element inside a Custom overlay. Empty = the HUD picks
     * its compiled-in default (currently "voyager", neutral parchment
     * background). Other supported codes: "dark_matter",
     * "dark_matter_nolabels", "voyager", "light_all", "positron".
     * Anything else falls back to the HUD's compiled default so the
     * rider doesn't get a blank map if they pick something we removed.
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
    /** Replay video export format: "MOV" (ProRes 4444 alpha, default), "APNG"
     *  (alpha), "GIF" (1-bit alpha) or "MP4" (chroma-filled). */
    val studioReplayVideoFormat: String = "MOV",
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
    /** MOV alpha codec: false = ProRes 4444, true = QuickTime Animation (qtrle,
     *  bigger but maximally editor-compatible alpha). */
    val studioReplayMovQtrle: Boolean = false,

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
    val dropboxLastSyncAt: Long = 0L
) {
    // Delegating getters so reads like `settings.wheelPollIntervalMs` keep working
    // after the 46 advanced fields moved into the nested [AdvancedSettings] (which
    // keeps AppSettings' copy() under the JVM/dex 255-argument limit). Writes use
    // copy(advanced = advanced.copy(...)).
    val wheelPollIntervalMs: Int get() = advanced.wheelPollIntervalMs
    val graphSampleIntervalMs: Int get() = advanced.graphSampleIntervalMs
    val tripRecordIntervalMs: Int get() = advanced.tripRecordIntervalMs
    val phoneGpsIntervalMs: Int get() = advanced.phoneGpsIntervalMs
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
    val autoLightNoGpsRetryMs: Int get() = advanced.autoLightNoGpsRetryMs
    val autoToggleGraceMs: Int get() = advanced.autoToggleGraceMs
    val navMovingKmh: Int get() = advanced.navMovingKmh
    val navPrepareDistM: Int get() = advanced.navPrepareDistM
    val navExecuteDistM: Int get() = advanced.navExecuteDistM
    val navProxBandM: Int get() = advanced.navProxBandM
    val navMinInterStopMoveM: Int get() = advanced.navMinInterStopMoveM
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
 * Power-user "Advanced" timing / threshold settings. Nested under
 * [AppSettings.advanced] so AppSettings' generated copy() stays under the
 * JVM/dex 255-argument limit. All clamped in SettingsRepository.sanitized().
 */
data class AdvancedSettings(
    val wheelPollIntervalMs: Int = 250,
    val graphSampleIntervalMs: Int = 1000,
    val tripRecordIntervalMs: Int = 1000,
    val phoneGpsIntervalMs: Int = 1000,
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
    val hudMdnsTimeoutMs: Int = 5000,
    val hudDiscoverySprintMs: Int = 30000,
    val autoLightNoGpsRetryMs: Int = 2000,
    val autoToggleGraceMs: Int = 4000,
    val navMovingKmh: Int = 4,
    val navPrepareDistM: Int = 200,
    val navExecuteDistM: Int = 30,
    val navProxBandM: Int = 4,
    val navMinInterStopMoveM: Int = 30,
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
