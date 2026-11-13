package com.eried.eucplanet.flic

/**
 * Reads the volume adjustments a media session receives back into the clicks
 * and holds that [com.eried.eucplanet.MainActivity] sees from the key events.
 *
 * With the screen off, or another app in front, Android hands no app the
 * volume keys. What it does hand the media session that owns a "remote"
 * volume is one adjust call per key event: +1 or -1 on every key down, the
 * auto-repeats while the key is held included, and 0 on the key up. This
 * class turns that stream into one click or one hold per press, with the
 * same 600 ms hold as the activity, so a binding does the same thing with
 * the screen on or off.
 *
 * Phones differ in what they send. One that repeats fires the hold on the
 * first repeat past [holdMs]. One that sends only the down and the up fires
 * it on the up, from how long the key was down. One that never sends the up
 * is taken as released after [releaseMs] of silence, as a click, since
 * nothing says how long it was held.
 */
class VolumeKeyPresses(
    private val holdMs: Long = 600L,
    private val releaseMs: Long = 1500L,
) {
    enum class Key { UP, DOWN }

    data class Fired(val key: Key, val hold: Boolean)

    private var pressed: Key? = null
    private var downAt = 0L
    private var lastAt = 0L
    private var holdFired = false

    /** One adjust call: direction above 0 is up, below 0 down, 0 the key let go. */
    fun adjust(direction: Int, nowMs: Long): List<Fired> {
        val key = when {
            direction > 0 -> Key.UP
            direction < 0 -> Key.DOWN
            else -> null
        }
        val fired = ArrayList<Fired>(2)
        if (key == null) {
            release(nowMs, known = true)?.let(fired::add)
            return fired
        }
        if (pressed != null && pressed != key) release(nowMs, known = true)?.let(fired::add)
        if (pressed == null) {
            pressed = key
            downAt = nowMs
            holdFired = false
        }
        lastAt = nowMs
        if (!holdFired && nowMs - downAt >= holdMs) {
            holdFired = true
            fired += Fired(key, hold = true)
        }
        return fired
    }

    /** When the press should be given up on if nothing more arrives, or null while nothing is pressed. */
    val expiresAt: Long?
        get() = if (pressed != null) lastAt + releaseMs else null

    /** Call at [expiresAt]: the release never came, so the press ends now. */
    fun expire(nowMs: Long): Fired? =
        if (pressed != null && nowMs - lastAt >= releaseMs) release(nowMs, known = false) else null

    private fun release(nowMs: Long, known: Boolean): Fired? {
        val key = pressed ?: return null
        pressed = null
        if (holdFired) return null
        // A real key-up tells how long the key was down; a timeout does not.
        val held = known && nowMs - downAt >= holdMs
        return Fired(key, hold = held)
    }
}
