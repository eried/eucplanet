package com.eried.eucplanet

import android.app.Application
import androidx.work.Configuration
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.di.appModules
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.garmin.GarminBridge
import com.eried.eucplanet.map.MapTileCache
import com.eried.eucplanet.util.CrashHandler
import com.eried.eucplanet.wear.WearBridge
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.startKoin

class EucPlanetApp : Application(), Configuration.Provider {

    lateinit var flicManager: FlicManager
    lateinit var settingsRepository: SettingsRepository
    lateinit var wearBridge: WearBridge
    lateinit var garminBridge: GarminBridge
    lateinit var amazfitBridge: com.eried.eucplanet.amazfit.AmazfitBridge
    /**
     * Constructed eagerly at app start so HudServer's `init` block runs
     * and starts watching `settings.hudServerEnabled`. Without this, the
     * server is lazy-constructed when SettingsScreen first inflates -- so
     * the toggle is only "live" while the rider is sitting on the
     * Settings page, which is exactly the opposite of what you want.
     */
    lateinit var hudServer: com.eried.eucplanet.service.hud.HudServer

    /**
     * Injected at cold start so its init{} registers the periodic pending-upload
     * safety-net, and so onCreate can reconcile any trip left pending by an app
     * that was closed mid-sync -- the "closed too early" case.
     */
    lateinit var syncManager: com.eried.eucplanet.data.sync.SyncManager

    /**
     * Constructed eagerly so its armed flag is read from disk before the first
     * screen composes, and so the app relaunches straight into the locked
     * screen instead of flashing the dashboard first.
     */
    lateinit var legalLockdown: com.eried.eucplanet.data.repository.LegalLockdownController

    // KoinWorkerFactory looks the worker up in the global Koin context when
    // WorkManager first asks for it, and Koin starts before anything in
    // onCreate can touch WorkManager. TripRepository's start-up sweep can call
    // WorkManager on another thread while the fields below are still being
    // filled; with Koin already started that cannot race.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(KoinWorkerFactory())
            .build()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@EucPlanetApp)
            modules(appModules)
        }
        // Built eagerly, in declaration order, before anything below runs, so
        // every start-up singleton exists at the same point it always did.
        flicManager = get()
        settingsRepository = get()
        wearBridge = get()
        garminBridge = get()
        amazfitBridge = get()
        hudServer = get()
        syncManager = get()
        legalLockdown = get()
        CrashHandler.install(this)
        // Track UI foreground so background-only process wakes (a periodic
        // upload worker re-creating the process) don't auto-connect hardware
        // like the external GPS box. See com.eried.eucplanet.util.AppForeground.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) =
                com.eried.eucplanet.util.AppForeground.onActivityStarted()
            override fun onActivityStopped(activity: android.app.Activity) =
                com.eried.eucplanet.util.AppForeground.onActivityStopped()
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, o: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        // Every start below is one line, and one line is easy to lose: the
        // watch-map merge replaced flicManager.initialize() with the tile
        // cache start and Flic went dead (no scan, no delete, no presses)
        // with nothing in the log. AppStartupTest pins the whole list.
        flicManager.initialize()
        MapTileCache.start(settingsRepository)
        wearBridge.start()
        garminBridge.start()
        amazfitBridge.start()
        // Touch hudServer so its init{} runs even on a cold app start.
        // The reference assignment alone is enough; HudServer's settings
        // collector takes over from there.
        hudServer.hashCode()
        // Catch any trip left pending by a too-early close, and register the
        // periodic upload safety-net. Both touch WorkManager, so they run here
        // (once Koin is up and the fields above are set) rather than in
        // SyncManager's init{}, which runs while the graph is still being built.
        // Same for the online (eucstats) uploads and the Dropbox mirror: a trip
        // strands if its retry chain ever ended, and nothing else restarts it.
        // All run a few seconds in so the rider id / link token have loaded, and
        // no-op when their destination is not configured.
        syncManager.reconcileOnStart()
        // Legal Mode Lockdown stops service mode too. DiagnosticsLogger is an
        // object with no injection, so the armed state is mirrored onto it here,
        // the one place that already lives for the whole process.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            legalLockdown.engaged.collect { armed ->
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.lockedDown = armed
                if (armed) com.eried.eucplanet.diagnostics.DiagnosticsLogger.disable()
            }
        }
    }
}
