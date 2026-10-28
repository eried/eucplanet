package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.service.RideNotificationText
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The lock-screen Live Activity, written in Swift (iosApp/RideLiveActivity.swift,
 * ActivityKit) and registered here at launch. Kotlin decides what it says.
 */
interface RideActivityBridge {
    fun start(title: String, text: String)
    fun update(title: String, text: String)
    fun end()
}

/**
 * The iOS counterpart of Android's ongoing ride notification: up while a
 * wheel is connected, with the wheel's name over the same line Android shows
 * ([RideNotificationText]), refreshed at most once a second and only when the
 * text changes, as the notification is.
 */
object RideActivity {
    var bridge: RideActivityBridge? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shown = false
    private var lastText: String? = null
    private var lastAt = 0L

    fun follow(link: IosWheelLink, data: StateFlow<WheelData>) {
        scope.launch {
            combine(link.connectionState, link.connectedDeviceName, data) { s, n, d -> Triple(s, n, d) }
                .collect { (state, name, d) -> show(state, name, d) }
        }
    }

    private suspend fun show(state: ConnectionState, name: String?, data: WheelData) {
        val b = bridge ?: return
        if (state == ConnectionState.DISCONNECTED) {
            if (shown) b.end()
            shown = false
            lastText = null
            return
        }
        val settings = IosAppGraph.settings.current.value
        val title = name ?: settings.lastDeviceName ?: getString(Res.string.app_name)
        val text = if (data.speed > 0) {
            val unit = Units.effectiveSpeedUnit(settings)
            RideNotificationText.telemetry(
                Units.speed(data.speed, unit), getString(Units.speedUnitRes(unit)), data.batteryPercent, data.voltage,
            )
        } else RideNotificationText.state(state)
        val line = "$title\n$text"
        val now = System.currentTimeMillis()
        if (!shown) {
            b.start(title, text)
            shown = true
        } else if (line != lastText && now - lastAt >= 1000) {
            b.update(title, text)
        } else return
        lastText = line
        lastAt = now
    }
}
