package com.eried.eucplanet.util

/**
 * A value that costs something to read (a call into the system), read again
 * only once it is older than [maxAgeMs]. The clock is handed in so the caller
 * chooses it and a test can drive it.
 */
class AgedValue<T>(private val maxAgeMs: Long, private val read: () -> T) {
    private var has = false
    private var value: T? = null
    private var atMs = 0L

    @Synchronized
    fun get(nowMs: Long): T {
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

/** A value worked out from one input, kept until the input is another object. */
class ByInstance<K : Any, V>(private val make: (K) -> V) {
    private var key: K? = null
    private var value: V? = null

    @Synchronized
    fun get(k: K): V {
        if (key !== k) {
            value = make(k)
            key = k
        }
        @Suppress("UNCHECKED_CAST")
        return value as V
    }
}

/** Lets something through at most once per [periodMs]. */
class EveryMs(private val periodMs: Long) {
    private var lastMs = Long.MIN_VALUE / 2

    @Synchronized
    fun due(nowMs: Long): Boolean {
        if (nowMs - lastMs < periodMs && nowMs >= lastMs) return false
        lastMs = nowMs
        return true
    }
}
