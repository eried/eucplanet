package com.eried.eucplanet.nav

/**
 * Whether a fix counts as riding, and whether a trace moved far enough to
 * give a heading. A connected wheel is the only judge of moving; without one
 * a GPS speed counts only on an accurate fix, so standing still with noisy
 * GPS does not end "Start riding".
 */
object NavMotion {
    /** Past this accuracy a GPS speed is noise, not riding. */
    private const val GPS_MOVING_MAX_ACCURACY_M = 20f

    /** [accuracyM] is null when the fix has none. */
    fun isMoving(
        wheelConnected: Boolean,
        wheelKmh: Double,
        movingKmh: Double,
        gpsSpeedMs: Double,
        movingMs: Double,
        accuracyM: Float?,
    ): Boolean {
        if (wheelConnected) return wheelKmh > movingKmh
        val accurate = accuracyM == null || accuracyM <= GPS_MOVING_MAX_ACCURACY_M
        return gpsSpeedMs > movingMs && accurate
    }

    /**
     * The net displacement must clear [minDispM] and the larger known accuracy
     * of the first and last fix. NaN or 0 means unknown.
     */
    fun headingDispOk(dispM: Double, minDispM: Double, firstAccM: Float, lastAccM: Float): Boolean {
        val known = listOf(firstAccM, lastAccM).filter { !it.isNaN() && it > 0f }
        val needed = maxOf(minDispM, known.maxOrNull()?.toDouble() ?: 0.0)
        return dispM >= needed
    }
}
