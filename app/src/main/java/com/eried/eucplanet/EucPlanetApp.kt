package com.eried.eucplanet

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.garmin.GarminBridge
import com.eried.eucplanet.util.CrashHandler
import com.eried.eucplanet.wear.WearBridge
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EucPlanetApp : Application(), Configuration.Provider {

    @Inject lateinit var flicManager: FlicManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var wearBridge: WearBridge
    @Inject lateinit var garminBridge: GarminBridge
    /**
     * Constructed eagerly at app start so HudServer's `init` block runs
     * and starts watching `settings.hudServerEnabled`. Without this, the
     * server is lazy-constructed when SettingsScreen first inflates -- so
     * the toggle is only "live" while the rider is sitting on the
     * Settings page, which is exactly the opposite of what you want.
     */
    @Inject lateinit var hudServer: com.eried.eucplanet.service.hud.HudServer

    /**
     * Injected at cold start so its init{} registers the periodic pending-upload
     * safety-net, and so onCreate can reconcile any trip left pending by an app
     * that was closed mid-sync -- the "closed too early" case.
     */
    @Inject lateinit var syncManager: com.eried.eucplanet.data.sync.SyncManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        flicManager.initialize()
        wearBridge.start()
        garminBridge.start()
        // Touch hudServer so its init{} runs even on a cold app start.
        // The reference assignment alone is enough; HudServer's settings
        // collector takes over from there.
        hudServer.hashCode()
        // Catch any trip left pending by a too-early close, and register the
        // periodic upload safety-net. Both touch WorkManager, so they run here
        // (after Hilt has injected workerFactory) rather than in SyncManager's
        // init{}, which runs mid-injection and raced the lateinit.
        syncManager.reconcilePendingTripUploads()
        syncManager.startPendingUploadWatcher()
    }
}
