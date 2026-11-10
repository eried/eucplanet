package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ApplyWhenIds
import com.eried.eucplanet.data.model.HornSettings
import com.eried.eucplanet.data.model.LightsSettings
import com.eried.eucplanet.data.model.MediaControlSettings
import com.eried.eucplanet.data.model.ProximityLockSettings
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.util.SunCalculator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The automations as both phones run them: the shared [AutomationManager]
 * over a fake wheel, a fixed clock and a fixed sun. The rules underneath have
 * their own tests; these pin what the manager does with their answers (the
 * command it sends, how often, and when it steps aside), which used to live
 * in Android's service and could only be checked by riding.
 */
class AutomationManagerTest {

    private class FakeWheel : AutomationWheel {
        override var connected = true
        override var speedKmh = 0f
        override var lightOn = false
        override var rssiDbm = 0
        override var locked = false
        override var hasBeeperVolume = false
        val lightToggles = mutableListOf<Boolean>()
        val locks = mutableListOf<Boolean>()
        val beeps = mutableListOf<Int>()
        /** Off for a wheel whose light command does nothing. */
        var lightFollows = true
        override fun toggleLight() {
            lightToggles += !lightOn
            if (lightFollows) lightOn = !lightOn
        }
        override fun setLock(target: Boolean) { locks += target; locked = target }
        override fun setBeeperVolume(level: Int) { beeps += level }
    }

    private class FakeMedia : MediaAutomation {
        var volumeFrames = 0
        var controlFrames = 0
        override suspend fun evaluateVolume(settings: AppSettings) { volumeFrames++ }
        override fun evaluateMediaControl(settings: AppSettings) { controlFrames++ }
        override fun restoreBaselineVolume() = Unit
        override fun resetMediaControl() = Unit
    }

    private val wheel = FakeWheel()
    private val media = FakeMedia()
    private var now = 1_000_000L
    private var lockedDown = false
    private var fix: PhoneLocation? = PhoneLocation(59.9, 10.7)
    private var sun: SunCalculator.SunResult = SunCalculator.SunResult.PolarNight

    private val manager = AutomationManager(
        wheel = wheel,
        lockedDown = { lockedDown },
        location = { fix },
        media = media,
        clock = { now },
        sun = { _, _, _ -> sun },
    )

    private fun settings(
        lights: String = ApplyWhenIds.CONNECTED,
        offWhenSlow: Boolean = false,
        lock: Boolean = false,
        unlockWhen: String = ProximityLockSettings.UNLOCK_WHEN_NEVER,
        beep: Boolean = false,
        pause: Boolean = false,
    ) = AppSettings(
        lights = LightsSettings(applyWhen = lights, offWhenSlow = offWhenSlow),
        proximityLock = ProximityLockSettings(lockEnabled = lock, unlockWhen = unlockWhen),
        horn = HornSettings(beepVolumeBySpeed = beep),
        mediaControl = MediaControlSettings(pauseEnabled = pause),
    )

    private fun frame(s: AppSettings, stepMs: Long = 250L) = runBlocking {
        manager.evaluate(s)
        now += stepMs
    }

    @Test fun darkTurnsTheLightOnOnce() {
        val s = settings()
        repeat(20) { frame(s) }
        assertEquals(listOf(true), wheel.lightToggles)
        assertTrue(wheel.lightOn)
    }

    @Test fun daylightTurnsItOff() {
        wheel.lightOn = true
        sun = SunCalculator.SunResult.MidnightSun
        frame(settings())
        assertEquals(listOf(false), wheel.lightToggles)
    }

    @Test fun neverLeavesTheLightAlone() {
        repeat(20) { frame(settings(lights = ApplyWhenIds.NEVER)) }
        assertTrue(wheel.lightToggles.isEmpty())
    }

    @Test fun noFixNoSchedule() {
        fix = null
        repeat(20) { frame(settings()) }
        assertTrue(wheel.lightToggles.isEmpty())
    }

    @Test fun aWheelThatIgnoresTheCommandIsNotAskedEveryFrame() {
        wheel.lightFollows = false
        val s = settings()
        // Four seconds of frames, inside the default grace window.
        repeat(16) { frame(s) }
        assertEquals(1, wheel.lightToggles.size)
        // Past the grace, once more.
        repeat(4) { frame(s) }
        assertEquals(2, wheel.lightToggles.size)
    }

    @Test fun aHandOnTheLightSuspendsTheAutomationUntilReconnect() {
        val s = settings()
        frame(s)
        assertTrue(wheel.lightOn)
        // A frame that sees it on, as the wheel's next report would.
        frame(s)
        // The rider switches it off long after the automation's own toggle.
        now += 10_000
        wheel.lightOn = false
        frame(s)
        assertTrue(manager.autoLightsSuspended.value)
        repeat(40) { frame(s) }
        assertEquals(listOf(true), wheel.lightToggles, "the automation must not undo the rider")
        manager.clearLightsSuspension()
        frame(s)
        assertEquals(listOf(true, true), wheel.lightToggles)
    }

    @Test fun lockdownsLightButtonDoesNotSuspend() {
        lockedDown = true
        manager.notifyManualLightChange()
        assertFalse(manager.autoLightsSuspended.value)
        manager.notifyManualLockChange()
        assertFalse(manager.autoLockSuspended.value)
        lockedDown = false
        manager.notifyManualLightChange()
        assertTrue(manager.autoLightsSuspended.value)
    }

    @Test fun walkingPaceCutsTheBeamAfterTheHold() {
        val s = settings(lights = ApplyWhenIds.RIDING, offWhenSlow = true)
        wheel.speedKmh = 20f
        repeat(4) { frame(s) }
        assertTrue(wheel.lightOn)
        wheel.speedKmh = -2f // backwards counts by its size
        repeat(16) { frame(s) }
        assertFalse(wheel.lightOn)
        assertEquals(listOf(true, false), wheel.lightToggles)
    }

    @Test fun fadingSignalLocksAfterTheHold() {
        val s = settings(lock = true)
        wheel.rssiDbm = -90
        repeat(11) { frame(s, 250L) }
        assertTrue(wheel.locks.isEmpty(), "not before the hold")
        repeat(2) { frame(s, 250L) }
        assertEquals(listOf(true), wheel.locks)
    }

    @Test fun walkingBackUnlocksWhenAsked() {
        val s = settings(lock = true, unlockWhen = ProximityLockSettings.UNLOCK_WHEN_RETURN)
        wheel.rssiDbm = -90
        repeat(14) { frame(s) }
        assertEquals(listOf(true), wheel.locks)
        wheel.rssiDbm = -50
        repeat(14) { frame(s) }
        assertEquals(listOf(true, false), wheel.locks)
    }

    @Test fun proximityStepsAsideForLockdownAndAHand() {
        val s = settings(lock = true)
        wheel.rssiDbm = -90
        lockedDown = true
        repeat(20) { frame(s) }
        assertTrue(wheel.locks.isEmpty())
        lockedDown = false
        manager.notifyManualLockChange()
        repeat(20) { frame(s) }
        assertTrue(wheel.locks.isEmpty())
        manager.clearLockSuspension()
        repeat(14) { frame(s) }
        assertEquals(listOf(true), wheel.locks)
    }

    @Test fun noSignalYetDoesNothing() {
        val s = settings(lock = true)
        wheel.rssiDbm = 0
        repeat(40) { frame(s) }
        assertTrue(wheel.locks.isEmpty())
    }

    @Test fun beeperFollowsTheSpeedOnlyWhenTheLevelChanges() {
        wheel.hasBeeperVolume = true
        val s = settings(beep = true, lights = ApplyWhenIds.NEVER)
        repeat(3) { frame(s) }
        wheel.speedKmh = 30f
        repeat(3) { frame(s) }
        wheel.speedKmh = 0f
        repeat(3) { frame(s) }
        val h = HornSettings()
        assertEquals(listOf(h.beepVolumeStopped, h.beepVolumeRiding, h.beepVolumeStopped), wheel.beeps)
    }

    @Test fun mediaRunsEveryFrameAndPausesStopForLockdown() {
        val s = settings(pause = true, lights = ApplyWhenIds.NEVER)
        repeat(3) { frame(s) }
        lockedDown = true
        repeat(2) { frame(s) }
        assertEquals(5, media.volumeFrames)
        assertEquals(3, media.controlFrames)
    }
}
