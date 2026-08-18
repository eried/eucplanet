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
     * Cells in series for a pack that is [nominalVoltage] when fully charged.
     *
     * Every EUC pack is a whole number of lithium cells at 4.2 V, so the
     * charged voltage each family already records for its models gives the
     * count with no per-brand table: 67 V is 16S, 84 V is 20S, 100 V is 24S,
     * 126 V is 30S, 151 V is 36S, 176 V is 42S. VeteranModel has resolved its
     * BMS cell count this way from the start; this is the same arithmetic in
     * one place so every family gets it.
     */
    fun seriesCellsFor(nominalVoltage: Int): Int =
        (nominalVoltage / FULL_V_PER_CELL).roundToInt()

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
        if (settings.mode == BatteryPercentSettings.MODE_WHEEL) return reportedPercent
        // A wheel that has not reported voltage yet reads 0, which would
        // otherwise come out as a confident 0%.
        if (voltage <= 0f) return reportedPercent
        if (seriesCells !in BatteryPercentSettings.SERIES_RANGE) return reportedPercent

        val cellVoltage = voltage / seriesCells
        return when (settings.mode) {
            BatteryPercentSettings.MODE_CUSTOM ->
                customLinear(
                    cellVoltage,
                    settings.minimumCellVoltageMv,
                    settings.maximumCellVoltageMv,
                )
            BatteryPercentSettings.MODE_CURVE -> lithiumCurve(cellVoltage)
            // A mode we do not recognise is not a licence to invent a number.
            else -> reportedPercent
        }
    }

    /**
     * A straight line from the rider's empty point to the rider's full point.
     * Both ends are clamped, so a settings file carrying nonsense (a full below
     * the empty) cannot invert the scale.
     */
    private fun customLinear(cellVoltage: Float, minimumMv: Int, maximumMv: Int): Int {
        val minimum = minimumMv.coerceIn(
            BatteryPercentSettings.MIN_CELL_MV,
            BatteryPercentSettings.MAX_CELL_MV,
        ) / 1000f
        val maximum = maximumMv.coerceIn(
            BatteryPercentSettings.MIN_FULL_MV,
            BatteryPercentSettings.MAX_FULL_MV,
        ) / 1000f
        val span = maximum - minimum
        if (span <= 0f) return 0
        return (((cellVoltage - minimum) * 100f) / span).toInt().coerceIn(0, 100)
    }

    /**
     * A lithium cell's discharge shape as two straight segments. The steep one
     * between 3.20 and 3.40 V covers the knee where a pack empties quickly, so
     * the number falls at a rate that matches what the rider feels. Same
     * endpoints WheelLog uses for its "enhanced" reading, so riders coming
     * from it see the number they are used to.
     */
    private fun lithiumCurve(cellVoltage: Float): Int = when {
        cellVoltage > 4.175f -> 100
        cellVoltage > 3.40f -> ((cellVoltage - 3.325f) / 0.0085f).roundToInt().coerceIn(0, 100)
        cellVoltage > 3.20f -> ((cellVoltage - 3.20f) / 0.0225f).roundToInt().coerceIn(0, 100)
        else -> 0
    }
}
