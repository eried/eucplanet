package com.eried.eucplanet.nav

import com.eried.eucplanet.raceThreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceGateTest {

    private data class State(val active: Boolean = false, val turn: Int = 0)

    @Test
    fun `guidance writes land while guiding and are dropped after Stop`() {
        val gate = GuidanceGate()
        var state = State()
        assertFalse(gate.ifGuiding { state = state.copy(turn = 1) })
        gate.begin()
        assertTrue(gate.ifGuiding { state = state.copy(active = true, turn = 2) })
        gate.end { state = state.copy(active = false) }
        assertFalse(gate.ifGuiding { state = state.copy(active = true, turn = 3) })
        assertEquals(State(active = false, turn = 2), state)
    }

    @Test
    fun `a start that was stopped while it loaded publishes nothing`() {
        val gate = GuidanceGate()
        var published = 0
        val first = gate.begin()
        gate.end { }
        assertFalse(gate.ifSession(first) { published++ })
        // And a start overtaken by a newer one.
        val second = gate.begin()
        val third = gate.begin()
        assertFalse(gate.ifSession(second) { published++ })
        assertTrue(gate.ifSession(third) { published++ })
        assertEquals(1, published)
    }

    @Test
    fun `the rider's own toggles work stopped or not`() {
        val gate = GuidanceGate()
        var minimized = false
        gate.always { minimized = true }
        assertTrue(minimized)
    }

    @Test
    fun `Stop from another thread is never undone by a write in progress`() {
        // Guidance on one thread reads the state, changes it and writes it
        // back; Stop on the other clears it. Whatever the timing, once both
        // are done the state must read stopped.
        repeat(200) {
            val gate = GuidanceGate()
            var state = State(active = true)
            gate.begin()
            val thrown = raceThreads(
                rounds = 2_000,
                sides = arrayOf(
                    { i -> gate.ifGuiding { state = state.copy(active = true, turn = i) } },
                    { i -> if (i == 1_000) gate.end { state = state.copy(active = false) } },
                ),
            )
            assertNull("$thrown", thrown)
            assertFalse("a write landed after Stop", state.active)
        }
    }
}
