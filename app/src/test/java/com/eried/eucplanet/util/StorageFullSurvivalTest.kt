package com.eried.eucplanet.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StorageFullSurvivalTest {

    private var before: Thread.UncaughtExceptionHandler? = null
    private val caught = AtomicReference<Throwable?>()
    private var arrived = CountDownLatch(1)

    @Before
    fun catchUncaught() {
        before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            caught.set(t)
            arrived.countDown()
        }
    }

    @After
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(before)
    }

    private fun uncaughtFromBackgroundTask(e: Throwable): Throwable {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { throw e }
        assertTrue("the failure reached the handler", arrived.await(5, TimeUnit.SECONDS))
        return caught.get()!!
    }

    @Test
    fun `a save that fails for lack of room in a background task keeps the app open`() {
        val t = uncaughtFromBackgroundTask(IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(survivesAsStorageFull(t))
    }

    @Test
    fun `so does a database that reports it is full`() {
        val t = uncaughtFromBackgroundTask(
            RuntimeException("database or disk is full (code 13 SQLITE_FULL)")
        )
        assertTrue(survivesAsStorageFull(t))
    }

    @Test
    fun `any other failure in a background task still closes the app`() {
        val t = uncaughtFromBackgroundTask(IllegalStateException("a bug"))
        assertFalse(survivesAsStorageFull(t))
    }

    @Test
    fun `a full phone on a thread that is going down still closes the app`() {
        val thread = Thread { throw IOException("write failed: ENOSPC (No space left on device)") }
        thread.start()
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertNotNull(caught.get())
        assertFalse("that thread is gone, the app cannot carry on", survivesAsStorageFull(caught.get()!!))
    }
}
