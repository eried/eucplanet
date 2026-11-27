package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayElementType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioNeedsTest {

    private fun el(type: OverlayElementType, clockStyle: String = "DIGITAL") =
        OverlayElement(id = type.name + clockStyle, type = type, clockStyle = clockStyle)

    @Test
    fun theFrameTickRunsOnlyForAStopwatchClock() {
        assertFalse(StudioNeeds.stopwatchTick(emptyList()))
        assertFalse(StudioNeeds.stopwatchTick(listOf(el(OverlayElementType.DATA_VALUE), el(OverlayElementType.CLOCK))))
        assertFalse(StudioNeeds.stopwatchTick(listOf(el(OverlayElementType.CLOCK, "ANALOG"))))
        assertTrue(StudioNeeds.stopwatchTick(listOf(el(OverlayElementType.DATA_VALUE), el(OverlayElementType.CLOCK, "STOPWATCH"))))
    }

    @Test
    fun theTrailIsKeptOnlyForAGForceElement() {
        assertFalse(StudioNeeds.gForceTrail(listOf(el(OverlayElementType.DATA_GRAPH), el(OverlayElementType.MAP))))
        assertTrue(StudioNeeds.gForceTrail(listOf(el(OverlayElementType.G_FORCE))))
    }
}
