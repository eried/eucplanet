package com.eried.eucplanet.hud.protocol

/** Observable consumer state for the watch map transport. */
data class WatchMapSnapshot(
    val frame: WatchMapFrame?,
    val route: WatchMapRoute?,
    val linkLive: Boolean,
    val fixLive: Boolean,
)

/**
 * Orders presence echoes, phone process sessions, frames, and route Assets.
 * Cached frame and route data survive [begin], but never regain live status
 * until a frame for the new viewer epoch is accepted.
 */
class WatchMapSession {
    private data class SentPresence(
        val sequence: Long,
        val atElapsedMs: Long,
    )

    private var viewerId: String = ""
    private var viewerEpoch: Long = -1L
    private val sentPresences = ArrayDeque<SentPresence>(2)

    private var phoneSessionId: String? = null
    private val retiredPhoneSessions = mutableSetOf<String>()
    private var lastFrameSequence = -1L
    private var lastPresenceSequence = -1L
    private var acceptedFrameAtElapsedMs = -1L
    private var acceptedEchoSentAtElapsedMs = -1L
    private var hasFrameForCurrentBegin = false

    private var frame: WatchMapFrame? = null
    private var route: WatchMapRoute? = null

    fun begin(viewerId: String, viewerEpoch: Long) {
        require(viewerId.isNotBlank())
        require(viewerEpoch >= 0L)
        this.viewerId = viewerId
        this.viewerEpoch = viewerEpoch
        sentPresences.clear()
        phoneSessionId = null
        retiredPhoneSessions.clear()
        lastFrameSequence = -1L
        lastPresenceSequence = -1L
        acceptedFrameAtElapsedMs = -1L
        acceptedEchoSentAtElapsedMs = -1L
        hasFrameForCurrentBegin = false
    }

    fun sentPresence(sequence: Long, atElapsedMs: Long) {
        if (sequence < 0L || atElapsedMs < 0L) return
        sentPresences.removeAll { it.sequence == sequence }
        sentPresences.addLast(SentPresence(sequence, atElapsedMs))
        while (sentPresences.size > 2) sentPresences.removeFirst()
    }

    fun acceptFrame(frame: WatchMapFrame, nowElapsedMs: Long): Boolean {
        if (!WatchMapProtocol.isValidFrame(frame)) return false
        if (frame.viewerId != viewerId || frame.viewerEpoch != viewerEpoch) return false
        val echoed = sentPresences.firstOrNull { it.sequence == frame.presenceSequence }
            ?: return false
        val echoAgeMs = elapsedDelta(nowElapsedMs, echoed.atElapsedMs) ?: return false
        if (echoAgeMs > WatchMapProtocol.LINK_STALE_MS) return false

        val currentPhoneSession = phoneSessionId
        if (currentPhoneSession == null) {
            phoneSessionId = frame.phoneSessionId
        } else if (frame.phoneSessionId == currentPhoneSession) {
            if (frame.sequence <= lastFrameSequence) return false
            if (frame.presenceSequence < lastPresenceSequence) return false
        } else {
            if (frame.phoneSessionId in retiredPhoneSessions) return false
            if (frame.presenceSequence <= lastPresenceSequence) return false
            retiredPhoneSessions += currentPhoneSession
            phoneSessionId = frame.phoneSessionId
            lastFrameSequence = -1L
        }

        if (frame.sequence <= lastFrameSequence) return false
        this.frame = frame
        lastFrameSequence = frame.sequence
        lastPresenceSequence = frame.presenceSequence
        acceptedFrameAtElapsedMs = nowElapsedMs
        acceptedEchoSentAtElapsedMs = echoed.atElapsedMs
        hasFrameForCurrentBegin = true
        return true
    }

    fun acceptRoute(route: WatchMapRoute): Boolean {
        if (!WatchMapProtocol.isValidRoute(route) || !hasFrameForCurrentBegin) return false
        val currentFrame = frame ?: return false
        if (!currentFrame.navigationActive || currentFrame.navigationSessionId.isBlank()) return false
        if (currentFrame.routeRevision <= 0L) return false
        if (route.navigationSessionId != currentFrame.navigationSessionId ||
            route.revision != currentFrame.routeRevision
        ) {
            return false
        }
        this.route = route
        return true
    }

    fun snapshot(nowElapsedMs: Long): WatchMapSnapshot {
        val currentFrame = frame
        val linkLive = acceptedFrameAtElapsedMs >= 0L &&
            elapsedDelta(nowElapsedMs, acceptedFrameAtElapsedMs)
                ?.let { it < WatchMapProtocol.LINK_STALE_MS } == true
        val fixLive = currentFrame?.let { current ->
            val fix = current.fix
            linkLive &&
                current.locationStatus == WatchMapLocationStatus.LIVE &&
                fix != null &&
                acceptedEchoSentAtElapsedMs >= 0L &&
                elapsedDelta(nowElapsedMs, acceptedEchoSentAtElapsedMs)?.let { elapsed ->
                    saturatingAdd(fix.ageMs, elapsed) <= current.fixMaxAgeMs
                } == true
        } == true
        val matchingRoute = route?.takeIf { candidate ->
            currentFrame != null &&
                currentFrame.navigationActive &&
                candidate.navigationSessionId == currentFrame.navigationSessionId &&
                candidate.revision == currentFrame.routeRevision
        }
        return WatchMapSnapshot(
            frame = currentFrame,
            route = matchingRoute,
            linkLive = linkLive,
            fixLive = fixLive,
        )
    }

    private fun elapsedDelta(nowElapsedMs: Long, thenElapsedMs: Long): Long? =
        if (nowElapsedMs >= thenElapsedMs) nowElapsedMs - thenElapsedMs else null

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}
