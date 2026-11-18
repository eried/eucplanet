package com.eried.eucplanet.data.store

/**
 * The units a fresh install starts in, from where the phone is.
 *
 * Two signals, both needed: the country of the mobile network and the
 * country of the phone's region setting. One alone is not enough. A
 * Norwegian who sets the phone to "English (United States)" has the region
 * but rides on a Norwegian network, and must start metric. A missing signal
 * (a Wi-Fi tablet, an emulator with no SIM) counts as metric: wrong toward
 * metric is one tap to fix, wrong toward miles on the wrong continent is a
 * rider reading the wrong speed.
 *
 * Three outcomes, the same ones the web sites pick:
 *  - miles, Fahrenheit, heights in feet: the United States, its territories
 *    and Liberia
 *  - miles, Celsius, heights in metres: the United Kingdom, the Crown
 *    Dependencies and Myanmar
 *  - kilometres, Celsius, heights in metres: everywhere else
 *
 * Only a first run asks. A rider who already has settings is never moved.
 */
object DefaultUnits {

    data class Units(val speed: String, val distance: String, val temp: String, val altitude: String)

    val METRIC = Units("kmh", "km", "C", "m")
    val MILES_CELSIUS = Units("mph", "mi", "C", "m")
    val IMPERIAL = Units("mph", "mi", "F", "ft")

    /** Miles and Fahrenheit. */
    private val FAHRENHEIT = setOf("US", "PR", "GU", "VI", "AS", "MP", "UM", "LR")

    /** Miles on the road, Celsius for the weather. */
    private val MILES_ONLY = setOf("GB", "JE", "GG", "IM", "MM")

    /**
     * The starting units for a phone on a [networkCountry] network whose
     * region setting is [regionCountry], both ISO country codes in any case,
     * blank when unknown.
     */
    fun pick(networkCountry: String, regionCountry: String): Units {
        val network = networkCountry.trim().uppercase()
        val region = regionCountry.trim().uppercase()
        val miles = FAHRENHEIT + MILES_ONLY
        return when {
            network in FAHRENHEIT && region in FAHRENHEIT -> IMPERIAL
            // A British phone roaming in the States, or the other way round:
            // both say miles, they disagree on the weather, Celsius it is.
            network in miles && region in miles -> MILES_CELSIUS
            else -> METRIC
        }
    }
}
