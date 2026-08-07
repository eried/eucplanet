package com.eried.eucplanet.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudioVideoSyncTest {
    @Test fun atOffset_isFrameZero() {
        assertEquals(0L, videoTimeUsFor(cursorMs = 5000, offsetMs = 5000, videoDurationMs = 10_000))
    }
    @Test fun realTime_oneToOne() {
        assertEquals(2_000_000L, videoTimeUsFor(cursorMs = 7000, offsetMs = 5000, videoDurationMs = 10_000))
    }
    @Test fun beforeOffset_isNull() {
        assertNull(videoTimeUsFor(cursorMs = 4000, offsetMs = 5000, videoDurationMs = 10_000))
    }
    @Test fun pastEnd_isNull() {
        assertNull(videoTimeUsFor(cursorMs = 16_000, offsetMs = 5000, videoDurationMs = 10_000))
    }

    // --- edge behavior (out of the clip window) ------------------------------

    @Test fun edge_insideWindow_matchesRealTime() {
        assertEquals(
            2_000_000L,
            edgeVideoTimeUs(cursorMs = 7000, offsetMs = 5000, videoDurationMs = 10_000, edge = "FREEZE")
        )
    }
    @Test fun edge_freeze_beforeStart_holdsFirstFrame() {
        assertEquals(
            0L,
            edgeVideoTimeUs(cursorMs = 1000, offsetMs = 5000, videoDurationMs = 10_000, edge = "FREEZE")
        )
    }
    @Test fun edge_freeze_pastEnd_holdsLastFrame() {
        assertEquals(
            10_000_000L,
            edgeVideoTimeUs(cursorMs = 20_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "FREEZE")
        )
    }
    @Test fun edge_loop_pastEnd_wraps() {
        // 3s past the end of a 10s clip -> 3s into the clip
        assertEquals(
            3_000_000L,
            edgeVideoTimeUs(cursorMs = 18_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "LOOP")
        )
    }
    @Test fun edge_loop_beforeStart_wrapsPositive() {
        // 2s before the offset -> 8s into a 10s clip (never negative)
        assertEquals(
            8_000_000L,
            edgeVideoTimeUs(cursorMs = 3000, offsetMs = 5000, videoDurationMs = 10_000, edge = "LOOP")
        )
    }
    @Test fun edge_black_outOfRange_isNull() {
        assertNull(
            edgeVideoTimeUs(cursorMs = 20_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "BLACK")
        )
    }
    @Test fun edge_zeroDuration_isNull() {
        assertNull(
            edgeVideoTimeUs(cursorMs = 5000, offsetMs = 0, videoDurationMs = 0, edge = "FREEZE")
        )
    }
}
