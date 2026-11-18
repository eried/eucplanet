package com.eried.eucplanet.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    // --- the Black edge: preview and export fill the same span ----------------

    @Test fun black_outOfRange_fillsBlack() {
        // Wherever the export has no frame for a Black-edge face, it has to be
        // told to fill black. Otherwise the pane exports see-through while the
        // preview shows black.
        for (cursor in listOf(1000L, 20_000L)) {
            assertNull(edgeVideoTimeUs(cursor, offsetMs = 5000, videoDurationMs = 10_000, edge = "BLACK"))
            assertTrue(videoEdgeIsBlack(cursor, offsetMs = 5000, videoDurationMs = 10_000, edge = "BLACK"))
        }
    }
    @Test fun black_insideWindow_showsTheClip() {
        assertFalse(videoEdgeIsBlack(cursorMs = 7000, offsetMs = 5000, videoDurationMs = 10_000, edge = "BLACK"))
        assertFalse(videoEdgeIsBlack(cursorMs = 5000, offsetMs = 5000, videoDurationMs = 10_000, edge = "BLACK"))
        assertFalse(videoEdgeIsBlack(cursorMs = 15_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "BLACK"))
    }
    @Test fun black_onlyForTheBlackEdge() {
        assertFalse(videoEdgeIsBlack(cursorMs = 20_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "FREEZE"))
        assertFalse(videoEdgeIsBlack(cursorMs = 20_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "LOOP"))
    }

    // --- what the pane shows: one decision for preview and export --------------

    @Test fun fill_noUsableClip_isThePlaceholder_whateverTheEdge() {
        // No clip bound, or one that will not open: the rider sees "No media"
        // on screen, so the export has to carry it too, on every frame.
        for (edge in listOf("FREEZE", "LOOP", "BLACK")) {
            for (cursor in listOf(0L, 7000L, 20_000L)) {
                assertEquals(
                    "$edge at $cursor",
                    ReplayVideoFill.NO_MEDIA,
                    replayVideoFill(false, cursor, offsetMs = 5000, videoDurationMs = 0, edge = edge)
                )
            }
        }
    }
    @Test fun fill_usableClip_insideWindow_isTheFrame() {
        for (edge in listOf("FREEZE", "LOOP", "BLACK")) {
            assertEquals(
                edge,
                ReplayVideoFill.FRAME,
                replayVideoFill(true, cursorMs = 7000, offsetMs = 5000, videoDurationMs = 10_000, edge = edge)
            )
        }
    }
    @Test fun fill_usableClip_outOfRange_followsTheEdge() {
        assertEquals(
            ReplayVideoFill.BLACK,
            replayVideoFill(true, cursorMs = 20_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "BLACK")
        )
        assertEquals(
            ReplayVideoFill.FRAME,
            replayVideoFill(true, cursorMs = 20_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "FREEZE")
        )
        assertEquals(
            ReplayVideoFill.FRAME,
            replayVideoFill(true, cursorMs = 20_000, offsetMs = 5000, videoDurationMs = 10_000, edge = "LOOP")
        )
    }

    @Test fun edge_zeroDuration_isNull() {
        assertNull(
            edgeVideoTimeUs(cursorMs = 5000, offsetMs = 0, videoDurationMs = 0, edge = "FREEZE")
        )
    }
}
