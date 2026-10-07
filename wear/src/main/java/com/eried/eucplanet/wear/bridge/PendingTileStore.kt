package com.eried.eucplanet.wear.bridge

import com.eried.eucplanet.hud.protocol.WatchMapTileKey

internal class PendingTileStore<T> {
    class Entry<T>(
        val sourceNodeId: String,
        val key: WatchMapTileKey,
        val deliveryGeneration: Long,
        val value: T,
    )

    private var sourceNodeId: String? = null
    private var visibleKeys: Set<WatchMapTileKey> = emptySet()
    private val entries = mutableMapOf<WatchMapTileKey, Entry<T>>()

    fun retain(sourceNodeId: String?, visibleKeys: Collection<WatchMapTileKey>) {
        val nextSource = sourceNodeId?.takeIf { it.isNotBlank() }
        val nextVisible = visibleKeys.toSet()
        if (nextSource != this.sourceNodeId) entries.clear()
        this.sourceNodeId = nextSource
        this.visibleKeys = nextVisible
        if (nextSource == null || nextVisible.isEmpty()) {
            entries.clear()
        } else {
            entries.keys.retainAll(nextVisible)
        }
    }

    fun offer(
        sourceNodeId: String,
        key: WatchMapTileKey,
        deliveryGeneration: Long,
        value: T,
        replaceSameGeneration: Boolean = false,
    ): Boolean {
        if (sourceNodeId != this.sourceNodeId || key !in visibleKeys || deliveryGeneration < 0L) {
            return false
        }
        val current = entries[key]
        if (current != null &&
            (deliveryGeneration < current.deliveryGeneration ||
                (deliveryGeneration == current.deliveryGeneration && !replaceSameGeneration))
        ) {
            return false
        }
        entries[key] = Entry(sourceNodeId, key, deliveryGeneration, value)
        return true
    }

    operator fun get(key: WatchMapTileKey): Entry<T>? = entries[key]

    operator fun contains(key: WatchMapTileKey): Boolean = key in entries

    class Completion<T>(
        val accepted: Boolean,
        val replacement: Entry<T>?,
    )

    fun complete(entry: Entry<T>): Completion<T> {
        val current = entries[entry.key]
        if (current !== entry) {
            return Completion(accepted = false, replacement = current)
        }
        entries.remove(entry.key)
        return Completion(accepted = true, replacement = null)
    }
}
