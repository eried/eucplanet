package com.eried.eucplanet.share

import com.eried.eucplanet.platform.System
import kotlinx.coroutines.flow.StateFlow

data class Identity(val mode: IdentityMode, val name: String, val color: String, val icon: String?,
                    val avatarUrl: String?, val flag: String?, val shareStats: Boolean)

data class PeerState(val last: SharePayload, val lastSeenMs: Long, val trail: Trail,
                     val freshness: Freshness, val left: Boolean)

sealed class ShareState {
    object Idle : ShareState()
    /**
     * [nowMs] is the clock the whole group view is rendered against, refreshed
     * by every [LiveShare.ageTick]. It is part of the state on purpose:
     * re-classifying freshness alone produces an equal object once a peer has
     * settled into STALE, the CAS write is then a no-op and nothing emits, so
     * "15 s ago" used to sit frozen for the whole 105 s until LOST. With the
     * tick's clock in the state every tick is a real change, and readers show
     * an age computed from it rather than from a fresh currentTimeMillis()
     * sampled at composition time (which the recomposition never reached).
     */
    data class Joined(val link: ShareLink, val me: Identity, val peers: Map<String, PeerState>,
                      val connected: Boolean, val error: String?,
                      val nowMs: Long = System.currentTimeMillis()) : ShareState()
}

/**
 * The riders who are in the group right now: everyone in [ShareState.Joined.peers]
 * except those who announced they left and those whose last fix aged out to
 * [Freshness.LOST]. Self is never in the map (peers are keyed by the remote
 * sender id), so this is always "the others".
 *
 * One definition on purpose. The navigator's Share badge, the group dialog's
 * status line and its "no one else here yet" line all read this, so they cannot
 * show 2, 2 and "nobody" for the same room.
 */
val ShareState.Joined.activePeers: List<PeerState>
    get() = peers.values.filter { !it.left && it.freshness != Freshness.LOST }


/**
 * The live location share as the navigator sees it: the room, this rider's
 * own stats line, and joining, starting and leaving a group. Android's is
 * ShareSession (the relay's WebSocket and the AES-GCM payloads); a phone
 * without one has no share to offer.
 */
interface LiveShare {
    /** Idle, or Joined with the room, this rider and every friend's last position. */
    val state: StateFlow<ShareState>

    /** What this rider is broadcasting right now, or null when nothing real goes out. */
    val myStats: StateFlow<ShareStats?>

    /** Ordinal of the group dialog's last tab, kept for the process lifetime only. */
    var groupTabOrdinal: Int

    /** The identity the group sees for [mode]; PROFILE falls back to SESSION when nothing is linked. */
    suspend fun identityFor(mode: IdentityMode, sessionName: String, shareStats: Boolean): Identity

    /** Creates a new room and joins it as the first rider. */
    suspend fun start(identity: Identity): ShareLink

    /** Joins [link] as [identity]. */
    suspend fun join(link: ShareLink, identity: Identity)

    fun leave()

    /** Re-classifies every friend's freshness from the local clock. */
    fun ageTick()

    companion object {
        /** The relay closes with 1013 when a room already holds its maximum
         *  number of riders. Carried as a typed marker in [ShareState.Joined.error]
         *  so the UI can say "this group is full" without matching on words
         *  that a relay could reword or localize. */
        const val ERR_ROOM_FULL = "room_full"
        /** WebSocket close code the relay uses for "try again later": here it
         *  only ever means the room is at capacity. */
        const val CLOSE_ROOM_FULL = 1013
    }
}
