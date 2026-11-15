package com.eried.eucplanet.data.model

import com.eried.eucplanet.platform.System
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * The GPS signal state machine behind the "GPS acquired / lost" voice, the
 * same on both phones (Android's TripRepository and the iPhone's GPS power
 * tiers feed it).
 *
 * It runs ONLY while tracking: a trip recording or navigation active, when
 * GPS is both needed AND the app is kept awake, so a stale stream means a
 * genuine satellite loss rather than the phone throttling background
 * location. It never voices the app's own power management (the idle
 * power-down, or re-engaging on app open or a wheel connect): entering and
 * leaving tracking is silent, and so is the first fix after GPS powers on.
 * Only a fix that ends a genuine loss is ACQUIRED, and only [lostAfterMs]
 * without a fix while tracking is LOST.
 */
class GpsSignalWatch(
    private val scope: CoroutineScope,
    private val lostAfterMs: Long = 10_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var tracking = false         // true while recording or navigating
    @Volatile private var hasFix = false           // FIXED (true) vs ACQUIRING (false) while tracking
    @Volatile private var acquiringFromLoss = false // this ACQUIRING followed a genuine loss, not a power-on
    @Volatile private var lastFixMs = 0L
    private var lossJob: Job? = null

    private val _events = MutableSharedFlow<GpsSignalEvent>(extraBufferCapacity = 4)
    /** Genuine GPS acquired / lost events; power management is never emitted here. */
    val events: SharedFlow<GpsSignalEvent> = _events.asSharedFlow()

    /** A fresh fix arrived. */
    fun onFix() {
        lastFixMs = clock()
        if (tracking && !hasFix) {
            hasFix = true
            // Announce "acquired" only if this fix ends a genuine loss - not the
            // first fix after powering GPS on (that transition is silent).
            if (acquiringFromLoss) {
                acquiringFromLoss = false
                _events.tryEmit(GpsSignalEvent.ACQUIRED)
            }
        }
        armLossWatchdog()
    }

    /** (Re)arm the no-fix watchdog. Fires only while tracking: if no fresh fix
     *  arrives within [lostAfterMs] the signal counts as genuinely lost. */
    private fun armLossWatchdog() {
        lossJob?.cancel(); lossJob = null
        if (!tracking) return
        lossJob = scope.launch {
            delay(lostAfterMs)
            if (tracking && hasFix) {
                hasFix = false
                acquiringFromLoss = true
                _events.tryEmit(GpsSignalEvent.LOST)
            }
        }
    }

    /**
     * Enter tracking (a recording or navigation just started). Silent: a
     * recent fix stays FIXED, otherwise we wait for the first fix, which is a
     * power-on and not voiced. [havePosition] says whether the phone still
     * holds a current position.
     */
    fun startTracking(havePosition: Boolean) {
        if (tracking) return
        tracking = true
        acquiringFromLoss = false
        hasFix = clock() - lastFixMs < lostAfterMs && havePosition
        if (hasFix) armLossWatchdog()
    }

    /** Leave tracking (no recording or navigation, or GPS off). Silent. */
    fun stopTracking() {
        tracking = false
        hasFix = false
        acquiringFromLoss = false
        lossJob?.cancel(); lossJob = null
    }
}
