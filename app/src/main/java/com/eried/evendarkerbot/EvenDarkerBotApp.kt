package com.eried.evendarkerbot

import android.app.Application
import com.eried.evendarkerbot.flic.FlicManager
import com.eried.evendarkerbot.util.CrashHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EvenDarkerBotApp : Application() {

    @Inject lateinit var flicManager: FlicManager

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        flicManager.initialize()
    }
}
