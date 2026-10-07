package com.eried.eucplanet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone belongs to whoever took it first.
 *
 * The Studio records video through an AudioRecord, and two things cannot hold
 * the microphone at once. A rider filming a ride and asking a question got
 * "I did not catch that", which blames them for the app's own conflict and
 * sends them repeating themselves into a microphone they were never going to
 * get. The listener now says which kind of failure it was.
 */
class VoiceMicBusyTest {

    @Test
    fun `a failure carries whether the microphone was the problem`() {
        assertTrue(ListenState.Failed("audio", micUnavailable = true).micUnavailable)
        // The default matters: every existing construction site means "gave
        // up", not "the microphone was taken".
        assertFalse(ListenState.Failed("no match").micUnavailable)
    }

    @Test
    fun `heard nothing is not the microphone being busy`() {
        // The distinction the rider hears. Silence is theirs to fix by
        // speaking again; a taken microphone is not.
        assertFalse(ListenState.Failed("heard nothing").micUnavailable)
        assertEquals("heard nothing", ListenState.Failed("heard nothing").reason)
    }

    @Test
    fun `the fake listener still reports a clean failure`() {
        // FakeVoiceListener drives every session test. Adding a field with a
        // default must not have quietly turned those into mic-busy runs.
        val states = FakeVoiceListener("battery").script()
        assertTrue(states.none { it is ListenState.Failed && it.micUnavailable })
    }
}
