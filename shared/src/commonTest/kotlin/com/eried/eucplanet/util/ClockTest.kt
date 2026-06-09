package com.eried.eucplanet.util

import kotlin.test.Test
import kotlin.test.assertTrue

class ClockTest {
    @Test fun nowIsAfter2020() {
        // 2020-01-01T00:00:00Z in epoch ms; a sane clock must be well past this.
        assertTrue(nowEpochMillis() > 1_577_836_800_000L)
    }
}
