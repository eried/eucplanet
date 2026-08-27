package com.eried.eucplanet.ui.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared chart zoom window: focus stays under the fingers, bounds and
 *  minimum span always hold, gestures compose sanely. */
class ChartWindowTest {

    private fun span(r: ClosedFloatingPointRange<Float>) = r.endInclusive - r.start

    @Test
    fun `identity gesture changes nothing`() {
        val w = ChartWindow.zoomPan(0.2f..0.7f, 1f, 0.5f, 0f)
        assertEquals(0.2f, w.start, 1e-4f)
        assertEquals(0.7f, w.endInclusive, 1e-4f)
    }

    @Test
    fun `pinching in halves the span and keeps the focus point in place`() {
        val before = 0f..1f
        val centroid = 0.25f
        val focus = before.start + span(before) * centroid
        val after = ChartWindow.zoomPan(before, 2f, centroid, 0f)
        assertEquals(0.5f, span(after), 1e-4f)
        // The data point under the fingers is still under them.
        assertEquals(focus, after.start + span(after) * centroid, 1e-4f)
    }

    @Test
    fun `zooming out from a slice returns to the full ride and clamps there`() {
        val out = ChartWindow.zoomPan(0.4f..0.6f, 0.1f, 0.5f, 0f)
        assertEquals(0f, out.start, 1e-4f)
        assertEquals(1f, out.endInclusive, 1e-4f)
    }

    @Test
    fun `pan slides the window, rightward drag reveals earlier ride`() {
        val before = 0.4f..0.6f
        val after = ChartWindow.zoomPan(before, 1f, 0.5f, 0.5f)
        assertTrue(after.start < before.start)
        assertEquals(span(before), span(after), 1e-4f)
    }

    @Test
    fun `pan clamps at both ends without squashing the span`() {
        val left = ChartWindow.zoomPan(0.05f..0.25f, 1f, 0.5f, 5f)
        assertEquals(0f, left.start, 1e-4f)
        assertEquals(0.2f, span(left), 1e-4f)
        val right = ChartWindow.zoomPan(0.75f..0.95f, 1f, 0.5f, -5f)
        assertEquals(1f, right.endInclusive, 1e-4f)
        assertEquals(0.2f, span(right), 1e-4f)
    }

    @Test
    fun `zoom never goes narrower than the minimum span`() {
        var w: ClosedFloatingPointRange<Float> = 0f..1f
        repeat(30) { w = ChartWindow.zoomPan(w, 3f, 0.5f, 0f) }
        assertEquals(ChartWindow.MIN_SPAN, span(w), 1e-4f)
        assertTrue(w.start >= 0f && w.endInclusive <= 1f)
    }

    @Test
    fun `absurd gesture values stay bounded`() {
        val w = ChartWindow.zoomPan(0.1f..0.9f, 1000f, 5f, -100f)
        assertTrue(w.start >= 0f && w.endInclusive <= 1f)
        assertTrue(span(w) >= ChartWindow.MIN_SPAN - 1e-4f)
    }
}
