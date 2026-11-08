package com.eried.eucplanet.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The notice bus both phones' root snackbar hosts read: what it lets
 * through, and what it drops when the host falls behind.
 */
class AppNotifierTest {

    @Test
    fun blankNoticesNeverReachTheHost() = runBlocking {
        val n = AppNotifier()
        val got = async(start = CoroutineStart.UNDISPATCHED) { n.messages.take(2).toList() }
        n.post("")
        n.post("   ")
        n.post("Trip saved")
        n.post("Backup failed")
        assertEquals(listOf("Trip saved", "Backup failed"), got.await())
    }

    @Test
    fun aBurstKeepsTheNewestEight() = runBlocking {
        val n = AppNotifier()
        val got = mutableListOf<String>()
        // A host that has not caught up yet: eleven posts overflow eight slots.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { n.messages.collect { got += it } }
        repeat(11) { n.post("m$it") }
        delay(100)
        job.cancel()
        // The newest eight always arrive; at most the one already handed over
        // to the host comes before them.
        assertEquals((3..10).map { "m$it" }, got.takeLast(8))
        assertTrue(got.size <= 9)
    }

    @Test
    fun noListenerNoBacklog() = runBlocking {
        // With no host listening (the locked screen has none), a notice is dropped
        // rather than shown later out of context.
        val n = AppNotifier()
        n.post("while locked")
        val got = async(start = CoroutineStart.UNDISPATCHED) { n.messages.take(1).toList() }
        n.post("after")
        assertEquals(listOf("after"), got.await())
    }
}
