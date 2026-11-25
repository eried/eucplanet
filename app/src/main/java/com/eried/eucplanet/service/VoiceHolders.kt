package com.eried.eucplanet.service

/**
 * Who needs the voice right now: the app's window and the wheel service.
 *
 * Each of the two used to start the voice on its way in and shut it down on
 * its way out, without asking about the other. Swiping the app away in the
 * middle of a ride closed the window, which took the voice from the service
 * that was still running: alarms and announcements went unspoken, and were
 * all spoken at once, late, when the app was opened again.
 */
class VoiceHolders {
    private val held = mutableSetOf<String>()

    @Synchronized
    fun add(who: String) {
        held.add(who)
    }

    /** Takes [who] off the list. True when that left nobody, which is when
     *  the voice can be shut down. Someone who was not on it changes nothing. */
    @Synchronized
    fun remove(who: String): Boolean = held.remove(who) && held.isEmpty()
}
