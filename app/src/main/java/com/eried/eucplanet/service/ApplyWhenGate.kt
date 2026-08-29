package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.ApplyWhenIds

/**
 * When a speed-driven automation may act.
 *
 * One question asked by three features - headlight, volume and playback rate -
 * so it is answered in one place, and answered as a pure function of the two
 * things it depends on. Inside [AutomationManager] it was private and read
 * live repositories, which made the most important line in all three
 * automations the one nothing could test.
 */
object ApplyWhenGate {

    /** Above this the wheel counts as moving rather than rolling under a
     *  rider standing over it. Low on purpose: walking pace already means
     *  the ride has started. */
    const val RIDING_KMH = 3f

    fun allows(mode: String, connected: Boolean, speedKmh: Float): Boolean = when (mode) {
        // Never is the off state, not "no condition": one control says both
        // whether the automation runs and what it waits for.
        ApplyWhenIds.NEVER -> false
        ApplyWhenIds.CONNECTED -> connected
        // Riding implies connected: speed comes from the wheel, so a stale
        // reading from a wheel that has gone away must not count as riding.
        else -> connected && speedKmh >= RIDING_KMH
    }

    /**
     * Like [allows], but for an automation that must keep acting while the
     * rider is momentarily stopped.
     *
     * [allows] asks "is the wheel moving right now", which is the wrong
     * question for the headlight: its whole purpose is what happens when the
     * rider slows to a walk, and by then the plain gate has already switched
     * the automation off. So "riding" latches for the connection: once they
     * have actually ridden, waiting at a crossing still counts.
     */
    fun allowsSticky(mode: String, connected: Boolean, rodeThisSession: Boolean): Boolean =
        when (mode) {
            ApplyWhenIds.NEVER -> false
            ApplyWhenIds.CONNECTED -> connected
            else -> connected && rodeThisSession
        }
}