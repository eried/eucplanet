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
    fun `a cursor anchor holds its place until the ride's edge takes it`() {
        // Zooming is anchored on the scrub cursor rather than the fingers, so
        // the moment being read stays where it is on screen. Near the end of
        // the ride that can only last until the window hits the edge.
        var w = 0.80f..0.90f
        val anchor = 0.8f                     // the read sits 80% across the view
        val moment = w.start + span(w) * anchor
        repeat(3) { w = ChartWindow.zoomPan(w, 0.5f, anchor, 0f) }
        assertEquals("clamped at the end of the ride", 1f, w.endInclusive, 1e-4f)
        // Held for as long as it could: the moment is still inside the view,
        // just no longer at the same fraction across it.
        assertTrue(moment in w.start..w.endInclusive)
        assertTrue(
            "the anchor has slid once the window ran out of room",
            (w.start + span(w) * anchor) < moment - 1e-3f,
        )
    }

    @Test
    fun `zooming in on a cursor mid-ride keeps it exactly under the line`() {
        var w = 0f..1f
        val anchor = 0.35f
        val moment = w.start + span(w) * anchor
        repeat(4) { w = ChartWindow.zoomPan(w, 1.5f, anchor, 0f) }
        assertEquals(moment, w.start + span(w) * anchor, 1e-4f)
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
