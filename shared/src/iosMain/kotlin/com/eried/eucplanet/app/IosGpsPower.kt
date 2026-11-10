package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.GpsIdleOff
import com.eried.eucplanet.data.model.GpsPowerPolicy
import com.eried.eucplanet.data.model.GpsTier
import com.eried.eucplanet.platform.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * The GPS power tiers on the iPhone: the same [GpsPowerPolicy] and idle grace
 * ([GpsIdleOff]) Android's TripRepository runs, mapped to CoreLocation's
 * accuracy and distance filter instead of a fused priority and interval.
 *
 * - HIGH (recording, or a wheel connected with the app in the background):
 *   best accuracy, every fix, kept running in the background.
 * - BALANCED (app in front, nothing recording): about 100 m, a fix every 25 m.
 * - LOW (the idle grace before OFF): about 1 km, a fix every 250 m.
 * - OFF (background, no wheel, not recording): updates stopped.
 *
 * Android's HIGH also honours the Advanced phone GPS interval; CoreLocation has
 * no interval, so HIGH delivers every fix. No navigator, live location share,
 * watch map or HUD map on iOS yet, so those inputs are always off here.
 */
internal object IosGpsPower {
    private const val TAG = "GpsPower"
    private val scope = MainScope()
    private val idleOff = GpsIdleOff(scope)

    /** A consumer asked for positions (Android's gpsStreamRequested). */
    private var requested = false
    private var started = false
    private var current: GpsTier? = null
    private var idleOffDelaySec = 30
    private val visible = MutableStateFlow(true)

    /** Starts the stream on demand, as Android's startLocationUpdates. */
    fun request() {
        if (!started) start()
        requested = true
        recompute()
    }

    /** Full stop, as Android's stopLocationUpdates (Stop all). */
    fun release() {
        requested = false
        current = null
        IosLocation.stopUpdates()
        IosLocation.keepInBackground(false)
    }

    private fun start() {
        started = true
        visible.value = UIApplication.sharedApplication.applicationState !=
            UIApplicationState.UIApplicationStateBackground
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            visible.value = false
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            visible.value = true
        }
        scope.launch { visible.collect { recompute() } }
        scope.launch { IosTrips.recording.collect { recompute() } }
        scope.launch { IosAppGraph.wheel.connectionState.collect { recompute() } }
        scope.launch {
            IosAppGraph.settings.settings.collect {
                idleOffDelaySec = it.gpsIdleOffDelaySec.coerceAtLeast(0)
                recompute()
            }
        }
    }

    private fun tier(appVisible: Boolean) = GpsPowerPolicy.tierFor(
        recording = IosTrips.recording.value,
        navigating = false,
        connected = IosAppGraph.wheel.connectionState.value == ConnectionState.CONNECTED,
        appVisible = appVisible,
    )

    private fun recompute() {
        if (!requested) return
        // CoreLocation keeps updates going in the background only when this was
        // allowed before the app left the front, so it follows what the policy
        // wants in the background, not the tier in force now.
        IosLocation.keepInBackground(tier(appVisible = false) == GpsTier.HIGH)
        idleOff.decide(tier(visible.value), current, idleOffDelaySec, ::apply)
    }

    private fun apply(tier: GpsTier) {
        if (tier == current) return
        current = tier
        when (tier) {
            GpsTier.OFF -> IosLocation.stopUpdates()
            GpsTier.HIGH -> IosLocation.startUpdates(BEST, NO_FILTER)
            GpsTier.BALANCED -> IosLocation.startUpdates(HUNDRED_METERS, 25.0)
            GpsTier.LOW -> IosLocation.startUpdates(KILOMETER, 250.0)
        }
        Log.i(TAG, "GPS tier=$tier")
    }

    // CoreLocation's kCLLocationAccuracy* and kCLDistanceFilterNone values.
    private const val BEST = -1.0
    private const val HUNDRED_METERS = 100.0
    private const val KILOMETER = 1000.0
    private const val NO_FILTER = -1.0
}
