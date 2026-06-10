package com.eried.eucplanet.util

/**
 * Pure unit conversion + display labels for the shared (iOS) UI.
 *
 * Mirrors the conversion math of the Android `com.eried.eucplanet.util.Units`
 * object, but stands alone under a different name on purpose: the Android `Units`
 * still lives in `:app`, and reusing the same fully-qualified name here would
 * collide on the shared classpath (split-package modules can share a package but
 * not a class). Labels are plain English — iOS has no string-resource layer yet,
 * so the Android localized variants (km/t, km/u, км/ч) aren't ported in v1.
 *
 * Convention: stored telemetry is ALWAYS metric (km/h, km, °C); these convert to
 * the rider's chosen display unit at render time. Unit keys match Android's:
 * speed "kmh|mph|ms|kn", distance "km|mi|m|ft|mil", temperature "C|F|K".
 */
object UnitFormat {
    fun speed(kmh: Float, unit: String): Float = when (unit) {
        "mph" -> kmh * 0.621371f
        "ms" -> kmh / 3.6f
        "kn" -> kmh / 1.852f
        else -> kmh
    }

    fun distance(km: Float, unit: String): Float = when (unit) {
        "mi" -> km * 0.621371f
        "m" -> km * 1000f
        "ft" -> km * 3280.84f
        "mil" -> km / 10f
        else -> km
    }

    fun temperature(celsius: Float, unit: String): Float = when (unit) {
        "F" -> celsius * 9f / 5f + 32f
        "K" -> celsius + 273.15f
        else -> celsius
    }

    fun speedLabel(unit: String): String = when (unit) {
        "mph" -> "mph"
        "ms" -> "m/s"
        "kn" -> "kn"
        else -> "km/h"
    }

    fun distanceLabel(unit: String): String = when (unit) {
        "mi" -> "mi"
        "m" -> "m"
        "ft" -> "ft"
        "mil" -> "mil"
        else -> "km"
    }

    fun tempLabel(unit: String): String = when (unit) {
        "F" -> "°F"
        "K" -> "K"
        else -> "°C"
    }
}
