package com.eried.eucplanet.hud.protocol

/**
 * Metres or feet for a height: altitude, GPS accuracy, ascent and descent.
 *
 * There is no altitude setting. A height follows the rider's distance unit,
 * since both are lengths: feet for a rider on miles or on feet, metres for
 * kilometres, metres and the Norwegian mile. Temperature has no say in it,
 * so a rider on miles and Celsius reads feet.
 *
 * One rule for the phone, the Studio and the HUD. It used to be written out
 * in each place as "miles means feet", which left a rider who picked feet as
 * their distance unit reading metres.
 */
object AltitudeUnit {
    const val FEET_PER_METRE = 3.28084f

    /** Whether heights read in feet for a rider on [distanceUnit]
     *  ("km", "mi", "m", "ft" or "mil"). */
    fun inFeet(distanceUnit: String): Boolean = distanceUnit == "mi" || distanceUnit == "ft"

    /** "ft" or "m". */
    fun suffix(distanceUnit: String): String = if (inFeet(distanceUnit)) "ft" else "m"

    /** [metres] in the unit [suffix] names. */
    fun value(metres: Float, distanceUnit: String): Float =
        if (inFeet(distanceUnit)) metres * FEET_PER_METRE else metres
}
