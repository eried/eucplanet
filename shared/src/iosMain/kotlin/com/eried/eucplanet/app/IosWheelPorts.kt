package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelProfile
import com.eried.eucplanet.data.repository.AccelSplitReset
import com.eried.eucplanet.data.repository.AppNotices
import com.eried.eucplanet.data.repository.EventAnnouncer
import com.eried.eucplanet.data.repository.ExternalGpsFeed
import com.eried.eucplanet.data.repository.GpsFix
import com.eried.eucplanet.data.repository.LegalLockdownGate
import com.eried.eucplanet.data.repository.PhoneDevice
import com.eried.eucplanet.data.repository.PhoneHorn
import com.eried.eucplanet.data.repository.PhoneImu
import com.eried.eucplanet.data.repository.PhoneImuSample
import com.eried.eucplanet.data.repository.RideRecording
import com.eried.eucplanet.data.repository.RouteKeepAlive
import com.eried.eucplanet.data.repository.TyrePressureFeed
import com.eried.eucplanet.data.repository.WheelAlarms
import com.eried.eucplanet.data.repository.WheelProfiles
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.tpms.TpmsReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.UIKit.UIDevice

// The iOS side of WheelRepositoryPorts. Each feature that is not on iOS yet
// (alarms, voice, trips, TPMS caps, external GPS, the phone IMU) answers as
// the Android app does with that feature switched off, and is replaced when
// the feature lands; docs/IOS_PARITY.md tracks which.

/** Alarms are not on iOS yet: no rule fires. */
internal object NoAlarms : WheelAlarms {
    override fun evaluate(data: WheelData) = Unit
    override fun stopConstantTone() = Unit
}

/** No tone player on iOS yet, so no audio route to hold open. */
internal object NoRouteKeepAlive : RouteKeepAlive {
    override fun startRouteKeepAlive(quietMs: Long) = Unit
    override fun stopRouteKeepAlive() = Unit
}

/** Voice is not on iOS yet: event lines go to the log. */
internal object LogAnnouncer : EventAnnouncer {
    override fun announceEvent(text: String) { Log.i("Voice", text) }
}

/**
 * Per-wheel profiles in memory until the Room database moves to the shared
 * module: tiltback, alarm and calibration restore within a session.
 */
internal class MemoryWheelProfiles : WheelProfiles {
    private val lock = Mutex()
    private val byName = mutableMapOf<String, WheelProfile>()
    override suspend fun getByName(name: String): WheelProfile? = lock.withLock { byName[name] }
    override suspend fun upsert(profile: WheelProfile) = lock.withLock { byName[profile.bleName] = profile }
}

/** The phone accelerometer is not read on iOS yet. */
internal object NoPhoneImu : PhoneImu {
    override val imu: StateFlow<PhoneImuSample?> = MutableStateFlow<PhoneImuSample?>(null).asStateFlow()
    override fun start() = Unit
    override fun stop() = Unit
}

/** External GPS boxes are not on iOS yet. */
internal object NoExternalGps : ExternalGpsFeed {
    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.DISCONNECTED).asStateFlow()
    override val currentSample: StateFlow<ExternalGpsSample?> =
        MutableStateFlow<ExternalGpsSample?>(null).asStateFlow()
}

/** Legal Mode Lockdown is not on iOS yet: never engaged. */
internal object NoLockdown : LegalLockdownGate {
    override fun isEngaged(): Boolean = false
    override fun onLegalModeActivated() = Unit
}

/** TPMS caps are not on iOS yet; no paired sensor reading. */
internal object NoTyrePressure : TyrePressureFeed {
    override val current: StateFlow<TpmsReading?> = MutableStateFlow<TpmsReading?>(null).asStateFlow()
    override fun submitWheel(kpa: Float, nowMs: Long) = Unit
}

internal object NoAccelSplits : AccelSplitReset {
    override fun reset() = Unit
}

/** Trip recording is not on iOS yet: never recording, no phone fix. */
internal object NoRecording : RideRecording {
    override val recording: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun currentFix(): GpsFix? = null
}

/** No snackbar host on iOS yet: notices go to the log. */
internal object LogNotices : AppNotices {
    override fun post(message: String) { Log.i("Notice", message) }
}

/** No horn sound on iOS yet, so a horn tap uses the wheel or the fallback. */
internal object NoPhoneHorn : PhoneHorn {
    override val isReady: Boolean = false
    override fun play(): Boolean = false
    override fun externalOutputActive(): Boolean = false
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
