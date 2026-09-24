package com.eried.eucplanet.wear.bridge

import com.eried.eucplanet.hud.protocol.WatchMapTileKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTileStoreTest {
    private val key = WatchMapTileKey("street", 3, 1, 2)
    private val otherKey = WatchMapTileKey("street", 3, 2, 2)

    @Test
    fun viewportRetentionRejectsLateHiddenAssets() {
        val store = PendingTileStore<String>()
        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 1L, "first"))

        store.retain("phone-a", listOf(otherKey))
        assertFalse(store.contains(key))
        assertFalse(store.offer("phone-a", key, 2L, "late"))

        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 2L, "fresh"))
    }

    @Test
    fun hideAndShowCreatesNewIdentityTicket() {
        val store = PendingTileStore<String>()
        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 1L, "old"))
        val old = store[key]

        store.retain("phone-a", emptyList())
        assertFalse(store.contains(key))
        val hiddenCompletion = store.complete(old!!)
        assertFalse(hiddenCompletion.accepted)
        assertTrue(hiddenCompletion.replacement == null)
        assertFalse(store.offer("phone-a", key, 1L, "hidden"))

        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 1L, "new"))
        val current = store[key]
        assertFalse(store.complete(old!!).accepted)
        assertSame(current, store[key])
        val currentCompletion = store.complete(current!!)
        assertTrue(currentCompletion.accepted)
        assertTrue(currentCompletion.replacement == null)
    }

    @Test
    fun newerGenerationReplacesOlderTicket() {
        val store = PendingTileStore<String>()
        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 1L, "old"))
        val old = store[key]
        assertFalse(store.offer("phone-a", key, 0L, "late"))
        assertTrue(store.offer("phone-a", key, 2L, "new"))
        val current = store[key]
        assertFalse(store.complete(old!!).accepted)
        assertSame(current, store[key])
        val currentCompletion = store.complete(current!!)
        assertTrue(currentCompletion.accepted)
        assertTrue(currentCompletion.replacement == null)
    }

    @Test
    fun equalGenerationRequiresExplicitReplacement() {
        val store = PendingTileStore<String>()
        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 1L, "asset"))
        assertFalse(store.offer("phone-a", key, 1L, "duplicate"))
        assertTrue(store.offer("phone-a", key, 1L, "inline", replaceSameGeneration = true))
        assertTrue(store[key]!!.value == "inline")
        assertFalse(store.offer("phone-a", key, 1L, "late-asset"))
    }
    @Test
    fun sourceChangeRejectsOldOffersAndCompletions() {
        val store = PendingTileStore<String>()
        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 1L, "old"))
        val old = store[key]

        store.retain("phone-b", listOf(key))
        val sourceChangeCompletion = store.complete(old!!)
        assertFalse(sourceChangeCompletion.accepted)
        assertTrue(sourceChangeCompletion.replacement == null)
        assertFalse(store.offer("phone-a", key, 1L, "late"))
        assertTrue(store.offer("phone-b", key, 1L, "new"))
        assertTrue(store.contains(key))
    }

    @Test
    fun completingReplacedEntryReturnsReplacementAndKeepsItPending() {
        val store = PendingTileStore<String>()
        store.retain("phone-a", listOf(key))
        assertTrue(store.offer("phone-a", key, 1L, "asset"))
        val inFlightAsset = store[key]!!

        assertTrue(store.offer("phone-a", key, 1L, "inline", replaceSameGeneration = true))

        val completion = store.complete(inFlightAsset)
        assertFalse(completion.accepted)
        assertSame(store[key], completion.replacement)
        assertTrue(completion.replacement!!.value == "inline")
        assertTrue(store.contains(key))
        assertTrue(store.complete(completion.replacement!!).accepted)
        assertFalse(store.contains(key))
    }

    @Test
    fun longViewportSequenceRetainsOnlyCurrentKeys() {
        val store = PendingTileStore<String>()
        repeat(1_000) { index ->
            val current = WatchMapTileKey("street", 3, index, 0)
            store.retain("phone-a", listOf(current))
            assertTrue(store.offer("phone-a", current, index.toLong(), index.toString()))
            if (index > 0) {
                val previous = WatchMapTileKey("street", 3, index - 1, 0)
                assertFalse(store.contains(previous))
            }
        }
    }
}
