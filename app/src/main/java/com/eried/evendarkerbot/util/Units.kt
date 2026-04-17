package com.eried.evendarkerbot.util

object Units {
    fun speed(kmh: Float, imperial: Boolean): Float = if (imperial) kmh * 0.621371f else kmh
    fun distance(km: Float, imperial: Boolean): Float = if (imperial) km * 0.621371f else km
    fun temperature(celsius: Float, imperial: Boolean): Float = if (imperial) celsius * 9f / 5f + 32f else celsius

    fun speedUnit(imperial: Boolean): String = if (imperial) "mph" else "km/h"
    fun distanceUnit(imperial: Boolean): String = if (imperial) "mi" else "km"
    fun tempUnit(imperial: Boolean): String = if (imperial) "\u00B0F" else "\u00B0C"
}
