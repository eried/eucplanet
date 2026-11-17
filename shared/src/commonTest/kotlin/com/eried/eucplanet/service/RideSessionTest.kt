package com.eried.eucplanet.service

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AccelSplitSettings
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.AccelSplitRepository
import com.eried.eucplanet.data.repository.PhoneDevice
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.SettingsStore
import com.eried.eucplanet.data.sync.MemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules around a ride that both phones used to write for themselves
 * (WheelService on Android, a handful of iOS objects): what a connect and a
 * disconnect do, and what every frame drives. The iPhone had drifted on two
 * of them, which is why they are one class now.
 */
class RideSessionTest {

    private class FakeWheel : RideWheel {
        override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val wheelData = MutableStateFlow(WheelData())
        override val chargeStatus = MutableStateFlow(ChargeStatus.Disconnected)
    }

    private class FakeTrips : RideRecorder {
        override val recording = MutableStateFlow(false)
        var starts = 0
        override suspend fun startRecording() { starts++; recording.value = true }
        override suspend fun stopRecording() { recording.value = false }
    }

    private class FakeVoice : RideVoice {
        val said = mutableListOf<String>()
        var frames = 0
        var cleared = 0
        override fun announceEvent(text: String) { said += text }
        override fun recordTelemetry(data: WheelData) { frames++ }
        override fun clearTelemetryHistory() { cleared++ }
        override fun announceStatus(data: WheelData, settings: AppSettings, isRecording: Boolean) = Unit
        override fun externalOutputActive() = false
    }

    private object Phone : PhoneDevice {
        override fun batteryPercent() = 50
        override fun hornFallback() = Unit
        override fun text(res: StringResource, vararg args: Any) =
            res.key + args.joinToString("") { " $it" }
    }

    private class NoAutomationWheel : AutomationWheel {
        override val connected = false
        override val speedKmh = 0f
        override val lightOn = false
        override val rssiDbm = 0
        override val locked = false
        override val hasBeeperVolume = false
        override fun toggleLight() = Unit
        override fun setLock(target: Boolean) = Unit
        override fun setBeeperVolume(level: Int) = Unit
    }

    private val wheel = FakeWheel()
    private val trips = FakeTrips()
    private val voice = FakeVoice()
    private val splits = AccelSplitRepository()
    private val alerts = mutableListOf<String>()
    private val settings = SettingsRepository(SettingsStore(MemoryDataStore()) { false })
    private val automation = AutomationManager(NoAutomationWheel(), { false }, { null })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = RideSession(
        wheel = wheel,
        settings = settings,
        automation = automation,
        trips = trips,
        splits = splits,
        voice = voice,
        phone = Phone,
        scope = scope,
        postChargeAlert = { title, _ -> alerts += title },
    )

    @AfterTest fun stop() = scope.cancel()

    private fun use(s: AppSettings) = runBlocking { settings.update(s) }
    private fun frame(data: WheelData, s: AppSettings) = runBlocking { session.onFrame(data, s) }

    @Test fun connectAnnouncesClearsSuspensionsAndRecords() {
        use(AppSettings(announceConnection = true, autoRecord = true, autoRecordStartInMotion = false))
        session.followConnection()
        automation.notifyManualLightChange()
        automation.notifyManualLockChange()
        wheel.connectionState.value = ConnectionState.CONNECTING
        wheel.connectionState.value = ConnectionState.CONNECTED
        assertEquals(listOf("voice_wheel_connected"), voice.said)
        assertFalse(automation.autoLightsSuspended.value)
        assertFalse(automation.autoLockSuspended.value)
        assertEquals(1, trips.starts, "auto-record starts on connect when not gated on motion")
    }

    @Test fun gpsSignalIsSpokenOnlyWithTheAnnouncementOn() {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<com.eried.eucplanet.data.model.GpsSignalEvent>(extraBufferCapacity = 4)
        session.followGpsSignal(events)
        use(AppSettings(announceGps = false))
        events.tryEmit(com.eried.eucplanet.data.model.GpsSignalEvent.LOST)
        use(AppSettings(announceGps = true))
        events.tryEmit(com.eried.eucplanet.data.model.GpsSignalEvent.LOST)
        events.tryEmit(com.eried.eucplanet.data.model.GpsSignalEvent.ACQUIRED)
        runBlocking {
            kotlinx.coroutines.withTimeout(2_000) { while (voice.said.size < 2) kotlinx.coroutines.delay(10) }
        }
        assertEquals(listOf("voice_gps_lost", "voice_gps_acquired"), voice.said)
    }

    @Test fun theFirstStateIsNotAChange() {
        use(AppSettings(announceConnection = true))
        wheel.connectionState.value = ConnectionState.CONNECTED
        session.followConnection()
        assertTrue(voice.said.isEmpty())
    }

    @Test fun disconnectAnnouncesOnlyAfterARealConnection() {
        use(AppSettings(announceConnection = true, autoRecord = false))
        var platformSaw = 0
        session.followConnection(onDisconnected = { platformSaw++ })
        wheel.connectionState.value = ConnectionState.CONNECTING
        wheel.connectionState.value = ConnectionState.DISCONNECTED
        assertTrue(voice.said.isEmpty(), "a failed attempt is not a lost wheel")
        assertEquals(1, voice.cleared)
        wheel.connectionState.value = ConnectionState.CONNECTED
        wheel.connectionState.value = ConnectionState.DISCONNECTED
        assertEquals(listOf("voice_wheel_connected", "voice_wheel_disconnected"), voice.said)
        assertEquals(2, platformSaw)
    }

    @Test fun lightsAreAnnouncedOnChangeOnly() {
        val s = AppSettings(announceLights = true, autoRecord = false)
        frame(WheelData(lightOn = true), s)
        frame(WheelData(lightOn = true), s)
        frame(WheelData(lightOn = false), s)
        assertEquals(listOf("voice_lights_off"), voice.said)
        assertEquals(3, voice.frames)
    }

    @Test fun motionStartsARecordingOnlyWhileConnected() {
        val s = AppSettings(autoRecord = true, autoRecordStartInMotion = true)
        frame(WheelData(speed = 12f), s)
        assertEquals(0, trips.starts)
        wheel.connectionState.value = ConnectionState.CONNECTED
        frame(WheelData(speed = 0f), s)
        assertEquals(0, trips.starts)
        frame(WheelData(speed = 12f), s)
        assertEquals(1, trips.starts)
        frame(WheelData(speed = 14f), s)
        assertEquals(1, trips.starts, "already recording")
    }

    @Test fun chargeAlertsPostTheirTitle() {
        val s = AppSettings(chargingNotify80 = true, chargingNotifyFull = true, autoRecord = false)
        wheel.chargeStatus.value = ChargeStatus.Charging
        frame(WheelData(batteryPercent = 70), s)
        frame(WheelData(batteryPercent = 80), s)
        wheel.chargeStatus.value = ChargeStatus.Full
        frame(WheelData(batteryPercent = 100), s)
        assertEquals(listOf("charge_alert_80_title", "charge_alert_full_title"), alerts)
    }

    /** Frames every 250 ms from [t0], speeding up from 15 to 45 km/h, 10 km/h every 2 s. */
    private fun speedUp(s: AppSettings, t0: Long, from: Float = 15f, to: Float = 45f): Long {
        var t = t0
        var v = from
        while (v <= to) {
            frame(WheelData(speed = v, timestamp = t), s)
            t += 250L
            v += 1.25f
        }
        return t
    }

    private val splitsOn = AppSettings(
        autoRecord = false,
        // The periodic report's switch is off: the splits have their own.
        voiceEnabled = false,
        accelSplit = AccelSplitSettings(enabled = true, direction = "ACCEL", increment = 10, minSpeed = 20),
    )

    @Test fun speedSplitsAreSpokenOnTheirOwnSwitch() {
        speedUp(splitsOn, 0L)
        assertEquals(2, voice.said.size, "20 to 30 and 30 to 40: ${voice.said}")
        assertTrue(voice.said[0].startsWith("accel_split_time_fmt 20 30 "), voice.said[0])
        assertTrue(voice.said[1].startsWith("accel_split_time_fmt 30 40 "), voice.said[1])
        assertEquals(2, splits.session.value.accel.size)
    }

    @Test fun splitsSwitchedOffSayNothingAndKeepTheSession() {
        speedUp(splitsOn, 0L)
        val kept = splits.session.value
        voice.said.clear()
        val off = splitsOn.copy(accelSplit = splitsOn.accelSplit.copy(enabled = false))
        speedUp(off, 60_000L)
        assertTrue(voice.said.isEmpty(), "off is quiet: ${voice.said}")
        assertEquals(kept, splits.session.value, "off is a pause, not a reset")
    }

    @Test fun aDisconnectDropsTheRunInFlightAndKeepsTheTimes() {
        use(splitsOn)
        wheel.connectionState.value = ConnectionState.CONNECTED
        session.followConnection()
        speedUp(splitsOn, 0L)
        voice.said.clear()
        // Mid-run at 25, then the wheel is off for five minutes.
        val t = speedUp(splitsOn, 60_000L, from = 15f, to = 25f)
        val kept = splits.session.value
        assertFalse(kept.isEmpty)
        wheel.connectionState.value = ConnectionState.DISCONNECTED
        wheel.connectionState.value = ConnectionState.CONNECTED
        frame(WheelData(speed = 35f, timestamp = t + 300_000L), splitsOn)
        assertTrue(voice.said.isEmpty(), "the gap was read as a step: ${voice.said}")
        assertEquals(kept, splits.session.value, "the same wheel keeps its times")
    }
}
