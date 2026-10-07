package com.eried.eucplanet.service.hud

import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The HUD discovery trace has to survive NOT being watched.
 *
 * `DiagnosticsLogger.note` is a no-op until the rider opens Service Mode, which
 * means the trace only ever existed for the part of an incident that happened
 * after they went looking. The 2026-08-22 capture is exactly that: the rider hit
 * a slow re-pair, opened Service Mode part-way through, and the file starts at
 * 15:12:43 with the link already broken. The run-up, which is where the answer
 * was, had never been recorded at all.
 *
 * So the link trace now always records into a small ring of its own, and opening
 * Service Mode replays that ring into the capture. The rider's report becomes
 * self-diagnosing: whatever happened in the minutes before they went looking is
 * in the file, with its original timestamps.
 *
 * This is deliberately only the link trace. Raw BLE stays opt-in, it is orders
 * of magnitude bigger and it is the thing the ring is protecting.
 */
class HudLinkTraceTest {

    @Before fun reset() {
        DiagnosticsLogger.disable()
        DiagnosticsLogger.clear()
    }

    private fun notes() = DiagnosticsLogger.entries.value
        .filter { it.kind == DiagnosticsLogger.Kind.NOTE }
        .map { it.text }

    @Test fun a_note_taken_before_service_mode_still_reaches_the_capture() {
        hudLinkNote("test", "beacon RX before anyone was watching")

        assertTrue("nothing is recorded while service mode is off", notes().isEmpty())

        DiagnosticsLogger.enable()

        assertEquals(
            listOf("hud_link: beacon RX before anyone was watching"),
            notes()
        )
    }

    @Test fun replayed_notes_keep_their_own_timestamps_and_come_first() {
        hudLinkNote("test", "older")
        val recordedAtMs = System.currentTimeMillis()
        DiagnosticsLogger.enable()
        hudLinkNote("test", "newer")

        val all = DiagnosticsLogger.entries.value
        // The replayed line has to sort before "entered service mode", or the
        // capture reads as though the link work started when the rider looked.
        val replayed = all.first()
        assertEquals(DiagnosticsLogger.Kind.NOTE, replayed.kind)
        assertEquals("hud_link: older", replayed.text)
        assertTrue(
            "replay must carry the original timestamp, not the replay time",
            replayed.timestampMs <= recordedAtMs
        )
        assertEquals(listOf("hud_link: older", "hud_link: newer"), notes())
    }

    @Test fun the_ring_is_bounded_and_keeps_the_most_recent() {
        val overflow = HudLinkTrace.MAX_ENTRIES + 25
        repeat(overflow) { hudLinkNote("test", "line $it") }

        DiagnosticsLogger.enable()

        val replayed = notes()
        assertEquals(HudLinkTrace.MAX_ENTRIES, replayed.size)
        assertEquals("hud_link: line 25", replayed.first())
        assertEquals("hud_link: line ${overflow - 1}", replayed.last())
    }

    @Test fun reopening_service_mode_does_not_duplicate_what_was_already_replayed() {
        hudLinkNote("test", "first")
        DiagnosticsLogger.enable()
        DiagnosticsLogger.disable()
        hudLinkNote("test", "second")
        DiagnosticsLogger.enable()

        assertEquals(listOf("hud_link: first", "hud_link: second"), notes())
    }

    @Test fun a_live_note_is_written_once_not_twice() {
        // While service mode is on the note goes straight to the buffer. It is
        // also kept in the ring, but it must not be replayed on top of itself.
        DiagnosticsLogger.enable()
        hudLinkNote("test", "live")
        DiagnosticsLogger.disable()
        DiagnosticsLogger.enable()

        assertEquals(listOf("hud_link: live"), notes())
    }
}
