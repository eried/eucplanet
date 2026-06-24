package com.eried.eucplanet.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

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
 * Material 3 Snackbar, per the project convention.
 *
 * [post] is non-suspending and safe to call from any thread; the buffer drops
 * the oldest pending message if a burst arrives faster than the host can show
 * them (these are advisory, never load-bearing).
 */
@Singleton
class AppNotifier @Inject constructor() {

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Post a transient message to the root snackbar host. No-op-safe from
     *  any thread; never blocks. */
    fun post(message: String) {
        if (message.isBlank()) return
        _messages.tryEmit(message)
    }
}
