package com.eried.eucplanet.service

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.ui.settings.SettingsAutomation
import com.eried.eucplanet.util.SunCalculator
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.absoluteValue

/**
 * What the automations read from the wheel and send to it. Both phones hand
 * over their [WheelRepository] through [asAutomationWheel]; tests hand a fake.
 */
interface AutomationWheel {
    val connected: Boolean
    /** Signed, as the wheel reports it; the automations take its size. */
    val speedKmh: Float
    val lightOn: Boolean
    /** 0 while the link has not reported a signal. */
    val rssiDbm: Int
    val locked: Boolean
    val hasBeeperVolume: Boolean
    fun toggleLight()
    fun setLock(target: Boolean)
    fun setBeeperVolume(level: Int)
}

fun WheelRepository.asAutomationWheel(): AutomationWheel = object : AutomationWheel {
    private val repo = this@asAutomationWheel
    override val connected get() = repo.connectionState.value == ConnectionState.CONNECTED
    override val speedKmh get() = repo.wheelData.value.speed
    override val lightOn get() = repo.wheelData.value.lightOn
    override val rssiDbm get() = repo.wheelData.value.rssiDbm
    override val locked get() = repo.locked.value
    override val hasBeeperVolume get() = repo.wheelHasBeeperVolume.value
    override fun toggleLight() = repo.toggleLight()
    override fun setLock(target: Boolean) = repo.setLock(target)
    override fun setBeeperVolume(level: Int) = repo.setBeeperVolume(level)
}

/**
 * The phone's own media under the automations: speed-scaled media volume and
 * pause / resume by speed. Android drives the system music stream and media
 * keys (AndroidMediaAutomation); iOS gives an app neither for other apps'
 * audio, so it has [None] and leaves those settings rows out.
 */
interface MediaAutomation {
    /** Every frame. Speed-based media volume, gated by its own apply-when. */
    suspend fun evaluateVolume(settings: AppSettings)

    /** Every frame while pause or resume is on and Legal Mode Lockdown is off. */
    fun evaluateMediaControl(settings: AppSettings)

    /** The rider's baseline volume back, on disconnect, Stop All and switch-off. */
    fun restoreBaselineVolume()

    /** Forget the pause / resume state, without resuming anything. */
    fun resetMediaControl()

    object None : MediaAutomation {
        override suspend fun evaluateVolume(settings: AppSettings) = Unit
        override fun evaluateMediaControl(settings: AppSettings) = Unit
        override fun restoreBaselineVolume() = Unit
        override fun resetMediaControl() = Unit
    }
}

/**
 * The speed and signal automations: the headlight (sun schedule and the
 * walking-pace cutoff), the beeper volume by speed and the Bluetooth
 * proximity lock, plus the phone's media through [media]. The same class on
 * both phones; the rules it composes live in [HeadlightRules],
 * [HeadlightSlowPolicy], [ApplyWhenGate] and [ProximityLockEvaluator].
 *
 * @param lockedDown Legal Mode Lockdown is engaged.
 * @param location the phone's latest fix, for the sun schedule.
 */
class AutomationManager(
    private val wheel: AutomationWheel,
    private val lockedDown: () -> Boolean,
    private val location: () -> PhoneLocation?,
    private val media: MediaAutomation = MediaAutomation.None,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sun: (latitude: Double, longitude: Double, nowMs: Long) -> SunCalculator.SunResult =
        { lat, lon, now -> SunCalculator.calculateState(lat, lon, now) },
) : SettingsAutomation {
    companion object {
        /** Walking pace, the headlight's own default for the same idea. */
        const val BEEP_STOPPED_BELOW_KMH = 5f
        private const val TAG = "AutomationManager"
    }

    /**
     * Every non-suspending step is under this lock: [evaluate] runs on the
     * telemetry collector's worker thread while the resets arrive from the
     * main thread (disconnect, Stop All, Settings) and from the Flic callback
     * thread, and the policy state is plain fields. Nothing under it blocks.
     */
    private val lock = SynchronizedObject()

    private var lastLightCheckMs = 0L
    private var lastAutoToggleMs = 0L
    private var lastKnownLightOn: Boolean? = null

    private var lightSlowState = HeadlightSlowPolicy.State()

    /** True once the rider has actually ridden since connecting. Keeps the
     *  headlight automation alive while they are stopped; see
     *  [ApplyWhenGate.allowsSticky]. Cleared on disconnect. */
    private var rodeThisSession = false

    /** Last answer from the sun schedule. Cached because that calculation is
     *  the expensive part and only changes across minutes, while the slow
     *  cutoff has to be judged on every telemetry frame. */
    private var lastDarkEnough: Boolean? = null

    /** Proximity lock / unlock decisions. See [ProximityLockEvaluator]. */
    private val proximityLock = ProximityLockEvaluator()

    /**
     * Put the media volume back to the rider's baseline - the level it was at
     * before speed-based auto-volume started scaling it down. Called on
     * disconnect / Stop All / when the feature is disabled, so the app never
     * leaves the music turned down after the rider stops.
     */
    override fun restoreBaselineVolume() = media.restoreBaselineVolume()

    private val _autoLightsSuspended = MutableStateFlow(false)
    override val autoLightsSuspended: StateFlow<Boolean> = _autoLightsSuspended.asStateFlow()

    /** Called from the UI / Flic paths whenever the user toggles the light manually. */
    override fun notifyManualLightChange() = synchronized(lock) { suspendLights() }

    private fun suspendLights() {
        // Legal Mode Lockdown is temporary, so its light button must not leave
        // auto-lights suspended for the rest of the session. Guarded here so
        // every caller is covered, not just the two we know about today.
        if (lockedDown()) return
        if (!_autoLightsSuspended.value) {
            Log.i(TAG, "Auto-lights suspended for this session (manual change)")
        }
        _autoLightsSuspended.value = true
    }

    /** Called on wheel reconnect or when the auto-lights setting is toggled. */
    override fun clearLightsSuspension() = synchronized(lock) {
        if (_autoLightsSuspended.value) {
            Log.i(TAG, "Auto-lights suspension cleared")
        }
        _autoLightsSuspended.value = false
        lastKnownLightOn = null
        // A new session starts with the beam under the schedule's control. A
        // cutoff left engaged from the last ride would otherwise keep the
        // light off until this one passed the rearm speed.
        lightSlowState = HeadlightSlowPolicy.State()
    }

    private val _autoLockSuspended = MutableStateFlow(false)
    override val autoLockSuspended: StateFlow<Boolean> = _autoLockSuspended.asStateFlow()

    /** Called whenever the rider locks or unlocks by hand: their choice stands
     *  for the rest of the session, the proximity automation steps aside. */
    override fun notifyManualLockChange() = synchronized(lock) {
        if (lockedDown()) return@synchronized
        if (!_autoLockSuspended.value) {
            Log.i(TAG, "Proximity lock suspended for this session (manual change)")
        }
        _autoLockSuspended.value = true
    }

    /** Called on wheel reconnect, from the Needs attention Fix, or when the
     *  proximity-lock settings change: the automation follows them again. */
    override fun clearLockSuspension() = synchronized(lock) {
        if (_autoLockSuspended.value) {
            Log.i(TAG, "Proximity lock suspension cleared")
        }
        _autoLockSuspended.value = false
        proximityLock.reset()
    }

    // Beeper volume by speed: the headlight's walking-pace gate, with no hold,
    // so the wheel is already quiet as it rolls to a stop and the power-off
    // beep that follows is soft. Loud again 2 km/h over, at once: the slower
    // headlight re-arm is about a walk lighting the beam, not the beeper.
    // Sent only when the level changes.
    private var beepSlowState = HeadlightSlowPolicy.State()
    private var beepSentLevel: Int? = null

    private fun evaluateBeepVolume(settings: AppSettings) {
        val h = settings.horn
        if (!h.beepVolumeBySpeed || !wheel.hasBeeperVolume) {
            // Disconnected or switched off: forget what was sent, so the next
            // connect or switch-on sends the right level again.
            beepSlowState = HeadlightSlowPolicy.State()
            beepSentLevel = null
            return
        }
        beepSlowState = HeadlightSlowPolicy.step(
            beepSlowState,
            speedKmh = wheel.speedKmh.absoluteValue,
            thresholdKmh = BEEP_STOPPED_BELOW_KMH,
            nowMs = clock(),
            enabled = true,
            holdMs = 0L,
            rearmGapKmh = 2f,
            rearmHoldMs = 0L,
        )
        val level = if (beepSlowState.forcedOff) h.beepVolumeStopped else h.beepVolumeRiding
        if (level != beepSentLevel) {
            Log.i(TAG, "Beeper volume -> $level (${if (beepSlowState.forcedOff) "stopped" else "riding"})")
            wheel.setBeeperVolume(level)
            beepSentLevel = level
        }
    }

    /** Reset the throttle so the next tick re-evaluates immediately. */
    override fun triggerImmediateLightEvaluation() = synchronized(lock) {
        lastLightCheckMs = 0L
        lastDarkEnough = null
    }

    /**
     * Called every telemetry tick (~250ms). Evaluates automation rules.
     */
    suspend fun evaluate(settings: AppSettings) {
        // Legal Mode Lockdown stops media control and proximity auto-lock.
        // Auto lights and auto volume keep running: lights are a safety
        // behaviour and the light button is on the locked screen anyway.
        val lockedDown = lockedDown()
        val connectedNow = wheel.connected
        if (!connectedNow) rodeThisSession = false
        else if (wheel.speedKmh.absoluteValue >= ApplyWhenGate.RIDING_KMH) {
            rodeThisSession = true
        }
        detectManualLightChange(settings)
        if (!_autoLightsSuspended.value) evaluateLights(settings)
        media.evaluateVolume(settings)
        evaluateBeepVolume(settings)
        val mc = settings.mediaControl
        if (!lockedDown && (mc.pauseEnabled || mc.resumeEnabled)) media.evaluateMediaControl(settings)
        // Media speed control is parked: it needs notification access the app
        // no longer asks for, and most players ignored the rate anyway. Its
        // evaluation is left intact in AndroidMediaAutomation for whoever
        // revives it.
        // lockEnabled is the whole feature's switch; unlockEnabled is a
        // sub-option of it, and the settings screen only draws the unlock
        // switch while this one is on. Gating on either used to keep the
        // unlock half running invisibly after the rider switched the feature
        // off - they could see no switch for it and had no way to stop it.
        // Resetting on the off path also covers every route that turns it off
        // (settings, the dashboard long-press, restoring a backup).
        if (!lockedDown && settings.proximityLock.lockEnabled && !_autoLockSuspended.value) evaluateProximityLock(settings)
        else synchronized(lock) { proximityLock.reset() }
    }

    /** Watch telemetry: if the wheel's light state flips without a recent auto-toggle, it's a manual change. */
    private fun detectManualLightChange(settings: AppSettings) = synchronized(lock) {
        val current = wheel.lightOn
        val previous = lastKnownLightOn
        lastKnownLightOn = current
        if (previous == null || previous == current) return@synchronized
        if (lastAutoToggleMs == 0L) return@synchronized
        val sinceAutoToggle = clock() - lastAutoToggleMs
        if (sinceAutoToggle > settings.autoToggleGraceMs.toLong()) {
            suspendLights()
        }
    }

    private fun evaluateLights(settings: AppSettings) = synchronized(lock) {
        val now = clock()
        val location = location()

        // Only the sun calculation is throttled, and its answer is cached for
        // the frames in between. The cutoff has to be judged on every frame:
        // under the throttle, a minute by default, its three second hold could
        // never be satisfied and the light stayed on all the way to the
        // crossing. A long cycle is right for the sun and wrong for this.
        val interval = if (location == null) settings.autoLightNoGpsRetryMs.toLong()
                       else settings.automationLightCheckIntervalMs.toLong()
        if (now - lastLightCheckMs >= interval) {
            lastLightCheckMs = now
            if (location != null) {
                lastDarkEnough = when (val r = sun(location.latitude, location.longitude, now)) {
                    is SunCalculator.SunResult.Normal -> {
                        val onAt = r.sunsetMillis - settings.lights.onMinutesBefore * 60_000L
                        val offAt = r.sunriseMillis + settings.lights.offMinutesAfter * 60_000L
                        now >= onAt || now <= offAt
                    }
                    SunCalculator.SunResult.PolarNight -> true
                    SunCalculator.SunResult.MidnightSun -> false
                }
            }
        }

        val decision = HeadlightRules.decide(
            slow = lightSlowState,
            input = HeadlightRules.Input(
                applyWhen = settings.lights.applyWhen,
                connected = wheel.connected,
                rodeThisSession = rodeThisSession,
                offWhenSlow = settings.lights.offWhenSlow,
                thresholdKmh = settings.lights.offBelowKmh,
                speedKmh = wheel.speedKmh.absoluteValue,
                darkEnough = lastDarkEnough,
                nowMs = now,
            ),
        )
        lightSlowState = decision.slow
        val shouldBeOn = decision.beamOn ?: return@synchronized

        val currentLightOn = wheel.lightOn
        // The decision runs on every telemetry frame now, so a wheel that does
        // not act on the command must not be asked several times a second.
        // Families with no BLE light command return early inside toggleLight
        // without changing the reported state or starting its own cooldown, so
        // this window is ours to hold. It is the same one the manual-change
        // detector uses, so an automatic toggle is never read as the rider's.
        val settled = now - lastAutoToggleMs >= settings.autoToggleGraceMs.toLong()
        if (shouldBeOn != currentLightOn && settled) {
            val what = if (shouldBeOn) "ON" else "OFF"
            Log.i(TAG, "Auto-lights: turning " + what +
                " (dark=" + lastDarkEnough + ", slowOff=" + lightSlowState.forcedOff + ")")
            wheel.toggleLight()
            lastAutoToggleMs = now
        }
    }

    /**
     * Clear media-control state on disconnect / Stop All / disable. We deliberately
     * do NOT auto-resume here - playback is left wherever it is (the rider can press
     * play) rather than risk blasting audio when a ride ends.
     */
    override fun resetMediaControl() = media.resetMediaControl()

    /**
     * Bluetooth-signal proximity lock / unlock. The decision lives in
     * [ProximityLockEvaluator]; this only supplies the reading and carries out
     * the answer. Locking needs a live link, so it runs while the signal is
     * fading - a full disconnect leaves nothing to send over (documented
     * limitation).
     */
    private fun evaluateProximityLock(settings: AppSettings) = synchronized(lock) {
        if (!wheel.connected) return@synchronized
        val rssi = wheel.rssiDbm
        when (
            proximityLock.evaluate(
                nowMs = clock(),
                rssiDbm = rssi,
                locked = wheel.locked,
                settings = settings.proximityLock
            )
        ) {
            ProximityLockEvaluator.Action.LOCK -> {
                wheel.setLock(true)
                Log.i(TAG, "Proximity: locking (rssi=$rssi dBm)")
            }
            ProximityLockEvaluator.Action.UNLOCK -> {
                wheel.setLock(false)
                Log.i(TAG, "Proximity: unlocking (rssi=$rssi dBm)")
            }
            ProximityLockEvaluator.Action.NONE -> Unit
        }
    }

    /** Clear proximity-lock state entirely, for Stop All and for disable. */
    override fun resetProximityLock() = synchronized(lock) { proximityLock.reset() }

    /**
     * The wheel disconnected. Keeps the arming so a rider who walked out of
     * range and came back still gets the auto-unlock; see
     * [ProximityLockEvaluator.onLinkLost].
     */
    fun onProximityLinkLost() = synchronized(lock) { proximityLock.onLinkLost() }
}
