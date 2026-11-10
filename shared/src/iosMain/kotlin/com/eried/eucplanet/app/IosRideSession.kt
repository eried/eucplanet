package com.eried.eucplanet.app

import com.eried.eucplanet.service.RideSession
import com.eried.eucplanet.service.asRideWheel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The shared [RideSession] on the iPhone, fed as WheelService feeds it on
 * Android: every telemetry frame, the connection changes, the auto-record
 * idle stop and the periodic voice reports. iOS adds nothing of its own to a
 * connect or a disconnect (the Live Activity follows the link by itself).
 */
internal object IosRideSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val session: RideSession by lazy {
        RideSession(
            wheel = IosAppGraph.wheel.asRideWheel(),
            settings = IosAppGraph.settings,
            automation = IosAppGraph.automation,
            trips = IosTrips,
            voice = IosAppGraph.voice,
            phone = IosPhoneDevice,
            scope = scope,
            postChargeAlert = IosChargeAlerts::post,
        )
    }

    fun start() {
        val s = session
        scope.launch {
            IosAppGraph.wheel.wheelData.collect { s.onFrame(it, IosAppGraph.settings.currentOrLoad()) }
        }
        s.followConnection()
        s.startAutoRecordIdleLoop()
        s.startVoiceLoop()
    }
}
