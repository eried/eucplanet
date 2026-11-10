package com.eried.eucplanet.service

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.data.model.WheelData
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
    private val alerts = mutableListOf<String>()
    private val settings = SettingsRepository(SettingsStore(MemoryDataStore()) { false })
    private val automation = AutomationManager(NoAutomationWheel(), { false }, { null })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = RideSession(
        wheel = wheel,
        settings = settings,
        automation = automation,
        trips = trips,
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
}
