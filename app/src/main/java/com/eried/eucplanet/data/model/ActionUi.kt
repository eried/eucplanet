package com.eried.eucplanet.data.model

/**
 * UI-layer hooks for the catalog actions that need handles the service-layer
 * [com.eried.eucplanet.flic.FlicManager] doesn't have — navigation, the
 * settings writer, a snackbar. These are exactly the dashboard-only actions
 * (`OPEN_*`, `TOGGLE_UNITS`, `MUTE_ALARMS`, `RESET_TRIP`).
 *
 * Implemented by every surface that lives inside the Compose tree: the
 * dashboard action tiles and the service-mode debug overlay. Physical
 * surfaces (Flic / volume keys / watch) never implement it — they only ever
 * bind eyes-free actions, which route through [dispatchAction]'s `fallback`.
 */
interface ActionUi {
    fun openNavigation()
    fun openStudio()
    fun openAbout()
    fun openService()
    fun openTrips()
    fun toggleUnits()
    fun toggleAlarmsMuted()
    fun resetTrip()
}

/**
 * The single surface→behavior mapping for catalog actions. Dashboard-only
 * keys are delegated to [ui]; every other key (eyes-free, custom BLE) is
 * handed to [fallback] — the shared physical-surface path that Flic and the
 * volume keys already use ([com.eried.eucplanet.flic.FlicManager.dispatchActionByName]).
 *
 * This replaces the duplicate `when (key)` that used to live in BOTH
 * `DashboardScreen` and `FlicManager`, so a debug surface can fire the full
 * catalog instead of silently no-op'ing the dashboard-only half.
 */
fun dispatchAction(key: String, ui: ActionUi, fallback: (String) -> Unit) {
    when (key) {
        "OPEN_NAVIGATION" -> ui.openNavigation()
        "OPEN_STUDIO" -> ui.openStudio()
        "OPEN_ABOUT" -> ui.openAbout()
        "OPEN_SERVICE" -> ui.openService()
        "OPEN_TRIPS" -> ui.openTrips()
        "TOGGLE_UNITS" -> ui.toggleUnits()
        "MUTE_ALARMS" -> ui.toggleAlarmsMuted()
        "RESET_TRIP" -> ui.resetTrip()
        else -> fallback(key)
    }
}
