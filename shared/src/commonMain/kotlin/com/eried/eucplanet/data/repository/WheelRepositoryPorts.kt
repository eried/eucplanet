package com.eried.eucplanet.data.repository

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WheelProfile
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.tpms.TpmsReading
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

// What WheelRepository needs from the rest of the app, one small interface per
// collaborator, each with only the members it calls. The Android classes
// (AlarmEngine, VoiceService, TripRepository...) implement them as they are;
// iOS supplies its own or a no-op until that feature lands there.

/** The alarm engine: checks each frame against the rider's rules. */
interface WheelAlarms {
    fun evaluate(data: WheelData)
    fun stopConstantTone()
}

/** Keeps the audio route open between cues while riding (TonePlayer). */
interface RouteKeepAlive {
    fun startRouteKeepAlive(quietMs: Long)
    fun stopRouteKeepAlive()
}

/** Speaks one event line ("wheel locked") when voice events are on. */
interface EventAnnouncer {
    fun announceEvent(text: String)
}

/** Saved per-wheel profiles, by BLE name (the Room DAO on Android). */
interface WheelProfiles {
    suspend fun getByName(name: String): WheelProfile?
    suspend fun upsert(profile: WheelProfile)
}

/** Hidden debug switches; only the lock-at-any-speed one is read here. */
interface CheatFlags {
    val lockAtAnySpeed: StateFlow<Boolean>
}

/** The phone's own accelerometer, started only while a G metric is shown. */
interface PhoneImu {
    val imu: StateFlow<PhoneImuSample?>
    fun start()
    fun stop()
}

/** A paired external GPS box (RaceBox, Dragy). */
interface ExternalGpsFeed {
    val connectionState: StateFlow<ConnectionState>
    val currentSample: StateFlow<ExternalGpsSample?>
}

/** Legal Mode Lockdown: while engaged only the code lifts the limits. */
interface LegalLockdownGate {
    fun isEngaged(): Boolean
    fun onLegalModeActivated()
}

/** Tyre pressure: a paired cap's reading, and the wheel's own relayed one. */
interface TyrePressureFeed {
    val current: StateFlow<TpmsReading?>
    fun submitWheel(kpa: Float, nowMs: Long = System.currentTimeMillis())
}

/** The speed-split timer, reset when a new wheel connects. */
interface AccelSplitReset {
    fun reset()
}

/** One phone location fix, in the units the history buffers keep. */
data class GpsFix(
    /** km/h, or null when the fix has no speed. */
    val speedKmh: Float?,
    /** Metres, or null when the fix has no altitude. */
    val altitudeM: Float?,
    /** Metres, or null when the fix has no accuracy. */
    val accuracyM: Float?,
)

/** The trip recorder: whether a trip is recording, and the latest phone fix. */
interface RideRecording {
    val recording: StateFlow<Boolean>
    fun currentFix(): GpsFix?
}

/** A short notice shown on whatever screen the rider is on (a snackbar on Android). */
interface AppNotices {
    fun post(message: String)
}

/** The phone-side horn sound. */
interface PhoneHorn {
    val isReady: Boolean
    fun play(): Boolean
    fun externalOutputActive(): Boolean
}

/** Things only the phone itself knows or does. */
interface PhoneDevice {
    /** Phone battery in percent, or -1 when unknown. */
    fun batteryPercent(): Int

    /** A short beep and buzz, for a horn tap on a wheel without a horn. */
    fun hornFallback()

    /** A shared string in the rider's language, formatted like Android's getString. */
    fun text(res: StringResource, vararg args: Any): String
}
