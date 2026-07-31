package com.eried.eucplanet.data.model

/**
 * GPS power tiers, from most to least battery-hungry. Mapped to a fused
 * LocationRequest priority + interval by [com.eried.eucplanet.data.repository.TripRepository].
 */
enum class GpsTier { HIGH, BALANCED, LOW }

/**
 * Decides how hard the phone GPS should work right now, so we never burn the
 * 1 Hz high-accuracy stream when nothing needs it.
 *
 * The rule (rider-specified):
 *  - Recording or navigating always needs precise 1 Hz.
 *  - App not visible (screen off / backgrounded): high accuracy only while a
 *    wheel is connected (riding with the phone pocketed); otherwise a low-power
 *    keep-warm fix so the Navigator / a fresh connect are still ready.
 *  - App visible but idle: balanced is enough to show a position and keep a fix
 *    warm without spinning the GPS chip at full rate.
 *
 * GPS is never turned fully off here - the coarsest state is [GpsTier.LOW], so
 * a position is always a beat away when the rider opens the Navigator or a wheel
 * comes back. Full stop is a separate, explicit action (Stop all).
 */
object GpsPowerPolicy {
    fun tierFor(
        recording: Boolean,
        navigating: Boolean,
        connected: Boolean,
        appVisible: Boolean,
    ): GpsTier = when {
        recording || navigating -> GpsTier.HIGH
        !appVisible -> if (connected) GpsTier.HIGH else GpsTier.LOW
        else -> GpsTier.BALANCED
    }
}
