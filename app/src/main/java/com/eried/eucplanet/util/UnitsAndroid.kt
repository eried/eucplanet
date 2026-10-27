package com.eried.eucplanet.util

import android.content.Context
import com.eried.eucplanet.R

/**
 * Localized speed unit. Norwegian uses "km/t", Dutch "km/u", Russian "км/ч",
 * etc., so we route through string resources rather than hardcoding "km/h".
 * [unit] is "kmh", "mph" or "ms".
 *
 * Lives in :app while the strings are Android resources; the rest of [Units]
 * is shared.
 */
fun Units.speedUnit(context: Context, unit: String): String = when (unit) {
    "mph" -> context.getString(R.string.unit_mph)
    "ms" -> context.getString(R.string.unit_ms)
    "kn" -> context.getString(R.string.unit_kn)
    else -> context.getString(R.string.unit_kmh)
}
