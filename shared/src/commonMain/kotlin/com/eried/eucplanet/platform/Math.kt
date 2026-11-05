package com.eried.eucplanet.platform

import kotlin.math.PI

/**
 * The `java.lang.Math` calls shared code makes that kotlin.math has no twin
 * for, under the same name, so a moved file keeps `Math.toRadians(...)` and
 * only adds an import.
 */
object Math {
    fun toRadians(degrees: Double): Double = degrees / 180.0 * PI
}
