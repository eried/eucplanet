package com.eried.eucplanet.util

data class GraphBounds(val min: Float, val max: Float) {
    val range: Float get() = (max - min).coerceAtLeast(0.0001f)
}

object GraphScale {
    // Minimum visible span per metric so a nearly-flat line doesn't fill the chart.
    const val SPAN_TEMPERATURE_C = 20f
    const val SPAN_TEMPERATURE_F = 36f
    const val SPAN_VOLTAGE = 5f
    const val SPAN_BATTERY = 20f
    const val SPAN_SPEED_KMH = 10f
    const val SPAN_SPEED_MPH = 6f
    const val SPAN_CURRENT = 10f
    const val SPAN_LOAD = 20f

    fun pad(dataMin: Float, dataMax: Float, minSpan: Float, relPadding: Float = 0.10f): GraphBounds {
        val rawRange = dataMax - dataMin
        return if (rawRange >= minSpan) {
            val pad = rawRange * relPadding
            GraphBounds(dataMin - pad, dataMax + pad)
        } else {
            val mid = (dataMin + dataMax) / 2f
            GraphBounds(mid - minSpan / 2f, mid + minSpan / 2f)
        }
    }

    fun absolute(dataMin: Float, dataMax: Float, pad: Float): GraphBounds =
        GraphBounds(dataMin - pad, dataMax + pad)

    fun fixed(min: Float, max: Float): GraphBounds = GraphBounds(min, max)
}
