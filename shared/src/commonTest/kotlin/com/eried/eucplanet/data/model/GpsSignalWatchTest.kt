package com.eried.eucplanet.data.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "GPS acquired / lost" voice speaks only genuine signal changes while
 * tracking, on both phones: never the power-on fix, never GPS power
 * management, never outside a recording or guidance.
 */
class GpsSignalWatchTest {

    private fun watching(block: suspend (GpsSignalWatch, MutableList<GpsSignalEvent>) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watch = GpsSignalWatch(scope, lostAfterMs = 150L)
        val seen = mutableListOf<GpsSignalEvent>()
        val job = launch(Dispatchers.Default) { watch.events.collect { synchronizedAdd(seen, it) } }
        delay(20)
        try {
            block(watch, seen)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private fun synchronizedAdd(list: MutableList<GpsSignalEvent>, e: GpsSignalEvent) =
        kotlinx.atomicfu.locks.synchronized(lock) { list += e }

    private fun snapshot(list: MutableList<GpsSignalEvent>) =
        kotlinx.atomicfu.locks.synchronized(lock) { list.toList() }

    @Test fun `the first fix after tracking starts is silent`() = watching { w, seen ->
        w.startTracking(havePosition = false)
        w.onFix()
        delay(50)
        assertEquals(emptyList(), snapshot(seen))
    }

    @Test fun `a gap while tracking is lost and the next fix acquired`() = watching { w, seen ->
        w.startTracking(havePosition = false)
        w.onFix()
        delay(400)
        assertEquals(listOf(GpsSignalEvent.LOST), snapshot(seen))
        w.onFix()
        delay(50)
        assertEquals(listOf(GpsSignalEvent.LOST, GpsSignalEvent.ACQUIRED), snapshot(seen))
    }

    @Test fun `without tracking a gap says nothing`() = watching { w, seen ->
        w.onFix()
        delay(400)
        w.onFix()
        delay(50)
        assertEquals(emptyList(), snapshot(seen))
    }

    @Test fun `stopping tracking cancels a pending loss`() = watching { w, seen ->
        w.startTracking(havePosition = false)
        w.onFix()
        w.stopTracking()
        delay(400)
        assertEquals(emptyList(), snapshot(seen))
    }

    @Test fun `a recent fix carries into tracking and can be lost`() = watching { w, seen ->
        w.onFix()
        w.startTracking(havePosition = true)
        delay(400)
        assertEquals(listOf(GpsSignalEvent.LOST), snapshot(seen))
    }
}
