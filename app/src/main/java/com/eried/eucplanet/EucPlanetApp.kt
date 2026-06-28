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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // motoeye-fix-again DIAGNOSTIC build: auto-start Service Mode at process
        // launch so the log captures the wheel/HUD connection from the very first
        // byte, even if the tester forgets to enable it via the seven-tap gesture.
        // The buffer is in-memory and cleared on process exit (see DiagnosticsLogger).
        // NOT for the public release branch.
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.enable()
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("service mode auto-started at app launch (diagnostic build)")
        flicManager.initialize()
        wearBridge.start()
        garminBridge.start()
        // Touch hudServer so its init{} runs even on a cold app start.
        // The reference assignment alone is enough; HudServer's settings
        // collector takes over from there.
        hudServer.hashCode()
    }
}
