package com.eried.eucplanet.service

import com.eried.eucplanet.service.MediaControlPolicy.Action
import com.eried.eucplanet.service.MediaControlPolicy.HOLD_MS
import com.eried.eucplanet.service.MediaControlPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the music pauses, when it comes back, and when it is left alone.
 *
 * The one that mattered: with resume switched off, the pause fired once per
 * ride and never again. Erwin likes that it does not re-fire while stopped,
 * and wanted it to fire on every crossing from fast to slow. Both hold now.
 */
class MediaControlPolicyTest {

    private var state = State()
    private var now = 1_000_000L

    /** Feed one speed reading, advancing the clock by [dtMs]. */
    private fun tick(
        speed: Float,
        dtMs: Long = 1000L,
        pause: Boolean = true, pauseAt: Int = 5,
        resume: Boolean = true, resumeAt: Int = 10,
        externalOk: Boolean = true, connected: Boolean = true,
    ): Action {
        now += dtMs
        val r = MediaControlPolicy.step(state, speed, now, pause, pauseAt, resume, resumeAt, externalOk, connected)
        state = r.state
        return r.action
    }

    /** Hold a speed long enough for the policy to act, returning the actions seen. */
    private fun hold(speed: Float, vararg opts: Pair<String, Any>): List<Action> {
        val o = opts.toMap()
        val out = ArrayList<Action>()
        repeat(5) {
            out += tick(speed,
                pause = o["pause"] as? Boolean ?: true, pauseAt = o["pauseAt"] as? Int ?: 5,
                resume = o["resume"] as? Boolean ?: true, resumeAt = o["resumeAt"] as? Int ?: 10,
                externalOk = o["externalOk"] as? Boolean ?: true, connected = o["connected"] as? Boolean ?: true)
        }
        return out.filter { it != Action.NONE }
    }

    @Test fun `slowing down pauses once, after the hold`() {
        assertEquals(Action.NONE, tick(3f))
        assertEquals(Action.NONE, tick(3f, dtMs = HOLD_MS - 1500))
        assertEquals(Action.PAUSE, tick(3f, dtMs = 1500))
        assertTrue(state.autoPaused)
    }

    @Test fun `staying stopped does not pause again`() {
        hold(0f)
        assertEquals(emptyList<Action>(), hold(0f))
        assertEquals(emptyList<Action>(), hold(2f))
    }

    @Test fun `a blip below the line does nothing`() {
        assertEquals(Action.NONE, tick(3f, dtMs = 500))
        assertEquals(Action.NONE, tick(20f))
        assertFalse(state.autoPaused)
        assertEquals(0L, state.pauseSince)
    }

    @Test fun `speeding up resumes what we paused`() {
        hold(0f)
        assertEquals(listOf(Action.PLAY), hold(15f))
        assertFalse(state.autoPaused)
    }

    @Test fun `the second crossing from fast to slow pauses again`() {
        hold(0f); hold(15f)
        assertEquals(listOf(Action.PAUSE), hold(0f))
    }

    @Test fun `with resume off, the pause still fires on every crossing`() {
        // The bug: the re-arm lived inside the resume rule, so without resume
        // the first pause was also the last.
        assertEquals(listOf(Action.PAUSE), hold(0f, "resume" to false))
        assertEquals(emptyList<Action>(), hold(0f, "resume" to false))
        // Fast again: nothing is sent (resume is off) but the pause re-arms.
        assertEquals(emptyList<Action>(), hold(15f, "resume" to false))
        assertFalse(state.autoPaused)
        assertEquals(listOf(Action.PAUSE), hold(0f, "resume" to false))
    }

    @Test fun `resume waits for headphones and keeps waiting`() {
        hold(0f)
        // Fast, on the phone speaker: no resume, and no re-arm either, so
        // plugging headphones in at speed still brings the music back.
        assertEquals(emptyList<Action>(), hold(15f, "externalOk" to false))
        assertTrue(state.autoPaused)
        assertEquals(listOf(Action.PLAY), hold(15f, "externalOk" to true))
    }

    @Test fun `a pause the rider made is never resumed`() {
        // Never auto-paused, so speeding up sends nothing.
        assertEquals(emptyList<Action>(), hold(15f))
        assertFalse(state.autoPaused)
    }

    @Test fun `the resume speed sits above the pause speed even if set equal`() {
        hold(0f, "pauseAt" to 5, "resumeAt" to 5)
        // 6 km/h is above the rider's 5, but inside the enforced dead-band.
        assertEquals(emptyList<Action>(), hold(6f, "pauseAt" to 5, "resumeAt" to 5))
        assertEquals(listOf(Action.PLAY), hold(7f, "pauseAt" to 5, "resumeAt" to 5))
    }

    @Test fun `a disconnected wheel reporting zero does not pause the music`() {
        assertEquals(emptyList<Action>(), hold(0f, "connected" to false))
        assertFalse(state.autoPaused)
    }

    @Test fun `disconnecting keeps the paused flag so a reconnect cannot resume a rider's stop`() {
        hold(0f)
        hold(0f, "connected" to false)
        assertTrue(state.autoPaused)
        assertEquals(0L, state.pauseSince)
        assertEquals(0L, state.resumeSince)
    }
}
