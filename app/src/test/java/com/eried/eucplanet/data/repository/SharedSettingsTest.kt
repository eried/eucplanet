package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every watcher of SettingsRepository.settings used to parse the settings
 * JSON itself: 65 parses on the main thread per cold start. These pin the
 * shared parse, so a later change cannot quietly bring one parse per watcher back.
 */
class SharedSettingsTest {

    @Test fun `many watchers cost one parse per change`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blob = MutableStateFlow(AppSettings(unitDistance = "km"))
        val parses = AtomicInteger()
        val shared = shareSettings(blob.map { parses.incrementAndGet(); it }, scope)

        repeat(20) { withTimeout(2000) { shared.first() } }
        assertEquals(1, parses.get())

        blob.value = AppSettings(unitDistance = "mi")
        withTimeout(2000) { shared.first { it.unitDistance == "mi" } }
        repeat(20) { withTimeout(2000) { shared.first() } }
        assertEquals(2, parses.get())
        scope.cancel()
    }

    @Test fun `nothing reaches a watcher before the first load`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blob = MutableStateFlow<AppSettings?>(null)
        val shared = shareSettings(blob.filterNotNull(), scope)

        // Before the store is read a watcher waits, it never gets defaults.
        assertNull(withTimeoutOrNull(200) { shared.first() })
        blob.value = AppSettings(unitDistance = "mi")
        assertEquals("mi", withTimeout(2000) { shared.first() }.unitDistance)
        scope.cancel()
    }

    @Test fun `a read error keeps the last settings and recovers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = AtomicInteger()
        // The first read gives km and then fails; the retry reads mi.
        val source = kotlinx.coroutines.flow.flow {
            if (reads.incrementAndGet() == 1) {
                emit(AppSettings(unitDistance = "km"))
                throw java.io.IOException("disk")
            }
            emit(AppSettings(unitDistance = "mi"))
        }
        val shared = shareSettings(source, scope, retryDelayMs = 10)

        assertEquals("mi", withTimeout(2000) { shared.first { it.unitDistance == "mi" } }.unitDistance)
        // A watcher that arrives after the error still gets settings at once.
        assertEquals("mi", withTimeout(500) { shared.first() }.unitDistance)
        scope.cancel()
    }
}
