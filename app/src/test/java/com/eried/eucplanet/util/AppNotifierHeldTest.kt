package com.eried.eucplanet.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AppNotifierHeldTest {

    @Test
    fun `a notice from before the screen is up is shown once it is`() = runBlocking {
        val notifier = AppNotifier()
        notifier.postForStart("Settings were reset")
        val shown = CopyOnWriteArrayList<String>()
        val screen = launch(Dispatchers.Unconfined) { notifier.messages.collect { shown += it } }
        delay(50)
        assertEquals(listOf("Settings were reset"), shown.toList())
        screen.cancel()
    }

    @Test
    fun `it is shown once, not again on the next screen`() = runBlocking {
        val notifier = AppNotifier()
        notifier.postForStart("Settings were reset")
        val first = CopyOnWriteArrayList<String>()
        val a = launch(Dispatchers.Unconfined) { notifier.messages.collect { first += it } }
        delay(50)
        a.cancel()
        val second = CopyOnWriteArrayList<String>()
        val b = launch(Dispatchers.Unconfined) { notifier.messages.collect { second += it } }
        delay(50)
        assertEquals(1, first.size)
        assertEquals(emptyList<String>(), second.toList())
        b.cancel()
    }

    @Test
    fun `with the screen up it goes out at once like any notice`() = runBlocking {
        val notifier = AppNotifier()
        val shown = CopyOnWriteArrayList<String>()
        val screen = launch(Dispatchers.Unconfined) { notifier.messages.collect { shown += it } }
        delay(50)
        notifier.postForStart("Settings were reset")
        notifier.post("Saved")
        delay(50)
        assertEquals(listOf("Settings were reset", "Saved"), shown.toList())
        screen.cancel()
    }
}
