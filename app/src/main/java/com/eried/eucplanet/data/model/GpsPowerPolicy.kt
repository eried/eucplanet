package com.eried.eucplanet.data.model

/**
 * GPS power tiers, from most to least battery-hungry. Mapped to a fused
 * LocationRequest priority + interval by [com.eried.eucplanet.data.repository.TripRepository].
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
 *  - App not visible (screen off / backgrounded): high accuracy only while a
 *    wheel is connected (riding with the phone pocketed); otherwise fully OFF.
 *    Background + disconnected + not recording/navigating is the pure-idle state
 *    that should cost nothing (ultra battery saving).
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
    ): GpsTier = when {
        recording || navigating -> GpsTier.HIGH
        !appVisible -> if (connected) GpsTier.HIGH else GpsTier.OFF
        else -> GpsTier.BALANCED
    }
}
