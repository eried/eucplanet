package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.GpsFix
import com.eried.eucplanet.data.repository.PhoneDevice
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.RideRecording
import com.eried.eucplanet.data.repository.RouteKeepAlive
import com.eried.eucplanet.platform.format
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.UIKit.UIDevice

// The iOS side of WheelRepositoryPorts: what the shared WheelRepository takes
// from the rest of the app. docs/IOS_PARITY.md tracks what is still missing.

/**
 * The audio route is not held warm between sounds on iOS. Android plays a
 * silent track through a ride so the first beep is not clipped by a sleeping
 * Bluetooth output; IosTonePlayer holds the audio session only while tones
 * play and a short while after.
 */
internal object NoRouteKeepAlive : RouteKeepAlive {
    override fun startRouteKeepAlive(quietMs: Long) = Unit
    override fun stopRouteKeepAlive() = Unit
}

/**
 * The trip recorder as WheelRepository reads it: whether a trip records
 * (the phone's acceleration is held for it) and the phone's fix for the GPS
 * metric histories, in the units Android's TripRepository gives.
 */
internal object IosRideRecording : RideRecording {
    override val recording: StateFlow<Boolean> get() = IosTrips.recording
    override fun currentFix(): GpsFix? = IosLocation.fix.value?.let { gpsFix(it) }

    internal fun gpsFix(loc: PhoneLocation) = GpsFix(
        speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else null,
        altitudeM = if (loc.hasAltitude()) loc.altitude.toFloat() else null,
        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
    )
}


internal object IosPhoneDevice : PhoneDevice {
    override fun batteryPercent(): Int {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        return if (level < 0f) -1 else (level * 100f).toInt()
    }

    /** A buzz; iOS has no stock beep to play alongside it. */
    override fun hornFallback() = AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)

    // WheelRepository calls this from its background scope, never the main
    // thread, so waiting for the resource read here is fine.
    override fun text(res: StringResource, vararg args: Any): String {
        val raw = runBlocking { getString(res) }
        return if (args.isEmpty()) raw else raw.format(*args)
    }
}
