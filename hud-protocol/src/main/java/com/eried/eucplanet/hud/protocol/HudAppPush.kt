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
    const val ANSWER_OLDER: String = "older"
    const val ANSWER_TOO_BIG: String = "too-big"

    /** Whether a HUD that paired with this protocol version takes the posts.
     *  A HUD from before the version fields reports 0 for both. */
    fun canReceive(hudMajor: Int, hudMinor: Int): Boolean =
        hudMajor == HudState.PROTOCOL_MAJOR && hudMinor >= MIN_MINOR

    /** What the phone can do with the app it carries, for the linked HUD. */
    enum class Offer {
        /** The HUD runs an older app, or another build of the same version. */
        SEND,
        /** The HUD already runs this very build. Sending it again is
         *  allowed, a tester may want a clean copy, but the phone asks first. */
        SAME_BUILD,
        /** Nothing to send: the HUD runs a newer app, which Android would
         *  refuse to take back, or a version is unknown. */
        NONE,
    }

    /**
     * Testers move between builds of one version all the time and Android
     * installs the same number over itself, so only a step back is ruled
     * out. 0 for a version code means unknown, and nothing is offered on a
     * guess. An empty build on either side reads as another build.
     */
    fun offer(
        hudVersionCode: Int,
        hudBuild: String,
        carriedVersionCode: Int,
        carriedBuild: String,
    ): Offer = when {
        hudVersionCode <= 0 || carriedVersionCode <= 0 -> Offer.NONE
        hudVersionCode > carriedVersionCode -> Offer.NONE
        hudVersionCode < carriedVersionCode -> Offer.SEND
        hudBuild.isNotEmpty() && hudBuild == carriedBuild -> Offer.SAME_BUILD
        else -> Offer.SEND
    }

    /** Whether the HUD keeps a posted app: anything but a step back. */
    fun accepts(runningVersionCode: Int, postedVersionCode: Int): Boolean =
        postedVersionCode >= runningVersionCode
}
