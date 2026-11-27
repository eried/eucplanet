package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The offline router's warm-up after a start ([RoutingService.warmUpOffline]).
 * The first route after a start used to run out of time and fall back to
 * straight lines: BRouter rates every kind of road it meets by running the
 * profile on its tags, and keeps the ratings, so the first route in a town
 * pays for all of them and the next ones reuse them. The warm-up does that
 * first route near the rider ([location], when a fix comes within
 * [FIX_WAIT_MS]). It waits [AFTER_START_MS] so the start itself goes first;
 * a route asked for sooner stops it. Nothing runs without a downloaded square.
 *
 * It also waits for the app to be on screen. Android starts the process with
 * no screen to run an upload job or after a reboot, and nobody asks such a
 * start for a route: up to a minute of routing there was battery for nothing.
 */
object OfflineWarmUp {
    const val AFTER_START_MS = 5_000L
    const val FIX_WAIT_MS = 30_000L

    /** [onScreen] is each phone's app-in-front flow. */
    fun start(routing: RoutingService, location: Flow<GeoPoint?>, onScreen: Flow<Boolean>) =
        start(location, onScreen) { near -> routing.warmUpOffline(near) }

    /** The same with its parts handed in, so the waiting can be tested. */
    fun start(
        location: Flow<GeoPoint?>,
        onScreen: Flow<Boolean>,
        afterStartMs: Long = AFTER_START_MS,
        fixWaitMs: Long = FIX_WAIT_MS,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        warm: suspend (GeoPoint?) -> Unit,
    ) {
        scope.launch {
            onScreen.first { it }
            delay(afterStartMs)
            try {
                val near = withTimeoutOrNull(fixWaitMs) { location.filterNotNull().first() }
                warm(near)
            } catch (e: Exception) {
                Log.w("OfflineRouter", "warm-up after start failed: ${e.message}")
            }
        }
    }
}
