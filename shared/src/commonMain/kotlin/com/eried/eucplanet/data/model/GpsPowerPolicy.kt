package com.eried.eucplanet.data.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GPS power tiers, from most to least battery-hungry. Mapped to a fused
 * LocationRequest priority + interval by Android's TripRepository, and to a
 * CoreLocation accuracy and distance filter by the iPhone's IosGpsPower.
 */
enum class GpsTier { HIGH, BALANCED, LOW, OFF }

/**
 * A GENUINE GPS signal transition (satellites actually lost / regained while GPS
 * is on and needed), as opposed to the app's own power management. Only these
 * are voiced; turning GPS off to save power, or back on when you open the app /
 * connect a wheel, is never one of these.
 */
enum class GpsSignalEvent { ACQUIRED, LOST }

/**
 * Decides how hard the phone GPS should work right now, so we never burn the
 * 1 Hz high-accuracy stream when nothing needs it.
 *
 * The rule (rider-specified):
 *  - Recording or navigating always needs precise 1 Hz.
 *  - Sharing a live location is the same kind of promise: friends are watching
 *    this rider's dot, so it stays 1 Hz whatever the app is doing. A rider who
 *    opens the web viewer to look at the group backgrounds the app, and with no
 *    wheel connected that used to drop GPS to OFF, freezing their own dot for
 *    everyone else while their phone said Connected.
 *  - A visible watch map needs the same precise 1 Hz stream while the phone is
 *    backgrounded. So does a linked HUD with the Map screen in its carousel:
 *    the HUD draws the map from the phone's position and showed "no fix" the
 *    moment the rider left the app with no wheel connected.
 *  - App not visible (screen off / backgrounded): high accuracy only while a
 *    wheel is connected (riding with the phone pocketed); otherwise fully OFF.
 *    Background + disconnected + not recording/navigating/sharing and no
 *    visible watch map is the pure-idle state that should cost nothing.
 *  - App visible but idle: balanced is enough to show a position and keep a fix
 *    warm without spinning the GPS chip at full rate.
 *
 * Re-engaging from OFF re-warms the stream (a fresh fix in ~1-3 s), and recording
 * gates its first plotted point on a fresh, accurate fix, so a cold start never
 * logs the stale last-known position. Full stop (Stop all) is still separate.
 */
object GpsPowerPolicy {
    fun tierFor(
        recording: Boolean,
        navigating: Boolean,
        connected: Boolean,
        appVisible: Boolean,
        sharing: Boolean = false,
        watchMapVisible: Boolean = false,
        hudMapLinked: Boolean = false,
    ): GpsTier = when {
        recording || navigating || sharing || watchMapVisible || hudMapLinked -> GpsTier.HIGH
        !appVisible -> if (connected) GpsTier.HIGH else GpsTier.OFF
        else -> GpsTier.BALANCED
    }
}

/**
 * The step from a tier to OFF, shared by both phones. When [GpsPowerPolicy]
 * says nothing needs a position, a cheap LOW fix is held through the rider's
 * idle grace (gpsIdleOffDelaySec) and only then released, so a quick app
 * switch does not drop the GPS. Any tier above OFF cancels the pending off.
 */
class GpsIdleOff(
    /** Runs the block after the delay; the function it returns cancels it. */
    private val after: (delayMs: Long, block: () -> Unit) -> () -> Unit,
) {
    constructor(scope: CoroutineScope) : this({ ms, block ->
        val job: Job = scope.launch { delay(ms); block() }
        ({ job.cancel() })
    })

    private var pending: (() -> Unit)? = null

    /**
     * [tier] is what the policy picked, [current] what is applied now and
     * [apply] switches the GPS to a tier.
     */
    fun decide(tier: GpsTier, current: GpsTier?, idleOffDelaySec: Int, apply: (GpsTier) -> Unit) {
        if (tier == GpsTier.OFF) {
            // Already off, or an off-grace already pending: stay put. Do NOT
            // re-bridge or reschedule on every recompute - the wheel's connection
            // state flips during reconnect scans, and re-entering here would
            // thrash GPS on and off (spurious acquired/lost, wasted battery).
            if (current == GpsTier.OFF || pending != null) return
            if (idleOffDelaySec <= 0) {
                apply(GpsTier.OFF)
            } else {
                // Hold a cheap low-power fix through the grace, then fully off if
                // still idle - so a quick app switch doesn't drop the GPS.
                apply(GpsTier.LOW)
                pending = after(idleOffDelaySec * 1000L) {
                    apply(GpsTier.OFF)
                    pending = null
                }
            }
        } else {
            // Leaving idle: cancel any pending off and apply the live tier.
            pending?.invoke(); pending = null
            apply(tier)
        }
    }
}
