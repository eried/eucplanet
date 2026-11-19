package com.eried.eucplanet.service.hud.engo

import java.util.Locale

/**
 * The numbers the rider can put in the two bottom corners of the ride page.
 *
 * The keys are the app's metric keys, so the phone names them with the same
 * labels it uses everywhere else. The glasses draw ASCII only, so each one
 * gets a short ASCII tag here.
 */
object EngoMetrics {
    const val NONE = "NONE"
    const val BATTERY = "BATTERY"
    const val TEMPERATURE = "TEMPERATURE"
    const val VOLTAGE = "VOLTAGE"
    const val CURRENT = "CURRENT"
    const val TRIP = "TRIP"

    /** What the pickers offer, in order. [NONE] leaves the corner empty. */
    val KEYS: List<String> = listOf(BATTERY, TEMPERATURE, VOLTAGE, CURRENT, TRIP)

    /** [key] when the glasses can show it, else [fallback]. */
    fun known(key: String, fallback: String): String =
        if (key == NONE || key in KEYS) key else fallback

    /**
     * The corner for [key], already formatted. Values arrive in the rider's
     * units; [tempUnit] and [distanceUnit] are their ASCII names.
     */
    fun cell(
        key: String,
        batteryPct: Int,
        temp: Int,
        tempUnit: String,
        voltage: Float,
        current: Float,
        trip: Float,
        distanceUnit: String,
    ): EngoCell = when (key) {
        BATTERY -> EngoCell("BATT", "$batteryPct%")
        TEMPERATURE -> EngoCell("TEMP", "$temp$tempUnit")
        VOLTAGE -> EngoCell("VOLT", String.format(Locale.US, "%.1fV", voltage))
        CURRENT -> EngoCell("AMPS", String.format(Locale.US, "%.0fA", current))
        TRIP -> EngoCell("TRIP", String.format(Locale.US, "%.1f%s", trip, distanceUnit))
        else -> EngoCell.EMPTY
    }
}
