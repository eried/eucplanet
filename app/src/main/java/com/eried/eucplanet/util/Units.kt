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

    /** Converts a stored kPa tire-pressure value to the chosen unit ("psi" or "bar"). */
    fun pressure(kpa: Float, unit: String): Float = when (unit) {
        "psi" -> kpa * 0.145038f
        "bar" -> kpa / 100f
        // Both are bar in disguise, and both are printed on real gauges:
        // kgf/cm2 sits beside psi on a lot of them, and MPa is what Asian
        // gauges and most EUC pumps read in. 1 bar is 1.0197 kgf/cm2 and
        // exactly 0.1 MPa.
        "kgf" -> kpa / 98.0665f
        "mpa" -> kpa / 1000f
        else -> kpa
    }

    /** psi floored to 1 decimal. The wheel transmits whole kPa and its own
     *  display truncates the psi conversion, so rounding read 0.1 psi high vs
     *  the wheel. Floor here so EUC Planet matches what the rider sees on the
     *  wheel (e.g. 210 kPa -> 30.458 -> 30.4, not 30.5). */
    fun pressurePsiFloored(kpa: Float): Float =
        kotlin.math.floor(pressure(kpa, "psi") * 10f) / 10f

    /** Back to kPa from the rider's pressure unit, for a threshold they typed. */
    fun pressureToKpa(value: Float, unit: String): Float = when (unit) {
        "psi" -> value / 0.145038f
        "bar" -> value * 100f
        // Both were added to pressure() and forgotten here, so a threshold
        // typed in kgf/cm2 or MPa was stored as though it had been kPa: a
        // 2.5 kgf alarm became 2.5 kPa and never fired.
        "kgf" -> value * 98.0665f
        "mpa" -> value * 1000f
        else -> value
    }

    /** Tire-pressure unit symbol for "psi" or "bar" (kPa otherwise). */
    fun pressureUnit(unit: String): String = when (unit) {
        "psi" -> "psi"
        "bar" -> "bar"
        "kgf" -> "kgf/cm\u00B2"
        "mpa" -> "MPa"
        else -> "kPa"
    }

    /**
     * Effective tire-pressure unit. No dedicated per-metric setting yet, so it
     * follows the rider's distance unit: imperial (mi) -> psi, everything else
     * -> bar. A standalone pressure-unit picker can be added later.
     */
    /**
     * The rider's pressure unit.
     *
     * A setting now, not a guess. It used to be derived from the distance
     * unit, which got the common case wrong: EUC riders run psi in a tyre
     * while measuring everything else in kilometres, and there is no
     * relationship between the two beyond an assumption that was usually
     * false.
     */
    fun effectivePressureUnit(s: AppSettings): String =
        s.tpms.pressureUnit.ifBlank {
            if (effectiveDistanceUnit(s) == "mi") "psi" else "bar"
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

    /**
     * Human-readable elapsed time for trip summaries: "1h 23m" once past an
     * hour, "23m 45s" under an hour, "45s" under a minute. Seconds are dropped
     * once hours appear (they are noise on a long ride).
     * TODO(i18n): localise the h/m/s suffixes in the global strings pass.
     */
    fun humanDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            m > 0 -> "%dm %02ds".format(m, sec)
            else -> "%ds".format(sec)
        }
    }
}
