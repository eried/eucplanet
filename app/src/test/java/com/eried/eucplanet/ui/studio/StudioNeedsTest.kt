package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayElementType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioNeedsTest {

    private fun el(type: OverlayElementType, clockStyle: String = "DIGITAL") =
        OverlayElement(id = type.name + clockStyle, type = type, clockStyle = clockStyle)

    @Test
    fun `the frame tick runs only for a stopwatch clock`() {
        assertFalse(StudioNeeds.stopwatchTick(emptyList()))
        assertFalse(StudioNeeds.stopwatchTick(listOf(el(OverlayElementType.DATA_VALUE), el(OverlayElementType.CLOCK))))
        assertFalse(StudioNeeds.stopwatchTick(listOf(el(OverlayElementType.CLOCK, "ANALOG"))))
        assertTrue(StudioNeeds.stopwatchTick(listOf(el(OverlayElementType.DATA_VALUE), el(OverlayElementType.CLOCK, "STOPWATCH"))))
    }

    @Test
    fun `the trail is kept only for a G-force element`() {
        assertFalse(StudioNeeds.gForceTrail(listOf(el(OverlayElementType.DATA_GRAPH), el(OverlayElementType.MAP))))
        assertTrue(StudioNeeds.gForceTrail(listOf(el(OverlayElementType.G_FORCE))))
    }
}
