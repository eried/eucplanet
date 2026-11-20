package com.eried.eucplanet.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** A glasses graph's length, as the picker shows it once chosen: minutes and seconds. */
class EngoWindowClockTest {

    @Test fun `each share of a five minute dashboard`() {
        assertEquals("5:00", engoWindowClock(1, 300))
        assertEquals("2:30", engoWindowClock(2, 300))
        assertEquals("1:15", engoWindowClock(4, 300))
        assertEquals("0:38", engoWindowClock(8, 300))
        assertEquals("0:19", engoWindowClock(16, 300))
    }

    @Test fun `it follows the dashboard's length, never under five seconds`() {
        assertEquals("60:00", engoWindowClock(1, 3600))
        assertEquals("3:45", engoWindowClock(16, 3600))
        // 30 s / 16 would be 2 s: the graph uses the shortest share offered, a quarter.
        assertEquals("0:08", engoWindowClock(16, 30))
    }
}
