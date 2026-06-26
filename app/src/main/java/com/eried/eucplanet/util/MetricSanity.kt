package com.eried.eucplanet.util

/**
 * Generic plausibility guards for telemetry SHOWN to the rider. Telemetry is
 * always recorded RAW (exactly as the wheel reports it); these checks are applied
 * only at display / statistics time, so a bad sensor read -- e.g. an unused
 * InMotion temperature slot decoding to -176 C -- never reaches a trip-summary
 * stat or a dashboard tile.
 *
 * The window is deliberately wide and value-based, not a special-case blocklist:
 * a genuine 0 C reading in winter is kept (it is real, not an error), and only
 * physically impossible values are dropped. We deliberately do NOT try to guess
 * that a "stuck 0" means a dead sensor, because that is indistinguishable from a
 * real cold reading and would risk hiding valid data.
 */
object MetricSanity {

    /** Plausible EUC temperature window in Celsius. Motor / controller can run
     *  hot; ambient / battery can sit well below freezing. Outside this is a bad
     *  read (e.g. -176 from an empty sensor slot). */
    const val TEMP_MIN_C = -40f
    const val TEMP_MAX_C = 150f

    fun isPlausibleTempC(celsius: Float): Boolean =
        !celsius.isNaN() && celsius in TEMP_MIN_C..TEMP_MAX_C
}
