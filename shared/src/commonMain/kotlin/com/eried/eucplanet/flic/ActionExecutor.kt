package com.eried.eucplanet.flic

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.CustomBleCommand
import com.eried.eucplanet.data.repository.AppNotices
import com.eried.eucplanet.data.repository.LegalLockdownGate
import com.eried.eucplanet.data.repository.PhoneDevice
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.dashboard.ActionDispatcher
import com.eried.eucplanet.ui.dashboard.DashboardAutomation
import com.eried.eucplanet.ui.dashboard.DashboardTrips
import com.eried.eucplanet.ui.dashboard.DashboardVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The phone's music player, for the media actions. */
interface MediaControls {
    /** Wake the last player, or open the music app and start it. */
    fun startMusic()
    /** Pause what plays; when nothing does, start music as [startMusic] does. */
    fun playPauseOrStart()
    fun pause()
    fun next()
    fun previous()
}

/** The spoken-command listener, for VOICE_LISTEN. */
interface VoiceListening {
    fun hasMicPermission(): Boolean
    fun listen()
}

/**
 * Runs a rider action by its catalog key, for every surface that fires one:
 * Flic, volume keys, the watch, the HUD, voice and the dashboard's tiles.
 * One table, so they all behave the same; on Android FlicManager hands its
 * button presses here.
 */
class ActionExecutor(
    private val settingsRepository: SettingsRepository,
    private val wheelRepository: WheelRepository,
    private val tripRepository: DashboardTrips,
    private val voiceService: DashboardVoice,
    private val automationManager: DashboardAutomation,
    private val appNotifier: AppNotices,
    private val legalLockdown: LegalLockdownGate,
    private val voiceCommands: VoiceListening,
    private val media: MediaControls,
    private val phone: PhoneDevice,
) : ActionDispatcher {
    private companion object {
        const val TAG = "FlicManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Timestamp of last dispatched action. UI uses this to flash a Flic indicator briefly.
    private val _lastActionAt = MutableStateFlow(0L)
    override val lastActionAt: StateFlow<Long> = _lastActionAt.asStateFlow()

    override fun dispatchActionByName(actionName: String) {
        if (actionName.isEmpty() || actionName == "NONE") return
        scope.launch {
            val settings = settingsRepository.get()
            executeAction(actionName, settings)
        }
    }

    suspend fun executeAction(key: String, settings: AppSettings) {
        if (key.isEmpty() || key == "NONE") return
        _lastActionAt.value = System.currentTimeMillis()
        Log.d(TAG, "executeAction key=$key")

        // Legal Mode Lockdown, checked first and before anything else.
        //
        // Every eyes-free surface (Flic, volume keys, the watch through
        // PhoneWearListenerService, the HUD through HudCommandSink) and the
        // dashboard tiles funnel through this function, so one allowlist here
        // covers all of them. It sits above the custom-BLE branch because a
        // rider's own raw frames could carry a speed-limit write, and above
        // the catalog precondition because the lock can be armed with no wheel
        // connected: checked after it, a legal-mode hotkey press with no wheel
        // present would give silence instead of the dialog that explains the
        // way out.
        if (legalLockdown.isEngaged() && !LockdownGate.isAllowed(key)) {
            if (LockdownGate.raisesUnlockPrompt(key)) {
                com.eried.eucplanet.ui.lockdown.LockdownPromptBus.request()
            }
            Log.i(TAG, "action $key blocked: legal mode lockdown armed")
            return
        }

        // Shared precondition gate (chokepoint A). Every eyes-free surface
        // funnels here, so this single check covers Flic, volume keys, the
        // dashboard tiles, the watch, the HUD long-press and Garmin `action:`.
        val connected = wheelRepository.connectionState.value ==
            com.eried.eucplanet.ble.ConnectionState.CONNECTED
        if (CustomBleCommand.isCustomBleId(key)) {
            // Custom frames go straight to the wheel; skip + log when there's
            // nothing to send them to.
            if (!connected) {
                Log.i(TAG, "custom BLE $key blocked: precondition not met (connected=false)")
                return
            }
            dispatchCustomBle(key, settings)
            return
        }
        // Catalog-driven gate: enabledReader == false means "must not fire
        // right now". Null reader = always allowed.
        val spec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key)
        val ctx = com.eried.eucplanet.data.model.StatusContext(
            wheel = wheelRepository.wheelData.value,
            tripRecording = tripRepository.recording.value,
            safetyActive = wheelRepository.safetySpeedActive.value,
            connected = connected
        )
        if (spec?.enabledReader?.invoke(ctx) == false) {
            Log.i(TAG, "action $key blocked: precondition not met (connected=$connected)")
            return
        }
        when (key) {
            "HORN" -> wheelRepository.sendHorn()
            "LIGHT_TOGGLE" -> {
                // While locked down the light button must not suspend
                // auto-lights. The mode is temporary, and the rider is never
                // told an automation was overridden by it.
                if (!legalLockdown.isEngaged()) automationManager.notifyManualLightChange()
                wheelRepository.toggleLight()
            }
            "LOCK_TOGGLE" -> {
                automationManager.notifyManualLockChange()
                wheelRepository.toggleLock()
            }
            "SAFETY_TOGGLE" -> wheelRepository.toggleSafetySpeed()
            "SAFETY_ON" -> wheelRepository.enableSafetySpeed()
            "SAFETY_OFF" -> wheelRepository.disableSafetySpeed()
            "VOICE_LISTEN" -> {
                // Legal Mode already refused above, for every surface at once.
                // What is left is the microphone: without it there is nothing
                // to listen with, and a rider pressing a button deserves to be
                // told that rather than to hear silence.
                if (!voiceCommands.hasMicPermission()) {
                    appNotifier.post(phone.text(Res.string.voice_answer_no_mic_permission))
                } else {
                    voiceCommands.listen()
                }
            }
            "VOICE_ANNOUNCE" -> {
                voiceService.announceTrigger(
                    wheelRepository.wheelData.value, settings,
                    isRecording = tripRepository.recording.value
                )
            }
            "RECORD_TOGGLE" -> {
                if (tripRepository.recording.value) {
                    tripRepository.stopRecording()
                } else {
                    tripRepository.startRecording()
                }
            }
            "RECORD_START" -> {
                if (!tripRepository.recording.value) tripRepository.startRecording()
            }
            "RECORD_STOP" -> {
                if (tripRepository.recording.value) tripRepository.stopRecording()
            }
            "MUSIC_START" -> media.startMusic()
            // Any other media press is the rider taking over, so a Start
            // music still pressing play must not undo it.
            // Smart: playing pauses, as the plain toggle did; silent starts
            // music the way Start music does, opening the player if it must,
            // which the toggle could not.
            "MEDIA_PLAY_PAUSE" -> media.playPauseOrStart()
            "MEDIA_PAUSE" -> media.pause()
            "MEDIA_NEXT" -> media.next()
            "MEDIA_PREVIOUS" -> media.previous()
            else -> Log.w(TAG, "Unknown / unsupported action key for physical surface: $key")
        }
    }

    private fun dispatchCustomBle(key: String, settings: AppSettings) {
        val commands = CustomBleCommand.parseAll(settings.dashboardCustomBle)
        val raw = commands[key]
        val connected = wheelRepository.connectedFamilyId
        val cmd = CustomBleCommand.resolveForDispatch(commands, key, connected)
        if (cmd == null) {
            // Tell the rider WHY the tap did nothing instead of silently
            // dropping. The three failure modes from resolveForDispatch:
            //   1. Unknown key (rider deleted the command? shouldn't happen
            //      from a live tile, but defensive)
            //   2. No frames defined (rider made the tile but never typed
            //      the hex bytes)
            //   3. Family mismatch / not connected
            val reason = when {
                raw == null -> "no such command"
                !raw.isSendable -> "no BLE frames defined"
                connected == null -> "no wheel connected"
                raw.family != connected ->
                    "command is for ${raw.family}, wheel is $connected"
                else -> "unknown"
            }
            Log.i(TAG, "custom BLE $key skipped: $reason")
            appNotifier.post("Custom BLE: $reason")
            return
        }
        Log.i(TAG, "custom BLE $key -> ${cmd.frames.size} frame(s) on ${cmd.family}")
        wheelRepository.sendCustomBle(cmd.frames)
    }
}
