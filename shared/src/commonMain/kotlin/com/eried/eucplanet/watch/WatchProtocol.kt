package com.eried.eucplanet.watch

/**
 * Phone ↔ watch contract — the iOS take on Android's `WatchProtocol.kt` /
 * `WearBridge`. The Android app streams telemetry to a Wear OS app over the
 * Wearable Data Layer; on iOS the same role is played by **WatchConnectivity**
 * (`WCSession`), with the Swift side forwarding [WatchState] snapshots to the
 * paired Apple Watch and routing the watch's button intents back here.
 *
 * This is the single source of truth for the wire shape so the Kotlin phone
 * side and the SwiftUI watch app can't drift. v1 is the **minimal riding dial**
 * (speed + battery + horn / light); richer parity (PWM band, nav arrow, stem
 * buttons, theme mirror) can grow these fields later — keep them tiny because
 * every snapshot round-trips over Bluetooth.
 */
data class WatchState(
    /** True only for a live wheel session (not demo, not idle). */
    val connected: Boolean = false,
    val wheelName: String = "",
    /** Always km/h on the wire; the watch converts using [unitSpeed]. */
    val speedKmh: Float = 0f,
    val batteryPercent: Int = 0,
    val pwmPercent: Float = 0f,
    /** Always °C on the wire; the watch converts using [unitTemp]. */
    val tempC: Float = 0f,
    /** Gauge full-scale (km/h), mirrors the phone dashboard's computed max. */
    val maxSpeedKmh: Float = 30f,
    /** The wheel's reported headlight state — the watch's light button mirrors it. */
    val lightOn: Boolean = false,
    val hasHorn: Boolean = true,
    val hasLight: Boolean = true,
    /** Resolved unit codes (same vocabulary as the phone Settings): speed
     *  "kmh"/"mph"/"ms"/"kn", temperature "C"/"F"/"K". */
    val unitSpeed: String = "kmh",
    val unitTemp: String = "C",
    /** Active theme accent as "#AARRGGBB" so the watch dial follows the rider's
     *  theme; empty string means "use the watch's built-in palette". */
    val accentArgb: String = "",
)

/**
 * Watch → phone button intents. Sent by the watch over the `WCSession` message
 * channel; [WatchLink.setControlHandler] receives them on the phone. Horn is
 * fire-and-forget; light toggles relative to the wheel's reported state.
 */
object WatchControl {
    const val HORN = "horn"
    const val LIGHT_TOGGLE = "light_toggle"
    /** Message key carrying one of the constants above. */
    const val KEY = "control"
}

/**
 * Platform seam for the phone↔watch link.
 *
 *  - **iOS** forwards [publish] into `WCSession` (Swift) and feeds watch button
 *    presses back through [setControlHandler].
 *  - **Android** is a no-op: the Android app already ships its own Wear OS
 *    bridge (`WearBridge`), so the shared module must not double-publish.
 */
expect object WatchLink {
    /** Phone → watch: push the latest telemetry snapshot (deduped downstream). */
    fun publish(state: WatchState)

    /** Register the handler invoked when the watch sends a [WatchControl] intent. */
    fun setControlHandler(handler: (String) -> Unit)
}
