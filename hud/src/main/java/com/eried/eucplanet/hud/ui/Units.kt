package com.eried.eucplanet.hud.ui

import com.eried.eucplanet.hud.protocol.HudState
import kotlin.math.round

/**
 * Pure-HUD unit conversion helpers.
 *
 * The wire format ships canonical metric (km/h, °C, km); the HUD converts to
 * the rider's chosen units locally using the codes in [HudState.unitSpeed],
 * [HudState.unitDistance], [HudState.unitTemp]. Mirrors the phone's `Units.kt`
 * but with a much smaller surface — the HUD never needs to format barometric
 * pressure or compute energy density, just render the dashboard tiles.
 *
 * Kept here (in the HUD module) on purpose: the phone's `Units.kt` pulls in
 * a string-resource lookup for unit suffixes, which is wrong for the HUD's
 * fixed font/sizing.
 */
object HudUnits {

    fun speed(kmh: Float, code: String): Float = when (code) {
        "mph" -> kmh * 0.6213712f
        "ms" -> kmh / 3.6f
        "kn" -> kmh * 0.5399568f
        else -> kmh
    }

    fun speedSuffix(code: String): String = when (code) {
        "mph" -> "mph"
        "ms" -> "m/s"
        "kn" -> "kn"
        else -> "km/h"
    }

    fun distance(km: Float, code: String): Float = when (code) {
        "mi" -> km * 0.6213712f
        "m" -> km * 1000f
        "ft" -> km * 3280.84f
        "mil" -> km / 10f
        else -> km
    }

    fun distanceSuffix(code: String): String = when (code) {
        "mi" -> "mi"
        "m" -> "m"
        "ft" -> "ft"
        "mil" -> "mil"
        else -> "km"
    }

    fun temperature(c: Float, code: String): Float = when (code) {
        "F" -> c * 9f / 5f + 32f
        "K" -> c + 273.15f
        else -> c
    }

    fun temperatureSuffix(code: String): String = when (code) {
        "F" -> "°F"
        "K" -> "K"
        else -> "°C"
    }

    /** Round to N decimals as a Float (rendering uses %.Nf, this is only for
     *  intermediate comparison / threshold checks). */
    fun round1(v: Float): Float = round(v * 10f) / 10f
}
