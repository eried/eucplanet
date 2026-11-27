package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineWarmUpTest {

    @Test
    fun `a start with no screen does not warm the router up, opening the app does`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val onScreen = MutableStateFlow(false)
        val warmed = CompletableDeferred<GeoPoint?>()
        try {
            OfflineWarmUp.start(
                location = flowOf(GeoPoint(59.9, 10.7)),
                onScreen = onScreen,
                afterStartMs = 0L,
                fixWaitMs = 1_000L,
                scope = scope,
            ) { near -> warmed.complete(near) }

            // An upload job woke the process: nothing is on screen.
            delay(300)
            assertFalse("warmed up with the app hidden", warmed.isCompleted)

            onScreen.value = true
            val near = withTimeoutOrNull(5_000) { warmed.await() }
            assertEquals(GeoPoint(59.9, 10.7), near)
        } finally {
            scope.cancel()
        }
    }
}
