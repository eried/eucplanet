package com.eried.eucplanet.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Ignores rapid repeat events within [intervalMs]. Fix for black-screen flicker
 * when a double-tap triggers two navigations before the first one resumes.
 */
class MultipleEventsCutter(private val intervalMs: Long = 500L) {
    private var lastEventTime = 0L
    fun processEvent(event: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastEventTime >= intervalMs) {
            lastEventTime = now
            event()
        }
    }
}

@Composable
fun rememberDebouncedClick(intervalMs: Long = 500L, onClick: () -> Unit): () -> Unit {
    val cutter = remember { MultipleEventsCutter(intervalMs) }
    return { cutter.processEvent(onClick) }
}
