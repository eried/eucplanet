package com.eried.eucplanet.service

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.platform.format

/**
 * The line under the wheel's name in the ride notification (Android) and the
 * Live Activity (iOS), so both phones show the same thing.
 */
object RideNotificationText {
    /** Riding: speed in the rider's unit, battery and voltage. */
    fun telemetry(displaySpeed: Float, speedUnit: String, batteryPercent: Int, voltage: Float): String =
        "%.1f %s | %d%% | %.1f V".format(displaySpeed, speedUnit, batteryPercent, voltage)

    /** Not moving: the connection state, "Connected", "Connecting"... */
    fun state(state: ConnectionState): String = state.name.lowercase().replaceFirstChar { it.uppercase() }
}
