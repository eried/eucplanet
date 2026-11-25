package com.eried.eucplanet.service

import com.eried.eucplanet.hud.protocol.OverlayElementType
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayHistoryNeedTest {

    @Test
    fun `a preset with no graph, map or trail keeps no history`() {
        assertEquals(
            OverlayHistoryNeed.NONE,
            OverlayHistoryNeed.sampleIntervalMs(
                listOf(OverlayElementType.DATA_VALUE, OverlayElementType.CLOCK, OverlayElementType.DATA_DIAL),
            ),
        )
        assertEquals(OverlayHistoryNeed.NONE, OverlayHistoryNeed.sampleIntervalMs(emptyList()))
    }

    @Test
    fun `a graph or a map takes a point a second`() {
        assertEquals(1_000L, OverlayHistoryNeed.sampleIntervalMs(listOf(OverlayElementType.DATA_GRAPH)))
        assertEquals(1_000L, OverlayHistoryNeed.sampleIntervalMs(listOf(OverlayElementType.MAP, OverlayElementType.TEXT)))
    }

    @Test
    fun `the G-force trail keeps the overlay's full rate`() {
        assertEquals(
            200L,
            OverlayHistoryNeed.sampleIntervalMs(listOf(OverlayElementType.DATA_GRAPH, OverlayElementType.G_FORCE)),
        )
    }

    @Test
    fun `every element type that reads the history is known here`() {
        // The elements that read StudioElementData.history, found in the code
        // that draws them. A new one that does has to be given a rate.
        val src = java.io.File("src/main/java/com/eried/eucplanet/ui/studio/StudioOverlayElements.kt").readText()
        val dispatch = Regex("""OverlayElementType\.(\w+) -> (\w+)\(""").findAll(src)
            .associate { it.groupValues[2] to it.groupValues[1] }
        val readers = dispatch.filter { (fn, _) ->
            val at = src.indexOf("fun $fn(")
            if (at < 0) return@filter false
            val next = src.indexOf("\n@Composable", at + 1).let { if (it < 0) src.length else it }
            "data.history" in src.substring(at, next)
        }.values.toSet()
        assertEquals(setOf("G_FORCE", "DATA_GRAPH", "MAP"), readers)
    }
}
