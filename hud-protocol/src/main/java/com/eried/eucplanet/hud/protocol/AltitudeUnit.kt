package com.eried.eucplanet.hud.protocol

/**
 * Metres or feet for a height: altitude, GPS accuracy, ascent and descent.
 *
 * Heights have their own unit, "m" or "ft", chosen by the rider beside the
 * speed, distance and temperature units. A rider on miles may well read
 * altitude in metres, as in the UK, so it is not taken from the distance
 * unit or the temperature unit.
 *
 * A rider from before the setting existed has none ([resolve] gets a blank).
 * They keep what they saw: heights follow their distance unit, feet for
 * miles or feet, metres for kilometres, metres and the Norwegian mile. An
 * older phone talking to a newer HUD is the same case.
 *
 * One rule for the phone, the Studio and the HUD.
 */
object AltitudeUnit {
    const val METRES = "m"
    const val FEET = "ft"
    const val FEET_PER_METRE = 3.28084f

    /** The unit heights read in for a rider who never chose one, from their
     *  [distanceUnit] ("km", "mi", "m", "ft" or "mil"). */
    fun fromDistance(distanceUnit: String): String =
        if (distanceUnit == "mi" || distanceUnit == "ft") FEET else METRES

    /** [altitudeUnit] when the rider chose one, else what their
     *  [distanceUnit] gives. Always "m" or "ft". */
    fun resolve(altitudeUnit: String, distanceUnit: String): String = when (altitudeUnit) {
        METRES, FEET -> altitudeUnit
        else -> fromDistance(distanceUnit)
    }

    /** [metres] in [unit], which is "m" or "ft" as [resolve] gives it. */
    fun value(metres: Float, unit: String): Float =
        if (unit == FEET) metres * FEET_PER_METRE else metres
}
