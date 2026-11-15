package com.eried.eucplanet.app

import com.eried.eucplanet.data.repository.KilledTripResume
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.service.LaunchKind
import com.eried.eucplanet.service.StartOnce
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo

/**
 * The app's start: everything that runs for the whole process, what
 * EucPlanetApp and WheelService start on Android. AppDelegate.swift calls
 * [start] at launch, before any screen, so a launch with no screen (iOS
 * relaunching the app in the background when a wheel it was linked to
 * talks, Bluetooth state restoration) runs the wheel link, the ride
 * session, the recorder and the GPS just the same. The first screen
 * (MainViewController) calls it too and then only attaches to what runs:
 * [StartOnce] keeps it to one start, so there is one wheel link and one
 * recording. Main thread only.
 */
object IosAppStart {
    private val once = StartOnce { kind -> run(kind) }

    /** How this process was started, null before [start]. */
    val kind: LaunchKind? get() = once.kind

    /**
     * Starts everything once. [bluetoothRelaunch] when iOS relaunched the
     * app for a Bluetooth device (UIApplication.LaunchOptionsKey.bluetoothCentrals),
     * or the EUC_HEADLESS hook simulates it. Later calls do nothing.
     */
    fun start(bluetoothRelaunch: Boolean) {
        val kind = if (bluetoothRelaunch) LaunchKind.BLUETOOTH_RELAUNCH else LaunchKind.FOREGROUND
        if (!once.start(kind)) Log.i(TAG, "Already started (${once.kind}); attaching")
    }

    private fun run(kind: LaunchKind) {
        Log.i(TAG, "Starting: $kind")
        if (kind == LaunchKind.BLUETOOTH_RELAUNCH) {
            // Mid-ride, no "welcome" when the rider next opens the app.
            IosAppGraph.voice.skipWelcome()
        }
        // Uncaught Kotlin exceptions to Documents/crashes, as Android's CrashHandler.
        IosCrashLog.install()
        val link = IosAppGraph.link
        val wheel = IosAppGraph.wheel
        // For scripted runs on the simulator, which cannot tap:
        // SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:<id> xcrun simctl launch ...
        (NSProcessInfo.processInfo.environment["EUC_CONNECT"] as? String)?.let { wheel.connect(it) }
        MainScope().launch {
            IosAppGraph.seed()
            // A recording the app was killed in, finalized or dropped, as on Android.
            // On a Bluetooth relaunch the newest one, killed moments ago, carries on
            // instead (KilledTripResume): the ride the system cut is still going.
            val resumeWithin = if (kind == LaunchKind.BLUETOOTH_RELAUNCH) {
                KilledTripResume.maxGapMs(IosAppGraph.settings.get())
            } else {
                null
            }
            IosTrips.recoverKilled(resumeWithinMs = resumeWithin)
            // Then the wheel out of any trip file never asked, as Android's start does.
            IosTrips.backfillWheelMeta()
        }
        // The phone's GPS for the dashboard and trips, as WheelService starts it
        // on Android, and lockdown stopping a recording.
        IosLocation.start()
        IosTrips.followWheel()
        // The trip meter counts while a wheel is connected, dashboard open or not.
        IosTripMeter.meter
        // Leaderboard uploads at launch and on every return to the front.
        IosEucStats.start()
        // The charge alerts, as WheelService posts them on Android.
        IosChargeAlerts.start()
        // The radar and the tyre caps, as WheelService injects them on Android:
        // the repository reconnects a paired radar once it exists, and the
        // scanner listens for paired caps, following the pairing from then on.
        IosAppGraph.radar
        IosAppGraph.tpmsScanner.startMonitoring()
        // The external GPS box, as WheelService injects it on Android: the
        // repository reconnects a paired box while the app is in front or a ride
        // runs. EUC_SERVICE, EUC_EXTGPS and EUC_SOURCES drive it for scripted runs.
        IosAppGraph.externalGps
        IosLaunchHooks.start()
        // The offline downloads left from the last run carry on (WorkManager
        // keeps them on Android), and the weekly Wi-Fi update check.
        IosAppGraph.offlineTiles
        IosAppGraph.offlineJobs.start()
        // SIMCTL_CHILD_EUC_VOICE_EVERY=15 makes the periodic report speak every
        // 15 s whatever the speed and output, to hear it with the app in the
        // background on the simulator. It is saved, like any settings change.
        (NSProcessInfo.processInfo.environment["EUC_VOICE_EVERY"] as? String)?.toIntOrNull()?.let { secs ->
            kotlinx.coroutines.runBlocking {
                IosAppGraph.settings.update {
                    it.copy(
                        voiceEnabled = true,
                        voiceAnnounceWhen = "ALWAYS",
                        voiceAnnounceRequireExternal = false,
                        voiceIntervalSeconds = secs,
                    )
                }
            }
        }
        // SIMCTL_CHILD_EUC_ENGINE=V8_MUSCLE turns the motor sound on with that
        // engine (an EngineProfile key), to hear it follow a virtual wheel on the
        // simulator. Saved, like any settings change.
        (NSProcessInfo.processInfo.environment["EUC_ENGINE"] as? String)?.let { key ->
            kotlinx.coroutines.runBlocking {
                IosAppGraph.settings.update {
                    it.copy(engineSound = it.engineSound.copy(enabled = true, type = key))
                }
            }
        }
        // What runs around a ride (automations, announcements, auto-record,
        // charge alerts, periodic reports): the shared RideSession, as WheelService runs it.
        IosRideSession.start()
        // Dropbox trips at launch, on every return to the front and while open.
        IosDropboxSync.start()
        // Keep screen on, and the language picker showing the applied language.
        IosDisplayPrefs.start()
        // The dashboard's "Needs attention" list, as MainActivity refreshes it on Android.
        IosAppHealth.start()
        // Legal Mode Lockdown: read before the first frame, and service mode
        // stopped while it runs, as EucPlanetApp does on Android.
        IosLockdown.start()
        // EUC_SNAP="15,20,100" saves a burst of window screenshots (field debugging).
        IosDebugSnaps.start()
        // SIMCTL_CHILD_EUC_RECORD=20 records a 20 s trip, for scripted runs.
        (NSProcessInfo.processInfo.environment["EUC_RECORD"] as? String)?.toLongOrNull()?.let { secs ->
            MainScope().launch {
                IosTrips.startRecording()
                kotlinx.coroutines.delay(secs * 1000)
                IosTrips.stopRecording()
            }
        }
        // SIMCTL_CHILD_EUC_ROUTE="59.9139,10.7522;59.9075,10.7602" plans those
        // stops (lat,lon pairs) as the route builder's draft, as if the rider had
        // tapped them, for scripted runs (with EUC_SCREEN=route).
        (NSProcessInfo.processInfo.environment["EUC_ROUTE"] as? String)?.let { spec ->
            val stops = spec.split(';').mapNotNull { pair ->
                val parts = pair.split(',')
                val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                if (lat != null && lon != null) com.eried.eucplanet.data.model.Waypoint(lat, lon) else null
            }
            if (stops.isNotEmpty()) {
                // SIMCTL_CHILD_EUC_ROUTE_MODE=CYCLING (a TravelMode) instead of the rider's default.
                val mode = NSProcessInfo.processInfo.environment["EUC_ROUTE_MODE"] as? String
                    ?: kotlinx.coroutines.runBlocking { IosAppGraph.settings.get().navDefaultTravelMode }
                IosAppGraph.currentRoute.set(
                    com.eried.eucplanet.data.model.NavRoute(
                        "Route", stops, com.eried.eucplanet.data.model.TravelMode.fromName(mode),
                        emptyList(), emptyList(), 0.0,
                    )
                )
            }
        }
        killInBackgroundHook()
        // The theme editor is off at every fresh start, as on Android.
        IosThemeEditor.start()
        // The ride Live Activity follows the link.
        RideActivity.follow(link, wheel.wheelData)
    }

    /**
     * EUC_KILL_IN_BACKGROUND=20: [secs] after the app goes to the background
     * it ends itself with SIGKILL, as iOS ends an app to free memory, to try
     * the Bluetooth relaunch on an iPhone (docs/IOS_SYNC.md). Set in the
     * Xcode scheme; the relaunch iOS makes has no such variable.
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun killInBackgroundHook() {
        val secs = (NSProcessInfo.processInfo.environment["EUC_KILL_IN_BACKGROUND"] as? String)?.toLongOrNull() ?: return
        var pending: kotlinx.coroutines.Job? = null
        val center = platform.Foundation.NSNotificationCenter.defaultCenter
        val main = platform.Foundation.NSOperationQueue.mainQueue
        center.addObserverForName(platform.UIKit.UIApplicationDidEnterBackgroundNotification, null, main) { _ ->
            pending?.cancel()
            pending = MainScope().launch {
                kotlinx.coroutines.delay(secs * 1000)
                Log.w(TAG, "EUC_KILL_IN_BACKGROUND: ending the app as iOS would")
                platform.posix.kill(platform.posix.getpid(), platform.posix.SIGKILL)
            }
        }
        center.addObserverForName(platform.UIKit.UIApplicationWillEnterForegroundNotification, null, main) { _ ->
            pending?.cancel()
            pending = null
        }
    }

    private const val TAG = "AppStart"
}
