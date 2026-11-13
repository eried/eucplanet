package com.eried.eucplanet.hud.protocol

/**
 * The HUD's screens as the phone names them, and the carousel the phone ships
 * in [HudState.enabledHudScreens].
 *
 * The ids are the names of the HUD's own Screen enum. The phone's settings
 * list and its wire frame both read from here, so what the rider sees switched
 * on is what the HUD is told to show.
 */
object HudScreens {

    /** Every screen, in the default carousel order. All of them are on for a
     *  rider who has not chosen yet. */
    val DEFAULT: List<String> = listOf(
        "Dashboard", "Camera", "Telemetry",
        "Custom", "CustomCam", "MapNav", "Map", "Nav",
        "Power", "TripStats", "Compass", "Safety", "BigClock"
    )

    /**
     * The carousel for the rider's saved row order and enabled set, both
     * comma-separated ids and both blank until the rider touches them.
     *
     * A blank enabled set means every screen. A screen the saved order does
     * not name (it was saved before that screen existed) goes at the end,
     * which is where the settings list shows it. Never empty: the HUD reads
     * an empty list as "no frame yet" and keeps its waiting splash.
     */
    fun carousel(orderCsv: String, enabledCsv: String): List<String> {
        val saved = orderCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val order = saved + DEFAULT.filter { it !in saved }
        val enabled = enabledCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .toSet().ifEmpty { DEFAULT.toSet() }
        return order.filter { it in enabled }.ifEmpty { DEFAULT }
    }
}
