package com.eried.eucplanet.platform

import kotlin.math.roundToLong

/**
 * The `java.lang.Math` calls shared code makes that kotlin.math has no twin
 * for, under the same name, so a moved file keeps `Math.toRadians(...)` and
 * only adds an import. Same constants as the JDK, so Android's numbers do not
 * move by a rounding step.
 */
object Math {
    private const val DEGREES_TO_RADIANS = 0.017453292519943295
    private const val RADIANS_TO_DEGREES = 57.29577951308232

    fun toRadians(degrees: Double): Double = degrees * DEGREES_TO_RADIANS
    fun toDegrees(radians: Double): Double = radians * RADIANS_TO_DEGREES

    const val PI: Double = kotlin.math.PI

    /** `Math.round(double)`: ties up, NaN is 0, the ends clamp to Long's range. */
    fun round(value: Double): Long = if (value.isNaN()) 0L else value.roundToLong()

    fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)
    fun abs(value: Double): Double = kotlin.math.abs(value)
    fun sqrt(value: Double): Double = kotlin.math.sqrt(value)
    fun cos(radians: Double): Double = kotlin.math.cos(radians)
}
