package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.service.RideSession
import com.eried.eucplanet.service.asRideWheel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The shared [RideSession] on the iPhone, fed as WheelService feeds it on
 * Android: every telemetry frame, the connection changes, the auto-record
 * idle stop and the periodic voice reports. Of what WheelService adds, the
 * engine sound is here too, fed the same way (every frame's speed and PWM,
 * the settings, the connection, the voice and the wheel's top speed); the
 * Live Activity follows the link by itself.
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
        val engine = IosAppGraph.engineSound
        val wheel = IosAppGraph.wheel
        scope.launch {
            wheel.wheelData.collect { data ->
                val settings = IosAppGraph.settings.currentOrLoad()
                s.onFrame(data, settings)
                if (settings.engineSoundEnabled) engine.pushTelemetry(data.speed, data.pwm)
            }
        }
        // Engine settings and lifecycle on every settings change, as WheelService.
        scope.launch {
            IosAppGraph.settings.settings.collect { settings ->
                engine.applySettings(settings)
                engine.setConnected(wheel.connectionState.value == ConnectionState.CONNECTED, settings)
            }
        }
        // The engine ducks itself while the voice speaks.
        scope.launch { IosAppGraph.voice.isSpeaking.collect { engine.setVoiceActive(it) } }
        // Speed to RPM against the wheel's own top speed.
        scope.launch { wheel.maxSpeedCap.collect { engine.setMaxSpeedRef(it) } }
        s.followConnection(
            onConnected = { settings -> engine.setConnected(true, settings) },
            onDisconnected = { settings -> engine.setConnected(false, settings) },
        )
        s.startAutoRecordIdleLoop()
        s.startVoiceLoop()
    }
}
