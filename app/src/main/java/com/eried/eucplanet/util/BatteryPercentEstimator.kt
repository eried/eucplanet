package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.BatteryPercentSettings
import kotlin.math.roundToInt

/**
 * Battery percentage worked out from pack voltage instead of taken from the
 * wheel.
 *
 * Some wheels, the KingSong S22 among them, report a percentage that disagrees
 * with their own display. The curves here are lithium chemistry rather than any
 * brand's arithmetic, so they apply to any wheel whose cell count is known.
 *
 * Strictly a display substitution: the caller replaces only the percentage it
 * shows, never the voltage, the raw frame, or anything sent to the wheel.
 *
 * Anything it cannot answer confidently returns the wheel's own number rather
 * than a guess: no voltage, no cell count, both options off. A wrong percentage
 * from us is worse than an imperfect one from the wheel, because a rider plans
 * a route on it.
 */
object BatteryPercentEstimator {

    /** Per-cell voltage treated as full on both curves. */
    private const val FULL_V_PER_CELL = 4.20f

    /**
     * [reportedPercent] is returned unchanged whenever an estimate would be
     * guesswork. [seriesCells] is the connected wheel's own count when its
     * model states one, otherwise the rider's setting.
     */
    fun estimate(
        voltage: Float,
        seriesCells: Int,
        settings: BatteryPercentSettings,
        reportedPercent: Int,
    ): Int {
        if (!settings.useWheelLogEnhanced && !settings.useCustomMinimumVoltage) return reportedPercent
        // A wheel that has not reported voltage yet reads 0, which would
        // otherwise come out as a confident 0%.
        if (voltage <= 0f) return reportedPercent
        if (seriesCells !in BatteryPercentSettings.SERIES_RANGE) return reportedPercent

        val cellVoltage = voltage / seriesCells
        return if (settings.useCustomMinimumVoltage) {
            customMinimum(cellVoltage, settings.minimumCellVoltageMv)
        } else {
            wheelLogEnhanced(cellVoltage)
        }
    }

    /**
     * A straight line from the rider's floor to full. Clamped rather than
     * trusted: the floor is also clamped, so a settings file carrying 4.5 V
     * cannot invert the scale.
     */
    private fun customMinimum(cellVoltage: Float, minimumMv: Int): Int {
        val minimum = minimumMv.coerceIn(
            BatteryPercentSettings.MIN_CELL_MV,
            BatteryPercentSettings.MAX_CELL_MV,
        ) / 1000f
        val span = FULL_V_PER_CELL - minimum
        if (span <= 0f) return 0
        return (((cellVoltage - minimum) * 100f) / span).toInt().coerceIn(0, 100)
    }

    /**
     * WheelLog's two-segment curve. The steep segment between 3.20 and 3.40 V
     * covers the knee where a pack empties quickly, so the number falls at a
     * rate that matches what the rider feels.
     */
    private fun wheelLogEnhanced(cellVoltage: Float): Int = when {
        cellVoltage > 4.175f -> 100
        cellVoltage > 3.40f -> ((cellVoltage - 3.325f) / 0.0085f).roundToInt().coerceIn(0, 100)
        cellVoltage > 3.20f -> ((cellVoltage - 3.20f) / 0.0225f).roundToInt().coerceIn(0, 100)
        else -> 0
    }
}
