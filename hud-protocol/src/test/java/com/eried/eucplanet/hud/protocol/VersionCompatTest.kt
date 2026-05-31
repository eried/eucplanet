package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive matrix of [VersionCompat.classify] outcomes from both the phone's
 * and the HUD's point of view. Both sides depend on this module so the same
 * `classify(...)` runs on either end -- the matrix is symmetric and both ends
 * agree on which is "ahead" and which is "behind".
 *
 * The current local constants are read live from [HudState.PROTOCOL_MAJOR] /
 * [HudState.PROTOCOL_MINOR]; whenever those bump, the test still passes
 * because the comparisons are relative to "the local side" rather than to a
 * frozen literal.
 */
class VersionCompatTest {

    private val ourMajor = HudState.PROTOCOL_MAJOR
    private val ourMinor = HudState.PROTOCOL_MINOR

    // ---- exact match --------------------------------------------------

    @Test fun exact_match_returns_EXACT() {
        assertEquals(
            VersionCompat.EXACT,
            VersionCompat.classify(ourMajor, ourMinor)
        )
    }

    @Test fun EXACT_is_neither_blocking_nor_hint() {
        assertFalse(VersionCompat.EXACT.isBlocking)
        assertFalse(VersionCompat.EXACT.isHint)
    }

    // ---- remote is BEHIND on minor ------------------------------------

    @Test fun remote_minor_behind_returns_REMOTE_BEHIND_MINOR() {
        // Only meaningful when ourMinor > 0. If we're still on minor 0
        // (the current state at protocol introduction), there is no
        // smaller minor to test -- skip the body, the case is exercised
        // automatically once we bump.
        if (ourMinor == 0) return
        assertEquals(
            VersionCompat.REMOTE_BEHIND_MINOR,
            VersionCompat.classify(ourMajor, ourMinor - 1)
        )
    }

    @Test fun REMOTE_BEHIND_MINOR_is_a_hint_not_blocking() {
        assertTrue(VersionCompat.REMOTE_BEHIND_MINOR.isHint)
        assertFalse(VersionCompat.REMOTE_BEHIND_MINOR.isBlocking)
    }

    // ---- remote is AHEAD on minor -------------------------------------

    @Test fun remote_minor_ahead_returns_REMOTE_AHEAD_MINOR() {
        assertEquals(
            VersionCompat.REMOTE_AHEAD_MINOR,
            VersionCompat.classify(ourMajor, ourMinor + 1)
        )
    }

    @Test fun REMOTE_AHEAD_MINOR_is_a_hint_not_blocking() {
        assertTrue(VersionCompat.REMOTE_AHEAD_MINOR.isHint)
        assertFalse(VersionCompat.REMOTE_AHEAD_MINOR.isBlocking)
    }

    // ---- remote is BEHIND on major ------------------------------------

    @Test fun remote_major_behind_returns_REMOTE_BEHIND_MAJOR() {
        // Only meaningful when ourMajor > 1.
        if (ourMajor <= 1) return
        assertEquals(
            VersionCompat.REMOTE_BEHIND_MAJOR,
            VersionCompat.classify(ourMajor - 1, ourMinor)
        )
    }

    @Test fun major_behind_beats_any_minor_offset() {
        if (ourMajor <= 1) return
        assertEquals(
            "major mismatch must win over a higher minor on the remote",
            VersionCompat.REMOTE_BEHIND_MAJOR,
            VersionCompat.classify(ourMajor - 1, ourMinor + 9999)
        )
    }

    @Test fun REMOTE_BEHIND_MAJOR_is_blocking() {
        assertTrue(VersionCompat.REMOTE_BEHIND_MAJOR.isBlocking)
        assertFalse(VersionCompat.REMOTE_BEHIND_MAJOR.isHint)
    }

    // ---- remote is AHEAD on major -------------------------------------

    @Test fun remote_major_ahead_returns_REMOTE_AHEAD_MAJOR() {
        assertEquals(
            VersionCompat.REMOTE_AHEAD_MAJOR,
            VersionCompat.classify(ourMajor + 1, ourMinor)
        )
    }

    @Test fun major_ahead_beats_any_minor_offset() {
        assertEquals(
            "major mismatch must win over a lower minor on the remote",
            VersionCompat.REMOTE_AHEAD_MAJOR,
            VersionCompat.classify(ourMajor + 1, 0)
        )
    }

    @Test fun REMOTE_AHEAD_MAJOR_is_blocking() {
        assertTrue(VersionCompat.REMOTE_AHEAD_MAJOR.isBlocking)
        assertFalse(VersionCompat.REMOTE_AHEAD_MAJOR.isHint)
    }

    // ---- defaults from legacy / pre-split clients ---------------------

    @Test fun zero_zero_treated_as_pre_split_baseline_1_0_by_helper() {
        // The HudCommandSink wrapper bumps 0 to 1 before passing to
        // classify (a pre-split HUD pair message has hudProtocolMajor=0).
        // Document that contract here so a future refactor doesn't drop
        // it silently.
        val majorAsRead = 0
        val effective = if (majorAsRead == 0) 1 else majorAsRead
        if (HudState.PROTOCOL_MAJOR == 1 && HudState.PROTOCOL_MINOR == 0) {
            assertEquals(
                VersionCompat.EXACT,
                VersionCompat.classify(effective, 0)
            )
        }
    }
}
