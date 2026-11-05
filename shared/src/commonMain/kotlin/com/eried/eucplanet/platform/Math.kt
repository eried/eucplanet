package com.eried.eucplanet.platform

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
}
