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
    }
}
