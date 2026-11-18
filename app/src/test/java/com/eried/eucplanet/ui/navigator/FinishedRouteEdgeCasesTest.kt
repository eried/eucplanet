package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.data.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Edge cases for keeping reached stops at the front. */
class FinishedRouteEdgeCasesTest {

    private fun wp(name: String, passed: Boolean = false) = Waypoint(0.0, 0.0, name, passed = passed)

    @Test fun `moving to the top with two reached stops lands right after them`() {
        val list = listOf(wp("r1", true), wp("r2", true), wp("a"), wp("b"))
        val moved = reorderKeepingPassed(list, from = 3, to = 0)!!
        assertEquals(listOf("r1", "r2", "b", "a"), moved.map { it.name })
        assertEquals(2, moved.indexOfFirst { it.name == "b" })
    }

    @Test fun `all reached means the whole list is the prefix`() {
        val list = listOf(wp("a", true), wp("b", true), wp("c", true))
        assertEquals(list.size, passedPrefix(list))
        assertNull(reorderKeepingPassed(list, from = 2, to = 0))
    }

    @Test fun `an empty list has no prefix and nothing to move`() {
        assertEquals(0, passedPrefix(emptyList()))
        assertNull(reorderKeepingPassed(emptyList(), from = 0, to = 0))
    }
}
