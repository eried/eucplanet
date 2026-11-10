package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs the shared AutomationManager as WheelService does on Android: every
 * telemetry frame, a fresh session's suspensions cleared on connect, and the
 * link-lost reset on disconnect.
 */
internal object IosAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start() {
        val wheel = IosAppGraph.wheel
        val automation = IosAppGraph.automation
        scope.launch {
            wheel.wheelData.collect { automation.evaluate(IosAppGraph.settings.currentOrLoad()) }
        }
        scope.launch {
            var last: ConnectionState? = null
            wheel.connectionState.collect { state ->
                if (last != null && state != last) when (state) {
                    ConnectionState.CONNECTED -> {
                        automation.clearLightsSuspension()
                        automation.clearLockSuspension()
                    }
                    ConnectionState.DISCONNECTED -> {
                        automation.restoreBaselineVolume()
                        automation.resetMediaControl()
                        automation.onProximityLinkLost()
                    }
                    else -> Unit
                }
                last = state
            }
        }
    }
}
