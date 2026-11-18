package com.eried.eucplanet.service.hud.engo

/**
 * When to look for the glasses again.
 *
 * The app links to the first glasses it finds by name; there is no list to
 * pick from. A link that drops (glasses switched off, out of range, Bluetooth
 * off for a moment) used to stay down until the rider flipped the switch.
 */
object EngoLink {

    /** How long to wait between searches while there is no link. */
    const val SEARCH_EVERY_MS = 5_000L

    /**
     * Whether to start a search now: the rider wants the link kept
     * ([autoConnect]), there is none and none being made ([idle]), and the
     * last search began at least [SEARCH_EVERY_MS] ago.
     */
    fun searchDue(autoConnect: Boolean, idle: Boolean, sinceLastSearchMs: Long): Boolean =
        autoConnect && idle && sinceLastSearchMs >= SEARCH_EVERY_MS
}
