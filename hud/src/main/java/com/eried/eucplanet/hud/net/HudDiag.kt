package com.eried.eucplanet.hud.net

import android.util.Log

/**
 * Lightweight HUD-side diagnostic log.
 *
 * The HUD has no shareable diagnostics file like the phone -- its only native
 * sink is logcat, which a rider can't easily pull off an aftermarket unit. So
 * this does two jobs:
 *
 *  1. Mirrors every self-heal step to logcat under one tag so a tester on adb
 *     gets the full story: `adb logcat -s HudDiag`.
 *  2. Keeps a small in-memory ring of recent events AND stages a compact
 *     one-line "recovery note" that [HudServer] threads back to the phone in
 *     its next Pair greeting. THAT does land in the rider's shareable
 *     diagnostics .txt (the phone logs the Pair as a NOTE), which closes the
 *     loop: the next time the link drops out of range and recovers, the shared
 *     log shows exactly what the HUD did instead of just "it came back".
 *
 * Verbose by design -- the whole point of this branch is to understand every
 * step of an out-of-range drop and its recovery.
 */
object HudDiag {
    private const val TAG = "HudDiag"
    private const val RING_CAP = 200

    private val ring = ArrayDeque<String>()
    private val lock = Any()

    /** Staged when a recovery completes; cleared when the next Pair consumes it. */
    @Volatile private var pendingNote: String = ""

    /** Log one step: to logcat (tagged by [origin]) and into the ring buffer. */
    fun log(origin: String, msg: String) {
        Log.i(TAG, "[$origin] $msg")
        synchronized(lock) {
            while (ring.size >= RING_CAP) ring.removeFirst()
            ring.addLast("$origin: $msg")
        }
    }

    /** Stage the summary the HUD should report on its next (re)pair. Overwrites
     *  any previous unconsumed note -- the freshest recovery is the relevant
     *  one for the rider's log. */
    fun setRecoveryNote(note: String) {
        pendingNote = note
        Log.i(TAG, "[recovery] note staged for next pair: $note")
    }

    /** Read and clear the staged recovery note (called while building Pair). */
    fun consumeRecoveryNote(): String {
        val n = pendingNote
        pendingNote = ""
        return n
    }

    /** Snapshot of the recent ring, oldest-first. For an on-screen dump or test. */
    fun recentDump(): List<String> = synchronized(lock) { ring.toList() }
}
