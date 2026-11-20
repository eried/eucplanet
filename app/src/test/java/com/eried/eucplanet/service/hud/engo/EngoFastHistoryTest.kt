package com.eried.eucplanet.service.hud.engo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngoFastHistoryTest {

    @Test
    fun keepsUpdatesAtMostTenASecond_forTheLastMinute() {
        val h = EngoFastHistory(keepMs = 60_000, minStepMs = 100)
        for (t in 0L..70_000L step 20) h.record("LOAD", t, (t / 1000).toFloat())
        val all = h.since("LOAD", 0)
        assertTrue("oldest kept is within the minute", all.first().timestampMs >= 10_000)
        assertTrue("about ten a second", all.size in 590..610)
        assertTrue(all.zipWithNext().all { (a, b) -> b.timestampMs - a.timestampMs >= 100 })
        assertEquals(65_000L, h.since("LOAD", 65_000).first().timestampMs)
    }

    @Test
    fun forgetsMetricsNoGraphDraws() {
        val h = EngoFastHistory()
        h.record("LOAD", 0, 1f)
        h.record("SPEED", 0, 2f)
        h.retain(setOf("SPEED"))
        assertTrue(h.since("LOAD", 0).isEmpty())
        assertEquals(1, h.since("SPEED", 0).size)
    }

    @Test
    fun aMissingReadingIsNotAPoint() {
        val h = EngoFastHistory()
        h.record("LOAD", 0, Float.NaN)
        assertTrue(h.since("LOAD", 0).isEmpty())
    }
}
