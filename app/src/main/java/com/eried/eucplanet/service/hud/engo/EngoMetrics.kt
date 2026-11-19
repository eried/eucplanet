package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.MetricRegistry
import com.eried.eucplanet.data.model.MetricUnitKind

/**
 * The readings the glasses can show, and how each one is titled.
 *
 * The keys are the app's metric catalog keys, so the phone names them with the
 * labels it uses everywhere else and their statistics come from the same
 * history buffers as the dashboard tiles. The glasses draw a small pixel font,
 * so each one gets a short upper-case title here, its unit included.
 */
object EngoMetrics {

    /** The rider's units, as the setting codes ("kmh", "C", "km", ...). */
    data class Units(
        val speed: String = "kmh",
        val temp: String = "C",
        val distance: String = "km",
        val pressure: String = "kpa",
        val altitude: String = "",
    ) {
        val speedLabel: String get() = when (speed) {
            "mph" -> "MPH"; "ms" -> "M/S"; "kn" -> "KN"; else -> "KM/H"
        }
        val tempLabel: String get() = when (temp) { "F" -> "°F"; "K" -> "K"; else -> "°C" }
        val distLabel: String get() = if (distance == "mi") "MI" else "KM"
    }

    /** A metric's short tag (used after a statistic) and its title when shown as is. */
    private class Tag(val short: String, val now: (Units) -> String)

    // A short tag follows a statistic word ("MAX PWM"), so it is three letters
    // at most: a column is 86 px, 43 font cells at the label size.
    private val TAGS: LinkedHashMap<String, Tag> = linkedMapOf(
        "SPEED" to Tag("SPD") { it.speedLabel },
        "GPS_SPEED" to Tag("GPS") { "GPS" },
        "LOAD" to Tag("PWM") { "PWM %" },
        "BATTERY" to Tag("BAT") { "BAT %" },
        "VOLTAGE" to Tag("V") { "VOLT" },
        "CURRENT" to Tag("A") { "AMPS" },
        "PHASE_CURRENT" to Tag("PH A") { "PH A" },
        "MOTOR_POWER" to Tag("W") { "MOT W" },
        "BATTERY_POWER" to Tag("BW") { "BAT W" },
        "TEMPERATURE" to Tag("TMP°") { "TMP" + it.tempLabel },
        "MOTOR_TEMP" to Tag("MOT°") { "MOT" + it.tempLabel },
        "CONTROLLER_TEMP" to Tag("CTL°") { "CTL" + it.tempLabel },
        "BATTERY_TEMP" to Tag("BAT°") { "BAT" + it.tempLabel },
        "TRIP" to Tag("TRP") { "TRIP " + it.distLabel },
        "RANGE_ESTIMATE" to Tag("RNG") { "RNG " + it.distLabel },
        "WH_PER_KM" to Tag("WH") { "WH/" + it.distLabel },
        "TORQUE" to Tag("NM") { "NM" },
        "PITCH" to Tag("PIT") { "PITCH" },
        "ROLL" to Tag("ROL") { "ROLL" },
        "TIRE_PRESSURE" to Tag("TYR") { "TYRE" },
        "GPS_ALTITUDE" to Tag("ALT") { "ALT" },
        "PHONE_BATTERY" to Tag("PHN") { "PHONE %" },
    )

    /** The rider's units from their settings, as the rest of the app resolves them. */
    fun units(s: com.eried.eucplanet.data.model.AppSettings): Units = Units(
        speed = com.eried.eucplanet.util.Units.effectiveSpeedUnit(s),
        temp = com.eried.eucplanet.util.Units.effectiveTempUnit(s),
        distance = com.eried.eucplanet.util.Units.effectiveDistanceUnit(s),
        pressure = com.eried.eucplanet.util.Units.effectivePressureUnit(s),
        altitude = com.eried.eucplanet.util.Units.effectiveAltitudeUnit(s),
    )

    /** What the pickers offer, in order. */
    val KEYS: List<String> = TAGS.keys.toList()

    /** The statistics the pickers offer, DashboardStat names. */
    val STATS: List<String> = listOf("CURRENT", "MAX", "MIN", "AVG", "SUSTAINED_PEAK")

    private val STAT_WORDS = mapOf("MAX" to "MAX", "MIN" to "MIN", "AVG" to "AVG", "SUSTAINED_PEAK" to "PK")

    fun known(key: String): Boolean = key in TAGS

    /** The title over a reading, e.g. "PWM %", "MAX PWM", "TOP" for the top speed. */
    fun title(key: String, stat: String, units: Units): String {
        val tag = TAGS[key] ?: return key.take(5)
        if (stat == "MAX" && (key == "SPEED" || key == "GPS_SPEED")) return "TOP"
        val word = STAT_WORDS[stat] ?: return tag.now(units)
        return "$word ${tag.short}"
    }

    /** Whether the bar and the graph run on a fixed 0..100 scale. */
    fun isPercent(key: String): Boolean =
        MetricRegistry.byKey(key)?.unit == MetricUnitKind.PERCENT

    fun isTemperature(key: String): Boolean =
        MetricRegistry.byKey(key)?.unit == MetricUnitKind.TEMPERATURE

    /**
     * The number the glasses draw: digits, sign, point (and a colon for a
     * clock), taken from the app's own formatted value so the rounding and
     * the rider's units are the tile's. "58°C" becomes "58", "83.5V" "83.5".
     */
    fun digits(formatted: String): String {
        val n = Regex("-?\\d+(?:[.:]\\d+)?").find(formatted.replace(',', '.'))?.value ?: return "--"
        // A decimal after three digits is noise at a glance: 112.0 A reads 112.
        val whole = n.substringBefore('.')
        return if ('.' in n && whole.trimStart('-').length >= 3) whole else n
    }
}
