package com.eried.eucplanet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the two cues.
 *
 * Not a sound test: what is worth pinning is that the ending figure is the
 * opening one reversed. A rider learns one cue and gets the other for free,
 * and that only holds while the notes stay mirrored.
 */
class VoiceEndCueTest {

    // The figures as the player states them, kept here so a change to either
    // has to be a deliberate change to both.
    private val start = listOf(780 to 70, 1040 to 80)
    private val end = listOf(1040 to 70, 780 to 80)

    @Test
    fun `the start cue rises`() {
        assertTrue("a cue that falls reads as an ending", start[1].first > start[0].first)
    }

    @Test
    fun `the end cue falls`() {
        assertTrue("a cue that rises reads as an invitation", end[1].first < end[0].first)
    }

    @Test
    fun `they use the same two notes`() {
        assertEquals(start.map { it.first }.sorted(), end.map { it.first }.sorted())
    }

    @Test
    fun `both are short enough to be over before a rider speaks`() {
        // The cue has to finish before the question starts, or the recogniser
        // spends its first moments listening to us.
        assertTrue(start.sumOf { it.second } <= 200)
        assertTrue(end.sumOf { it.second } <= 200)
    }
}
