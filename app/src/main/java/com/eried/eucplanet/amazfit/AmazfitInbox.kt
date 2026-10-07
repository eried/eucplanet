package com.eried.eucplanet.amazfit

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bit of Amazfit bridge state that other parts of the app touch without
 * needing the bridge itself: the queue of one-shot events waiting for the
 * watch's next poll, and the record of when that watch last polled.
 *
 * Has no dependencies on purpose. [com.eried.eucplanet.wear.WatchVibrator]
 * (which `AlarmEngine` owns) queues vibrate hints here; routing it through the
 * full [AmazfitBridge] would pull `FlicManager` and friends into the alarm
 * graph and risk a Hilt cycle.
 *
 * Thread-safe: the HTTP worker threads drain while app threads enqueue.
 */
@Singleton
class AmazfitInbox @Inject constructor() {
    private val events = ConcurrentLinkedQueue<Map<String, Any>>()
    private val pollCount = AtomicInteger(0)

    /** Epoch millis of the most recent `/state` poll, 0 before the first. */
    @Volatile var lastPollAtMs: Long = 0L
        private set

    /** Device name from the watch's `info:` message, empty until it arrives. */
    @Volatile var watchName: String = ""

    fun enqueue(event: Map<String, Any>) {
        events.add(event)
    }

    /** Removes and returns every queued event, oldest first. */
    fun drainEvents(): List<Map<String, Any>> {
        val out = ArrayList<Map<String, Any>>()
        while (true) {
            val e = events.poll() ?: break
            out.add(e)
        }
        return out
    }

    fun notePoll(nowMs: Long) {
        lastPollAtMs = nowMs
        pollCount.incrementAndGet()
    }

    /** Polls since the previous call; the bridge samples this once a second
     *  to drive the delivery-rate badge. */
    fun takePollCount(): Int = pollCount.getAndSet(0)

    fun hasPolledWithin(windowMs: Long, nowMs: Long): Boolean =
        lastPollAtMs > 0L && nowMs - lastPollAtMs <= windowMs

    /**
     * Blocks until the queue is empty (a poll drained it) or [timeoutMs]
     * elapses. Used by the QUIT path, which runs right before the process is
     * torn down and wants the watch to have actually fetched the event.
     */
    fun awaitDrained(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (events.isNotEmpty()) {
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    val pendingCount: Int get() = events.size
}
