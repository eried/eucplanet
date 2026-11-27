package com.eried.eucplanet.util

import com.eried.eucplanet.data.repository.AppNotices
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * App-global transient-notification bus.
 *
 * Background code (services, singleton repositories, the activity's intent
 * handler) has no Compose scope, so it can't reach the per-screen
 * [com.eried.eucplanet.ui.common.LocalSnackbar]. Those call sites used to fall
 * back to a native Android Toast, which sits behind the soft keyboard and
 * doesn't match the rest of the UI. They now [post] here instead, and a single
 * root-level Snackbar host in MainActivity collects [messages] and shows them
 * over whatever screen is on top -- so every user-facing transient is a
 * Material 3 Snackbar, per the project convention. The iPhone's root host is
 * in MainViewController, the same snackbar over whatever screen is up.
 *
 * Neither phone turns a notice into a system notification: one posted while
 * the app is in the background reaches the snackbar host, never the lock
 * screen.
 *
 * [post] is non-suspending and safe to call from any thread; the buffer drops
 * the oldest pending message if a burst arrives faster than the host can show
 * them (these are advisory, never load-bearing).
 */
class AppNotifier : AppNotices {

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    // A lock and a deque where Android had a ConcurrentLinkedQueue, so the
    // same code runs on the iPhone.
    private val heldLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val heldForStart = ArrayDeque<String>()

    private fun takeHeld(): String? = kotlinx.atomicfu.locks.synchronized(heldLock) { heldForStart.removeFirstOrNull() }

    val messages: SharedFlow<String> = _messages.onSubscription {
        while (true) emit(takeHeld() ?: break)
    }

    /** Post a transient message to the root snackbar host. No-op-safe from
     *  any thread; never blocks. */
    override fun post(message: String) {
        if (message.isBlank()) return
        _messages.tryEmit(message)
    }

    /**
     * A notice about something found while the app was starting, before any
     * screen is there to show it. [post] would drop it: nobody is listening
     * yet. This one waits for the first screen. Only for what the rider must
     * be told about this start; anything else would turn up stale.
     */
    fun postForStart(message: String) {
        if (message.isBlank()) return
        if (_messages.subscriptionCount.value > 0) _messages.tryEmit(message) else kotlinx.atomicfu.locks.synchronized(heldLock) { heldForStart.addLast(message) }
    }
}
