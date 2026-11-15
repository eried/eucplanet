package com.eried.eucplanet.ui.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live G-Force trail is cut from a rolling buffer the view model keeps.
 *
 * The overlay asks for the trail's seconds at 50 Hz plus a 15% tail it fades
 * out. If the buffer holds fewer samples than the longest trail asks for, the
 * tail of that trail is missing and its oldest end snaps off instead of fading.
 */
class GForceTrailBufferTest {

    @Test fun `the longest trail asks for its seconds plus the fade tail`() {
        assertEquals(1150, gForceTrailSamples(GFORCE_TRAIL_MAX_SEC))
    }

    @Test fun `the buffer holds every trail length the rider can pick`() {
        for (sec in GFORCE_TRAIL_MIN_SEC..GFORCE_TRAIL_MAX_SEC) {
            val wanted = gForceTrailSamples(sec)
            assertTrue(
                OverlayStudioViewModel.LIVE_TRAIL_MAX >= wanted,
                "a $sec s trail wants $wanted samples, the buffer keeps " +
                    "${OverlayStudioViewModel.LIVE_TRAIL_MAX}",
            )
        }
    }
}
