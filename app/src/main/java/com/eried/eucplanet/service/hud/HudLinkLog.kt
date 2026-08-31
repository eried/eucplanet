package com.eried.eucplanet.service.hud

import android.util.Log
import com.eried.eucplanet.diagnostics.DiagnosticsLogger

/**
 * One stream for the whole discovery story.
 *
 * The dial layer already wrote its trace here; the probe and the broadcast
 * listener only wrote to logcat, which a rider cannot send us. That left every
 * remote report of "it won't connect" showing the verdict (no beacon, no mDNS,
 * nothing answered) with none of the context that says why: which networks the
 * phone could actually see, whether the broadcast listener was even bound,
 * whether WiFi came and went underneath it.
 *
 * All three now write here, so a Service Mode capture reads as one timeline,
 * and [HudLinkTrace] keeps the recent past of that timeline whether or not
 * anyone is watching. See [HudLinkTrace] for why that matters.
 */
internal fun hudLinkNote(tag: String, msg: String) {
    Log.i(tag, "[disc] $msg")
    HudLinkTrace.record(msg)
    DiagnosticsLogger.note("hud_link: $msg")
}

/**
 * A small always-on ring of the HUD link trace, replayed into a Service Mode
 * capture the moment one is opened.
 *
 * [DiagnosticsLogger.note] is a no-op until the rider opens Service Mode, so
 * until now the trace only existed for the part of an incident that happened
 * after they went looking. The 2026-08-22 capture is exactly that failure: the
 * rider hit a slow re-pair, opened Service Mode part-way through, and the file
 * begins with the link already broken. The run-up, which is where the answer
 * was, had never been recorded.
 *
 * The link trace is cheap enough to always keep: a handful of lines per search,
 * one beacon line a minute, nothing at all while a link is healthy. [MAX_ENTRIES]
 * of it costs well under 100 KB, which is worth paying so the next field report
 * arrives already containing its own cause. Raw BLE stays opt-in, that is the
 * firehose this ring is deliberately not.
 *
 * Replay is watermarked by sequence number so reopening Service Mode, or
 * toggling it off and on mid-ride, never writes a line into the capture twice.
 */
internal object HudLinkTrace {

    /** Roughly an hour of a link that is having trouble, and far more than that
     *  of one that is not. Sized to hold the run-up to a report, not a session. */
    const val MAX_ENTRIES = 600

    private class Line(val seq: Long, val timestampMs: Long, val text: String)

    private val lock = Any()
    private val ring = ArrayDeque<Line>(MAX_ENTRIES)
    private var seq = 0L
    /** Highest sequence already handed to [DiagnosticsLogger], live or replayed. */
    private var replayedThrough = 0L
    private var registered = false

    fun record(msg: String) {
        synchronized(lock) {
            registerOnce()
            seq++
            if (ring.size >= MAX_ENTRIES) ring.removeFirst()
            ring.addLast(Line(seq, System.currentTimeMillis(), msg))
            // A note written while service mode is on reaches the buffer through
            // the normal path, so it must not be replayed on top of itself.
            if (DiagnosticsLogger.enabled.value) replayedThrough = seq
        }
    }

    private fun registerOnce() {
        if (registered) return
        registered = true
        DiagnosticsLogger.registerBackfill { drain() }
    }

    /** Everything recorded since the last replay, oldest first. */
    private fun drain(): List<DiagnosticsLogger.Entry> = synchronized(lock) {
        val fresh = ring.filter { it.seq > replayedThrough }
        replayedThrough = seq
        fresh.map {
            DiagnosticsLogger.Entry(
                timestampMs = it.timestampMs,
                kind = DiagnosticsLogger.Kind.NOTE,
                text = "hud_link: ${it.text}",
            )
        }
    }
}
