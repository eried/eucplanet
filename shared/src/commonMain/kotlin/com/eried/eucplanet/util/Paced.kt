package com.eried.eucplanet.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A value that costs something to read (a call into the system), read again
 * only once it is older than [maxAgeMs]. The clock is handed in so the caller
 * chooses it and a test can drive it.
 */
class AgedValue<T>(private val maxAgeMs: Long, private val read: () -> T) {
    // Replaces @Synchronized, which does nothing on the iPhone.
    private val lock = SynchronizedObject()

    private var has = false
    private var value: T? = null
    private var atMs = 0L

    fun get(nowMs: Long): T {
        synchronized(lock) {
            // nowMs < atMs: the clock went back, the age cannot be trusted.
            if (!has || nowMs - atMs >= maxAgeMs || nowMs < atMs) {
                value = read()
                atMs = nowMs
                has = true
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }
}

/** A value worked out from one input, kept until the input is another object. */
class ByInstance<K : Any, V>(private val make: (K) -> V) {
    // Replaces @Synchronized, which does nothing on the iPhone.
    private val lock = SynchronizedObject()

    private var key: K? = null
    private var value: V? = null

    fun get(k: K): V {
        synchronized(lock) {
            if (key !== k) {
                value = make(k)
                key = k
            }
            @Suppress("UNCHECKED_CAST")
            return value as V
        }
    }
}

/** Lets something through at most once per [periodMs]. */
class EveryMs(private val periodMs: Long) {
    // Replaces @Synchronized, which does nothing on the iPhone.
    private val lock = SynchronizedObject()

    private var lastMs = Long.MIN_VALUE / 2

    fun due(nowMs: Long): Boolean {
        synchronized(lock) {
            if (nowMs - lastMs < periodMs && nowMs >= lastMs) return false
            lastMs = nowMs
            return true
        }
    }
}
