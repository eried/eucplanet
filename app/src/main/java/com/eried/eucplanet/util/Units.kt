package com.eried.eucplanet.util

import android.content.Context
import com.eried.eucplanet.R

object Units {
    fun speed(kmh: Float, imperial: Boolean): Float = if (imperial) kmh * 0.621371f else kmh
    fun distance(km: Float, imperial: Boolean): Float = if (imperial) km * 0.621371f else km
    fun temperature(celsius: Float, imperial: Boolean): Float = if (imperial) celsius * 9f / 5f + 32f else celsius

    /**
     * Localized speed unit. Norwegian uses "km/t", Dutch "km/u", Russian "\u043A\u043C/\u0447",
     * etc., so we route through string resources rather than hardcoding "km/h".
     */
    fun speedUnit(context: Context, imperial: Boolean): String =
        if (imperial) context.getString(R.string.unit_mph) else context.getString(R.string.unit_kmh)

    fun distanceUnit(imperial: Boolean): String = if (imperial) "mi" else "km"
    fun tempUnit(imperial: Boolean): String = if (imperial) "\u00B0F" else "\u00B0C"
}
