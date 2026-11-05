package com.eried.eucplanet.flic

/**
 * What may still fire while Legal Mode Lockdown is armed.
 *
 * An allowlist, not a blocklist, on purpose: an action added to ActionCatalog
 * later is blocked by default rather than silently becoming a hole in the lock.
 */
object LockdownGate {

    /** The two buttons the locked screen offers. Nothing else runs. */
    val ALLOWED_ACTIONS: Set<String> = setOf("HORN", "LIGHT_TOGGLE")

    private val LEGAL_MODE_ACTIONS = setOf("SAFETY_TOGGLE", "SAFETY_ON", "SAFETY_OFF")

    fun isAllowed(key: String): Boolean = key in ALLOWED_ACTIONS

    /** A blocked legal-mode press shows the rider the way out instead of doing nothing. */
    fun raisesUnlockPrompt(key: String): Boolean = key in LEGAL_MODE_ACTIONS
}
