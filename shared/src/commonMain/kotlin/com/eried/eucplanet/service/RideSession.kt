package com.eried.eucplanet.service

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.EventAnnouncer
import com.eried.eucplanet.data.repository.PhoneDevice
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.resources.*
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the ride session reads from the wheel (WheelRepository through [asRideWheel]). */
interface RideWheel {
    val connectionState: StateFlow<ConnectionState>
    val wheelData: StateFlow<WheelData>
    val chargeStatus: StateFlow<ChargeStatus>
}

fun WheelRepository.asRideWheel(): RideWheel = object : RideWheel {
    override val connectionState = this@asRideWheel.connectionState
    override val wheelData = this@asRideWheel.wheelData
    override val chargeStatus = this@asRideWheel.chargeStatus
}

/** The trip recorder the ride session starts and stops (TripRepository, IosTrips). */
interface RideRecorder {
    val recording: StateFlow<Boolean>
    suspend fun startRecording()
    suspend fun stopRecording()
}

/** The voice the ride session speaks through (VoiceService, IosVoice). */
interface RideVoice : EventAnnouncer {
    /** Every frame, so the load-style reports average over a real window. */
    fun recordTelemetry(data: WheelData)
    /** A new wheel, or the same one after a gap, must not average across the break. */
    fun clearTelemetryHistory()
    /** The periodic status report, over the live values. Quiet under Legal Mode Lockdown. */
    fun announceStatus(data: WheelData, settings: AppSettings, isRecording: Boolean)
    /** Audio is on headphones, Bluetooth or another output that is not the phone's own speaker. */
    fun externalOutputActive(): Boolean
}

/**
 * What runs around a ride, the same on both phones: what a connect and a
 * disconnect start and stop, what every telemetry frame drives (the
 * automations, the lights announcement, auto-record, the charge alerts), the
 * auto-record idle stop and the periodic voice reports.
 *
 * Android's WheelService and the iPhone's app graph both build one and feed
 * it; each keeps only what its own platform adds (the notification, the
 * widget, the engine sound and the HUD on Android; the Live Activity on iOS).
 * A change to any rule here reaches both phones. RideSessionTest pins them.
 *
 * @param scope where recordings start and stop and the loops run: the
 *   service's lifecycle on Android, the app's on iOS.
 * @param postChargeAlert shows a charge alert as the platform's notification.
 */
class RideSession(
    private val wheel: RideWheel,
    private val settings: SettingsRepository,
    private val automation: AutomationManager,
    private val trips: RideRecorder,
    private val voice: RideVoice,
    private val phone: PhoneDevice,
    private val scope: CoroutineScope,
    private val postChargeAlert: (title: String, text: String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "RideSession"
        private const val MOTION_MIN_KMH = AutoRecordPolicy.MOTION_MIN_KMH
    }

    // Written by the telemetry collector (a worker thread on Android) and
    // cleared on disconnect from the main thread.
    @Volatile
    private var lastLightOn: Boolean? = null

    // Timestamp of the last sample in motion (|speed| > MOTION_MIN_KMH) while
    // connected. Used by the idle-timeout loop to decide when to auto-stop.
    // Written from the telemetry collector and from the main thread loops, so
    // it must be visible to both.
    @Volatile
    private var lastMotionAtMs: Long = 0L

    private var chargeAlertState = ChargeAlertPolicy.State()
    private var voiceJob: Job? = null

    private val connected get() = wheel.connectionState.value == ConnectionState.CONNECTED

    /** Every telemetry frame. */
    suspend fun onFrame(data: WheelData, settings: AppSettings) {
        voice.recordTelemetry(data)
        automation.evaluate(settings)
        checkLightTransition(data.lightOn, settings)
        evaluateAutoRecordOnTelemetry(data, settings)
        checkChargeAlerts(data, settings)
    }

    /**
     * Connection transitions. The shared part of each runs first, then the
     * platform's own ([onConnected], [onDisconnected]). The first state seen
     * is where the session starts, not a change.
     */
    fun followConnection(
        onConnected: suspend (AppSettings) -> Unit = {},
        onDisconnected: suspend (AppSettings) -> Unit = {},
    ): Job = scope.launch {
        var lastConnectionState: ConnectionState? = null
        wheel.connectionState.collect { state ->
            if (lastConnectionState != null && state != lastConnectionState) {
                val settings = settings.get()
                when (state) {
                    ConnectionState.CONNECTED -> {
                        // Fresh connection: the session suspensions are spent,
                        // auto-lights and proximity lock follow their settings again
                        automation.clearLightsSuspension()
                        automation.clearLockSuspension()
                        if (settings.announceConnection) {
                            voice.announceEvent(phone.text(Res.string.voice_wheel_connected))
                        }
                        // Auto-record: start recording when wheel connects, unless the user
                        // gated it on "start in motion"; then the telemetry handler starts it.
                        if (settings.autoRecord && !settings.autoRecordStartInMotion &&
                            !trips.recording.value) {
                            scope.launch { trips.startRecording() }
                        }
                        onConnected(settings)
                    }
                    ConnectionState.DISCONNECTED -> {
                        // A new wheel, or the same one after a gap, must not
                        // average across the break.
                        voice.clearTelemetryHistory()
                        // Only announce if we were actually connected (not just reconnect cycling)
                        if (lastConnectionState == ConnectionState.CONNECTED && settings.announceConnection) {
                            voice.announceEvent(phone.text(Res.string.voice_wheel_disconnected))
                        }
                        lastLightOn = null
                        // Speed-based auto-volume drives the music down at
                        // standstill; put the rider's baseline back on
                        // disconnect so it isn't left turned down.
                        automation.restoreBaselineVolume()
                        automation.resetMediaControl()
                        automation.onProximityLinkLost()
                        onDisconnected(settings)
                    }
                    else -> {}
                }
            }
            lastConnectionState = state
        }
    }

    /**
     * The automations forget the session: Stop All, and the end of the
     * service. The connection collector may already be gone by then, so this
     * does not wait for a disconnect.
     */
    fun resetAutomations() {
        automation.restoreBaselineVolume()
        automation.resetMediaControl()
        automation.resetProximityLock()
    }

    // --- Auto-record motion gating ---

    private fun evaluateAutoRecordOnTelemetry(data: WheelData, settings: AppSettings) {
        if (!settings.autoRecord) return
        val moving = AutoRecordPolicy.isMoving(data.speed)
        if (moving) lastMotionAtMs = clock()

        // Motion-linked loop: start on first motion and restart after each idle auto-stop.
        if (AutoRecordPolicy.shouldStart(
                settings,
                moving = moving,
                connected = connected,
                alreadyRecording = trips.recording.value,
            )
        ) {
            scope.launch { trips.startRecording() }
        }
    }

    /**
     * The idle stop, once a second while a recording is open, and the idle
     * timer reset whenever a recording starts, so the stop-after-idle
     * threshold is measured from the fresh start, not from some stale
     * lastMotionAtMs left over from a prior session.
     */
    fun startAutoRecordIdleLoop() {
        scope.launch {
            trips.recording.collect { isRecording ->
                if (isRecording) lastMotionAtMs = clock()
            }
        }
        scope.launch {
            while (true) {
                // Only a recording can be auto-stopped. Waiting here instead of
                // checking once a second keeps an idle phone asleep; it used to
                // parse every setting on the main thread each second, all night.
                if (!trips.recording.value) trips.recording.first { it }
                delay(1000L)
                val settings = settings.currentOrLoad()
                if (!settings.autoRecord || !settings.autoRecordStartInMotion) continue
                if (!trips.recording.value) continue

                val connected = connected
                val moving = connected && kotlin.math.abs(wheel.wheelData.value.speed) > MOTION_MIN_KMH
                if (moving) {
                    lastMotionAtMs = clock()
                    continue
                }

                // Idle = not moving OR disconnected. Give a short grace period when we just started
                // recording so we don't instantly stop a recording that began before the wheel moves.
                if (lastMotionAtMs == 0L) lastMotionAtMs = clock()
                val idleMs = clock() - lastMotionAtMs
                if (AutoRecordPolicy.shouldStop(settings, recording = true, idleMs = idleMs)) {
                    Log.i(TAG, "Auto-stop: idle for ${idleMs / 1000}s (connected=$connected)")
                    trips.stopRecording()
                }
            }
        }
    }

    private fun checkLightTransition(current: Boolean, settings: AppSettings) {
        val previous = lastLightOn
        lastLightOn = current
        // Skip the first observation; that's the state we inherit on connect,
        // not a change the user should hear.
        if (previous == null || previous == current) return
        if (!settings.announceLights) return
        voice.announceEvent(
            phone.text(if (current) Res.string.voice_lights_on else Res.string.voice_lights_off)
        )
    }

    // --- Periodic voice announcements ---

    fun startVoiceLoop() {
        voiceJob?.cancel()
        voiceJob = scope.launch {
            while (true) {
                delay(settings.currentOrLoad().voiceIntervalSeconds * 1000L)
                // Re-read settings *after* the wait, not before it. If the rider
                // turns "Report status periodically" off during the countdown, the
                // pending report is dropped instead of one last one slipping through.
                // Only this periodic report is gated here, alarm/trigger/nav voice
                // lives on separate paths and is unaffected.
                val settings = settings.currentOrLoad()
                // Gate on the single visible "Enable periodic reports" toggle
                // (voiceEnabled). voicePeriodicEnabled is retired (the
                // dashboard quick-toggle drives voiceEnabled too).
                if (settings.voiceEnabled) {
                    val connected = connected
                    val data = wheel.wheelData.value
                    // "RIDING" = the same "in motion" test that auto-starts a trip
                    // recording (shared MOTION_MIN_KMH), so the two stay in lockstep.
                    val moving = connected && kotlin.math.abs(data.speed) > MOTION_MIN_KMH
                    val allowed = when (settings.voiceAnnounceWhen) {
                        "ALWAYS" -> true
                        "RIDING" -> moving
                        else -> connected   // "CONNECTED" + any legacy/unknown value
                    }
                    if (!allowed) continue
                    // Extra opt-in condition: only speak while audio is on an
                    // external output (headphones / Bluetooth / wired / USB).
                    if (settings.voiceAnnounceRequireExternal && !voice.externalOutputActive()) continue
                    voice.announceStatus(data, settings, isRecording = trips.recording.value)
                }
            }
        }
    }

    fun stopVoiceLoop() {
        voiceJob?.cancel()
        voiceJob = null
    }

    // --- Charge alerts ---

    /**
     * Tell the rider when the pack passes 80%, their own level, or
     * finishes, if they asked.
     *
     * Runs on every telemetry frame, which is why the deciding is in
     * [ChargeAlertPolicy] and only the posting is the platform's.
     */
    private fun checkChargeAlerts(data: WheelData, settings: AppSettings) {
        val step = ChargeAlertPolicy.step(
            chargeAlertState,
            wheel.chargeStatus.value,
            data.batteryPercent,
            want80 = settings.chargingNotify80,
            wantFull = settings.chargingNotifyFull,
            customMark = if (settings.chargingNotifyCustom) settings.chargingNotifyCustomPct else 0,
        )
        chargeAlertState = step.state
        when (step.alert) {
            ChargeAlertPolicy.Alert.AT_80 -> postChargeAlert(
                phone.text(Res.string.charge_alert_80_title), phone.text(Res.string.charge_alert_80_text),
            )
            ChargeAlertPolicy.Alert.FULL -> postChargeAlert(
                phone.text(Res.string.charge_alert_full_title), phone.text(Res.string.charge_alert_full_text),
            )
            ChargeAlertPolicy.Alert.CUSTOM -> postChargeAlert(
                phone.text(Res.string.charge_alert_custom_title, settings.chargingNotifyCustomPct),
                phone.text(Res.string.charge_alert_custom_text),
            )
            ChargeAlertPolicy.Alert.NONE -> Unit
        }
    }
}
