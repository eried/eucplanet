package com.eried.eucplanet.util

import android.content.Context
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AppSettings

object Units {
    /**
     * Converts a stored km/h value to the user's chosen speed unit.
     * [unit] is "kmh", "mph" or "ms".
     */
    fun speed(kmh: Float, unit: String): Float = when (unit) {
        "mph" -> kmh * 0.621371f
        "ms" -> kmh / 3.6f
        "kn" -> kmh / 1.852f
        else -> kmh
    }

    /**
     * Converts a stored km value to the user's chosen distance unit.
     * [unit] is "km", "mi" or "m".
     */
    fun distance(km: Float, unit: String): Float = when (unit) {
        "mi" -> km * 0.621371f
        "m" -> km * 1000f
        "ft" -> km * 3280.84f
        "mil" -> km / 10f
        else -> km
    }

    /**
     * Reverse of [speed]: takes a value already in [unit] and returns km/h.
     * Used by sliders / alarm thresholds where the user types in display units.
     */
    fun speedToKmh(value: Float, unit: String): Float = when (unit) {
        "mph" -> value / 0.621371f
        "ms" -> value * 3.6f
        "kn" -> value * 1.852f
        else -> value
    }

    /**
     * Reverse of [distance]: takes a value already in [unit] and returns km.
     */
    fun distanceToKm(value: Float, unit: String): Float = when (unit) {
        "mi" -> value / 0.621371f
        "m" -> value / 1000f
        "ft" -> value / 3280.84f
        "mil" -> value * 10f
        else -> value
    }

    /**
     * Converts a stored °C value to the user's chosen temperature unit.
     * [unit] is "C", "F" or "K".
     */
    fun temperature(celsius: Float, unit: String): Float = when (unit) {
        "F" -> celsius * 9f / 5f + 32f
        "K" -> celsius + 273.15f
        else -> celsius
    }

    /**
     * Reverse of [temperature]: takes a value already in [unit] and returns °C.
     * Used by the alarm-threshold input where the user types in display units.
     */
    fun temperatureToCelsius(value: Float, unit: String): Float = when (unit) {
        "F" -> (value - 32f) * 5f / 9f
        "K" -> value - 273.15f
        else -> value
    }

    /**
     * Localized speed unit. Norwegian uses "km/t", Dutch "km/u", Russian "км/ч",
     * etc., so we route through string resources rather than hardcoding "km/h".
     * [unit] is "kmh", "mph" or "ms".
     */
    fun speedUnit(context: Context, unit: String): String = when (unit) {
        "mph" -> context.getString(R.string.unit_mph)
        "ms" -> context.getString(R.string.unit_ms)
        "kn" -> context.getString(R.string.unit_kn)
        else -> context.getString(R.string.unit_kmh)
    }

    /** Distance unit symbol for "km", "mi" or "m". */
    fun distanceUnit(unit: String): String = when (unit) {
        "mi" -> "mi"
        "m" -> "m"
        "ft" -> "ft"
        "mil" -> "mil"
        else -> "km"
    }

    /** Temperature unit symbol for "C", "F" or "K". */
    fun tempUnit(unit: String): String = when (unit) {
        "F" -> "°F"
        "K" -> "K"
        else -> "°C"
    }

    /** The three top-level measurement-unit modes. CUSTOM is a derived label. */
    enum class UnitSystem { METRIC, IMPERIAL, CUSTOM }

    /**
     * The effective speed unit ("kmh", "mph" or "ms"). A blank [AppSettings.unitSpeed]
     * is the pre-migration value: fall back to the legacy [AppSettings.imperialUnits] flag.
     */
    fun effectiveSpeedUnit(s: AppSettings): String =
        if (s.unitSpeed.isBlank()) (if (s.imperialUnits) "mph" else "kmh") else s.unitSpeed

    /**
     * The effective distance unit ("km", "mi" or "m"). A blank [AppSettings.unitDistance]
     * is the pre-migration value: fall back to the legacy [AppSettings.imperialUnits] flag.
     */
    fun effectiveDistanceUnit(s: AppSettings): String =
        if (s.unitDistance.isBlank()) (if (s.imperialUnits) "mi" else "km") else s.unitDistance

    /**
     * The effective temperature unit ("C", "F" or "K"). A blank [AppSettings.unitTemp]
     * is the pre-migration value: fall back to the legacy [AppSettings.imperialUnits] flag.
     */
    fun effectiveTempUnit(s: AppSettings): String =
        if (s.unitTemp.isBlank()) (if (s.imperialUnits) "F" else "C") else s.unitTemp

    /**
     * Derives the top-level [UnitSystem] label from the three per-unit choices:
     * all metric -> METRIC, all imperial -> IMPERIAL, any other mix (including
     * m/s or meters) -> CUSTOM.
     */
    fun unitSystemOf(s: AppSettings): UnitSystem {
        val speed = effectiveSpeedUnit(s)
        val dist = effectiveDistanceUnit(s)
        val temp = effectiveTempUnit(s)
        return when {
            speed == "kmh" && dist == "km" && temp == "C" -> UnitSystem.METRIC
            speed == "mph" && dist == "mi" && temp == "F" -> UnitSystem.IMPERIAL
            else -> UnitSystem.CUSTOM
        }
    }
}
