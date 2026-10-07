package com.eried.eucplanet.ui.recording

/**
 * Pure math for the trip charts' shared zoom window.
 *
 * The window is a fraction range of the (already trimmed) ride, 0f..1f when
 * fully zoomed out. Every chart on the screen renders the same window, so a
 * pinch on any of them zooms them all, and the scrub cursor keeps meaning the
 * same moment everywhere.
 */
object ChartWindow {

    /** Narrowest visible slice: 2% of the ride, enough that a pinch cannot
     *  zoom into a single sample and strand the rider on a flat line. */
    const val MIN_SPAN = 0.02f

    /**
     * One two-finger gesture step: pinch by [zoomFactor] around the fingers'
     * centroid ([centroidFrac], 0..1 across the chart width) and slide by
     * [panFrac] (finger movement as a fraction of the chart width, positive
     * right). The data point under the centroid stays under the fingers while
     * the span changes; a rightward drag reveals earlier ride, like dragging
     * a photo. Result always stays inside 0..1 and never narrower than
     * [MIN_SPAN].
     */
    fun zoomPan(
        window: ClosedFloatingPointRange<Float>,
        zoomFactor: Float,
        centroidFrac: Float,
        panFrac: Float,
    ): ClosedFloatingPointRange<Float> {
        val span = (window.endInclusive - window.start).coerceIn(MIN_SPAN, 1f)
        val newSpan = (span / zoomFactor.coerceIn(0.1f, 10f)).coerceIn(MIN_SPAN, 1f)
        val focus = window.start + span * centroidFrac.coerceIn(0f, 1f)
        var newStart = focus - newSpan * centroidFrac.coerceIn(0f, 1f) - panFrac * newSpan
        newStart = newStart.coerceIn(0f, 1f - newSpan)
        return newStart..(newStart + newSpan)
    }
}
