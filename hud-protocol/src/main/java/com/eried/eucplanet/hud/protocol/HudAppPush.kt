package com.eried.eucplanet.hud.protocol

/**
 * The phone handing the HUD its own app.
 *
 * The phone app carries the HUD app that was built alongside it. When the
 * HUD on the other end runs an older one, the rider can send it across the
 * link they already share, and the HUD asks to install it. The same door
 * lets the phone ask the HUD to remove itself. Both are plain HTTP posts to
 * the HUD's server, next to the state socket, and the HUD only takes them
 * from the phone it is linked to.
 */
object HudAppPush {
    /** First protocol minor whose HUD takes an app file or a remove request. */
    const val MIN_MINOR: Int = 21

    /** The HUD app file is posted here as the request body. */
    const val PATH_UPDATE: String = "/app/update"

    /** An empty post here asks the HUD to remove its own app. */
    const val PATH_REMOVE: String = "/app/remove"

    /** Largest file the HUD keeps. The HUD app is under 3 MB. */
    const val MAX_BYTES: Long = 32L * 1024 * 1024

    /** What the HUD answers to a posted file, as the response text. */
    const val ANSWER_READY: String = "ready"
    const val ANSWER_NOT_AN_APP: String = "not-an-app"
    const val ANSWER_NOT_NEWER: String = "not-newer"
    const val ANSWER_TOO_BIG: String = "too-big"

    /** Whether a HUD that paired with this protocol version takes the posts.
     *  A HUD from before the version fields reports 0 for both. */
    fun canReceive(hudMajor: Int, hudMinor: Int): Boolean =
        hudMajor == HudState.PROTOCOL_MAJOR && hudMinor >= MIN_MINOR

    /**
     * Whether the HUD runs an older app than the one the phone carries.
     * 0 on either side means unknown, and nothing is offered on a guess.
     */
    fun isOlder(hudVersionCode: Int, carriedVersionCode: Int): Boolean =
        hudVersionCode > 0 && carriedVersionCode > 0 && hudVersionCode < carriedVersionCode
}
