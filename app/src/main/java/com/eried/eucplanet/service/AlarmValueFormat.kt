package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.util.Units
import kotlin.math.roundToInt

// How an alarm's reading becomes the number a rider reads or hears. The rule
// editor and the alarm engine both come here, so the voice preview and the
// alarm that actually fires say the same thing.

/**
 * Metrics whose useful range is too short for whole numbers are edited in
 * tenths.
 *
 * The stepper holds an Int. A tyre runs about 1 to 4 bar and g-force about 0
 * to 3, so whole units would offer a rider four settings across the entire
 * range they care about. For those the displayed number is ten times the
 * reading and the stepper's format/parse put the point back. psi is 15 to
 * 60 and needs no help, which is why pressure's scale depends on the unit and
 * g-force's does not.
 */
internal fun displayScale(metric: AlarmMetric, pressureUnit: String): Float = when (metric) {
    // Each pressure unit needs its own scale, not just bar. A tyre tops out
    // near 5 bar, 5.1 kgf/cm2 or 0.5 MPa, so in MPa whole numbers gave the
    // rider a range of nought to nought and a stepper that would not move at
    // all. The scale is whatever it takes to keep the useful range about fifty
    // steps wide.
    AlarmMetric.TIRE_PRESSURE -> when (pressureUnit) {
        "bar", "kgf" -> 10f
        "mpa" -> 100f
        else -> 1f      // psi and kPa are already wide enough
    }
    AlarmMetric.G_FORCE, AlarmMetric.LATERAL_G -> 10f
    else -> 1f
}

/** Decimals implied by [displayScale], for formatting and parsing. */
internal fun displayDecimals(metric: AlarmMetric, pressureUnit: String): Int =
    when (displayScale(metric, pressureUnit)) {
        100f -> 2
        10f -> 1
        else -> 0
    }

/**
 * A reading as the rider reads it, decimals and all, in their units.
 *
 * The scale is an editing device, never something to show: printed raw, a
 * three bar rule reads "30" and a 1.5 g rule "15". The decimal point is always
 * a dot, because the text also goes to the voice and some voices in
 * comma-decimal locales read "3,0" as "three zero".
 */
internal fun formatThreshold(
    metric: AlarmMetric,
    valueInternal: Float,
    speedUnit: String,
    tempUnit: String,
    pressureUnit: String,
): String {
    val shown = displayThreshold(metric, valueInternal, speedUnit, tempUnit, pressureUnit) /
        displayScale(metric, pressureUnit)
    val decimals = displayDecimals(metric, pressureUnit)
    return if (decimals == 0) shown.roundToInt().toString()
    else String.format(java.util.Locale.US, "%.${decimals}f", shown)
}

/**
 * The stepper's integer for a reading: the rider's unit times [displayScale].
 * Use [formatThreshold] for anything a rider reads or hears.
 */
internal fun displayThreshold(
    metric: AlarmMetric,
    valueInternal: Float,
    speedUnit: String,
    tempUnit: String,
    pressureUnit: String,
): Float =
    when (metric) {
        AlarmMetric.SPEED, AlarmMetric.GPS_SPEED, AlarmMetric.EXTERNAL_GPS_SPEED -> Units.speed(valueInternal, speedUnit)
        // The three sensors convert exactly like the metric they were hiding in.
        AlarmMetric.TEMPERATURE,
        AlarmMetric.MOTOR_TEMP,
        AlarmMetric.CONTROLLER_TEMP,
        AlarmMetric.BATTERY_TEMP -> Units.temperature(valueInternal, tempUnit)
        AlarmMetric.TIRE_PRESSURE ->
            Units.pressure(valueInternal, pressureUnit) * displayScale(metric, pressureUnit)
        AlarmMetric.G_FORCE, AlarmMetric.LATERAL_G ->
            valueInternal * displayScale(metric, pressureUnit)
        else -> valueInternal
    }

/**
 * What a voice alarm says for {value} and {threshold}: the reading of the
 * rule's own metric in the rider's units. [metricName] is the stored
 * [com.eried.eucplanet.data.model.AlarmRule.metric]; one that is no longer a
 * known metric is spoken as the plain rounded number.
 */
internal fun spokenAlarmValue(
    metricName: String,
    valueInternal: Float,
    speedUnit: String,
    tempUnit: String,
    pressureUnit: String,
): String {
    val metric = runCatching { AlarmMetric.valueOf(metricName) }.getOrNull()
        ?: return valueInternal.roundToInt().toString()
    return formatThreshold(metric, valueInternal, speedUnit, tempUnit, pressureUnit)
}
