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

    /** The maker id ActiveLook glasses advertise (its documentation: filter
     *  on a manufacturer id ending in 0x08F2). */
    const val MAKER_ID = 0x08F2

    /** The name ActiveLook glasses carry when the maker set none. */
    private const val DEFAULT_NAME = "A.Look"

    /**
     * Whether an advertisement is from glasses the app can drive: its name
     * starts with [prefix] (or the ActiveLook default), or it carries the
     * ActiveLook maker id. Either is enough, since which one a given pair
     * sends is only known from a real unit.
     */
    fun isGlasses(name: String?, makerIds: List<Int>, prefix: String = "ENGO"): Boolean =
        name?.startsWith(prefix, ignoreCase = true) == true ||
            name?.startsWith(DEFAULT_NAME, ignoreCase = true) == true ||
            MAKER_ID in makerIds

    /**
     * Whether glasses with this hardware version string have a colour
     * display. ActiveLook names the display module in it: MDP08 is colour
     * ("ALK03-MDP08ACRG"), MDP05 grey levels. Anything else counts as grey,
     * which every pair can draw.
     */
    fun isColor(hardware: String): Boolean = hardware.contains("MDP08", ignoreCase = true)
}
