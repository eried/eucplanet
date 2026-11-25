package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayOnceTest {

    private val overlay = """{"elements":[{"type":"DATA_VALUE"}]}"""
    private fun frame(json: String = overlay, speed: Float = 10f) =
        HudState(speedKmh = speed, customOverlayJson = json)

    @Test
    fun an_older_hud_gets_the_overlay_in_every_frame() {
        val sender = OverlaySender()
        repeat(20) { i ->
            val out = sender.stamp(frame(), hudMinor = 27, nowMs = i * 200L)
            assertEquals(overlay, out.customOverlayJson)
            assertFalse(out.customOverlaySame)
        }
    }

    @Test
    fun a_hud_whose_greeting_has_not_arrived_gets_it_in_every_frame_too() {
        val out = OverlaySender().stamp(frame(), hudMinor = 0, nowMs = 0L)
        assertEquals(overlay, out.customOverlayJson)
    }

    @Test
    fun a_new_hud_gets_it_once_then_same_as_before() {
        val sender = OverlaySender()
        val first = sender.stamp(frame(), hudMinor = 28, nowMs = 1_000L)
        assertEquals(overlay, first.customOverlayJson)
        assertFalse(first.customOverlaySame)
        for (i in 1..40) {
            val out = sender.stamp(frame(speed = i.toFloat()), hudMinor = 28, nowMs = 1_000L + i * 200L)
            assertEquals("", out.customOverlayJson)
            assertTrue(out.customOverlaySame)
            assertEquals(i.toFloat(), out.speedKmh, 0f)
        }
    }

    @Test
    fun a_changed_overlay_goes_out_at_once() {
        val sender = OverlaySender()
        sender.stamp(frame(), hudMinor = 28, nowMs = 0L)
        sender.stamp(frame(), hudMinor = 28, nowMs = 200L)
        val other = """{"elements":[]}"""
        val out = sender.stamp(frame(other), hudMinor = 28, nowMs = 400L)
        assertEquals(other, out.customOverlayJson)
        assertFalse(out.customOverlaySame)
    }

    @Test
    fun it_goes_out_again_every_ten_seconds_whatever_happened() {
        val sender = OverlaySender()
        var full = 0
        for (i in 0 until 150) {   // 30 s at 5 frames a second
            if (sender.stamp(frame(), hudMinor = 28, nowMs = i * 200L).customOverlayJson.isNotEmpty()) full++
        }
        assertEquals(3, full)
    }

    @Test
    fun taking_the_overlay_away_is_said_outright() {
        val sender = OverlaySender()
        sender.stamp(frame(), hudMinor = 28, nowMs = 0L)
        val none = sender.stamp(frame(""), hudMinor = 28, nowMs = 200L)
        assertEquals("", none.customOverlayJson)
        assertFalse("no overlay must not read as same as before", none.customOverlaySame)
        // And back again: sent in full.
        val back = sender.stamp(frame(), hudMinor = 28, nowMs = 400L)
        assertEquals(overlay, back.customOverlayJson)
    }

    @Test
    fun the_hud_sees_the_same_overlay_in_every_frame_end_to_end() {
        val sender = OverlaySender()
        val keeper = OverlayKeeper()
        var json = overlay
        for (i in 0 until 200) {
            if (i == 70) json = """{"elements":[]}"""
            if (i == 120) json = ""
            if (i == 160) json = overlay
            val wire = sender.stamp(frame(json, speed = i.toFloat()), hudMinor = 28, nowMs = i * 200L)
            val seen = keeper.restore(wire)
            assertEquals("frame $i", json, seen.customOverlayJson)
            assertFalse(seen.customOverlaySame)
            assertEquals(i.toFloat(), seen.speedKmh, 0f)
        }
    }

    @Test
    fun a_new_hud_reads_an_older_phone_as_before() {
        val keeper = OverlayKeeper()
        repeat(5) { assertEquals(overlay, keeper.restore(frame()).customOverlayJson) }
    }
}
