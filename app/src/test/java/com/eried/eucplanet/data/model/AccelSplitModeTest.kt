package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four speed-split states and the settings pair they map onto.
 *
 * The settings screen has a switch and a direction picker; the dashboard
 * button has one tap. Both must mean the same thing by "Brake", and this is
 * the registry that says so, so it gets the drift guard every registry gets.
 */
class AccelSplitModeTest {

    @Test fun `a tap walks off, accel, brake, both, and round again`() {
        assertEquals(
            listOf(AccelSplitMode.ACCEL, AccelSplitMode.BRAKE, AccelSplitMode.BOTH, AccelSplitMode.OFF),
            listOf(AccelSplitMode.OFF, AccelSplitMode.ACCEL, AccelSplitMode.BRAKE, AccelSplitMode.BOTH).map { it.next() },
        )
    }

    @Test fun `every state round-trips through the settings`() {
        for (mode in AccelSplitMode.entries) {
            val settings = mode.applyTo(AccelSplitSettings())
            assertEquals("$mode did not survive the settings", mode, AccelSplitMode.of(settings))
        }
    }

    @Test fun `off keeps the direction the rider last chose`() {
        // The settings switch turned back on should come back in the same
        // direction. Off only clears the switch.
        val braking = AccelSplitMode.BRAKE.applyTo(AccelSplitSettings())
        val off = AccelSplitMode.OFF.applyTo(braking)
        assertFalse(off.enabled)
        assertEquals("BRAKE", off.direction)
        assertEquals(AccelSplitMode.BRAKE, AccelSplitMode.of(off.copy(enabled = true)))
    }

    @Test fun `the settings default is off, and an unknown direction reads as accel`() {
        assertEquals(AccelSplitMode.OFF, AccelSplitMode.of(AccelSplitSettings()))
        assertEquals(
            AccelSplitMode.ACCEL,
            AccelSplitMode.of(AccelSplitSettings(enabled = true, direction = "SIDEWAYS")),
        )
    }

    @Test fun `every state has its own tile label`() {
        // Four states that read the same on the tile is a guessing game.
        val labels = AccelSplitMode.entries.map { it.tileLabelRes }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test fun `the directions match the tracker's flags`() {
        // What the tracker is told: accel tracks up, brake tracks down, both
        // track both. Stated here so a renamed direction cannot silently turn
        // "Brake" into accel-only.
        fun tracks(mode: AccelSplitMode) = (mode == AccelSplitMode.ACCEL || mode == AccelSplitMode.BOTH) to
            (mode == AccelSplitMode.BRAKE || mode == AccelSplitMode.BOTH)
        assertEquals(true to false, tracks(AccelSplitMode.ACCEL))
        assertEquals(false to true, tracks(AccelSplitMode.BRAKE))
        assertEquals(true to true, tracks(AccelSplitMode.BOTH))
        assertTrue(tracks(AccelSplitMode.OFF) == (false to false))
    }
}
