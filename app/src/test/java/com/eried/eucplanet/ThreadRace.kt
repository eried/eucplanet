package com.eried.eucplanet

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Sets [sides] against each other, one thread each, [rounds] calls apiece, and
 * hands back the first thing any of them threw. Null means they got along.
 *
 * For state the telemetry thread fills while the screen's thread clears it. A
 * side that is still running after [timeoutMs] counts as stuck and is reported
 * the same way, so a lock that never lets go fails the test too.
 */
fun raceThreads(
    rounds: Int = 200_000,
    timeoutMs: Long = 30_000L,
    vararg sides: (Int) -> Unit,
): Throwable? {
    val failure = AtomicReference<Throwable?>()
    val go = CountDownLatch(1)
    val threads = sides.map { side ->
        thread(isDaemon = true) {
            go.await()
            try {
                var i = 0
                while (i < rounds && failure.get() == null) side(i++)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
    }
    go.countDown()
    val deadline = System.currentTimeMillis() + timeoutMs
    for (t in threads) {
        t.join((deadline - System.currentTimeMillis()).coerceAtLeast(1L))
        if (t.isAlive) failure.compareAndSet(null, IllegalStateException("a side is stuck"))
    }
    return failure.get()
}
