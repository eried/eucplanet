package com.eried.eucplanet.flic

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.CustomBleCommand
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.VoiceService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2ButtonListener
import io.flic.flic2libandroid.Flic2Manager
import io.flic.flic2libandroid.Flic2ScanCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FlicManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val wheelRepository: WheelRepository,
    private val tripRepository: TripRepository,
    private val voiceService: VoiceService,
    private val automationManager: AutomationManager
) {
    companion object {
        private const val TAG = "FlicManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flic2Manager: Flic2Manager? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Timestamp of last dispatched Flic action. UI uses this to flash a Flic indicator briefly.
    private val _lastActionAt = MutableStateFlow(0L)
    val lastActionAt: StateFlow<Long> = _lastActionAt.asStateFlow()

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private val _pairedButtons = MutableStateFlow<List<Flic2Button>>(emptyList())
    val pairedButtons: StateFlow<List<Flic2Button>> = _pairedButtons.asStateFlow()

    fun initialize() {
        try {
            flic2Manager = Flic2Manager.initAndGetInstance(context, Handler(Looper.getMainLooper()))
            Log.i(TAG, "Flic2Manager initialized")
            reconnectPairedButtons()
            scope.launch { reconcileSettings() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Flic2Manager", e)
        }
    }

    private fun reconnectPairedButtons() {
        val manager = flic2Manager ?: return
        val buttons = manager.buttons
        _pairedButtons.value = buttons
        for (button in buttons) {
            button.addListener(buttonListener)
            button.connect()
        }
        Log.i(TAG, "Reconnected ${buttons.size} paired buttons")
    }

    // Keep AppSettings.flic1..4Address in sync with the Flic2 lib's paired list.
    // Clears stale addresses (button forgotten outside the app) and auto-fills empty slots
    // with buttons the lib already knows, fixes the case where pairing data was lost but the
    // buttons remain known to the Flic lib.
    private suspend fun reconcileSettings() {
        val addrs = (flic2Manager?.buttons ?: return).map { it.bdAddr }.toSet()
        val settings = settingsRepository.get()
        var updated = settings
        if (updated.flic1Address != null && updated.flic1Address !in addrs) updated = updated.copy(flic1Address = null)
        if (updated.flic2Address != null && updated.flic2Address !in addrs) updated = updated.copy(flic2Address = null)
        if (updated.flic3Address != null && updated.flic3Address !in addrs) updated = updated.copy(flic3Address = null)
        if (updated.flic4Address != null && updated.flic4Address !in addrs) updated = updated.copy(flic4Address = null)
        for (addr in addrs) {
            val alreadyAssigned = addr == updated.flic1Address || addr == updated.flic2Address ||
                    addr == updated.flic3Address || addr == updated.flic4Address
            if (alreadyAssigned) continue
            updated = when {
                updated.flic1Address == null -> updated.copy(flic1Address = addr)
                updated.flic2Address == null -> updated.copy(flic2Address = addr)
                updated.flic3Address == null -> updated.copy(flic3Address = addr)
                updated.flic4Address == null -> updated.copy(flic4Address = addr)
                else -> updated  // all slots full
            }
        }
        if (updated != settings) {
            Log.i(TAG, "Reconciled Flic addresses: 1=${updated.flic1Address} 2=${updated.flic2Address} 3=${updated.flic3Address} 4=${updated.flic4Address}")
            settingsRepository.update(updated)
        }
    }

    fun startScan() {
        val manager = flic2Manager ?: return
        _scanning.value = true
        _scanStatus.value = ""

        manager.startScan(object : Flic2ScanCallback {
            override fun onDiscoveredAlreadyPairedButton(button: Flic2Button) {
                // Button is in the Flic2 lib's storage already. Two cases:
                //  (1) It's also saved in our AppSettings (flic1/flic2), just reconnect
                //      silently and keep scanning, otherwise scanning for a second button
                //      would abort the moment the first already-paired one is seen.
                //  (2) Lib knows it but AppSettings lost it (e.g. DB reset), complete the
                //      pair flow so the address gets saved and the UI can leave scanning state.
                Log.i(TAG, "Already paired: ${button.bdAddr}")
                button.addListener(buttonListener)
                button.connect()
                _pairedButtons.value = flic2Manager?.buttons ?: emptyList()
                scope.launch {
                    val s = settingsRepository.get()
                    val knownInApp = button.bdAddr == s.flic1Address || button.bdAddr == s.flic2Address ||
                            button.bdAddr == s.flic3Address || button.bdAddr == s.flic4Address
                    if (!knownInApp) {
                        saveButtonAddress(button.bdAddr)
                        _scanStatus.value = context.getString(
                            R.string.flic_status_paired_fmt,
                            button.name ?: button.bdAddr
                        )
                        _scanning.value = false
                        flic2Manager?.stopScan()
                    }
                    // If known in app, leave the scan running so another button can be added.
                }
            }

            override fun onDiscovered(bdAddr: String) {
                Log.i(TAG, "Discovered: $bdAddr")
                _scanStatus.value = context.getString(R.string.flic_status_found_connecting)
            }

            override fun onConnected() {
                _scanStatus.value = context.getString(R.string.flic_status_connected_pairing)
            }

            override fun onAskToAcceptPairRequest() {
                _scanStatus.value = context.getString(R.string.flic_status_accept_pairing)
            }

            override fun onComplete(result: Int, subCode: Int, button: Flic2Button?) {
                _scanning.value = false
                if (result == Flic2ScanCallback.RESULT_SUCCESS && button != null) {
                    Log.i(TAG, "Paired: ${button.bdAddr} name=${button.name}")
                    _scanStatus.value = context.getString(R.string.flic_status_paired_fmt, button.name ?: button.bdAddr)
                    button.addListener(buttonListener)
                    button.connect()
                    _pairedButtons.value = flic2Manager?.buttons ?: emptyList()

                    // Save button address
                    scope.launch { saveButtonAddress(button.bdAddr) }
                } else {
                    Log.w(TAG, "Scan failed: result=$result subCode=$subCode")
                    _scanStatus.value = context.getString(R.string.flic_status_scan_failed_fmt, result)
                }
            }
        })
    }

    fun stopScan() {
        flic2Manager?.stopScan()
        _scanning.value = false
        _scanStatus.value = ""
    }

    fun forgetButton(button: Flic2Button) {
        button.disconnectOrAbortPendingConnection()
        flic2Manager?.forgetButton(button)
        _pairedButtons.value = flic2Manager?.buttons ?: emptyList()
        scope.launch {
            val s = settingsRepository.get()
            val updated = when (button.bdAddr) {
                s.flic1Address -> s.copy(flic1Address = null)
                s.flic2Address -> s.copy(flic2Address = null)
                s.flic3Address -> s.copy(flic3Address = null)
                s.flic4Address -> s.copy(flic4Address = null)
                else -> s
            }
            if (updated !== s) settingsRepository.update(updated)
        }
    }

    private suspend fun saveButtonAddress(bdAddr: String) {
        val s = settingsRepository.get()
        // Skip if this button is already assigned to any slot.
        if (bdAddr == s.flic1Address || bdAddr == s.flic2Address ||
            bdAddr == s.flic3Address || bdAddr == s.flic4Address) return
        val updated = when {
            s.flic1Address == null -> s.copy(flic1Address = bdAddr)
            s.flic2Address == null -> s.copy(flic2Address = bdAddr)
            s.flic3Address == null -> s.copy(flic3Address = bdAddr)
            s.flic4Address == null -> s.copy(flic4Address = bdAddr)
            else -> return  // all slots full
        }
        settingsRepository.update(updated)
    }

    // --- Button event handling ---

    private val buttonListener = object : Flic2ButtonListener() {
        override fun onButtonSingleOrDoubleClickOrHold(
            button: Flic2Button, wasQueued: Boolean, lastQueued: Boolean,
            timestamp: Long, isSingleClick: Boolean, isDoubleClick: Boolean, isHold: Boolean
        ) {
            val gesture = when {
                isSingleClick -> "click"
                isDoubleClick -> "doubleClick"
                isHold -> "hold"
                else -> return
            }
            Log.i(TAG, "Button ${button.bdAddr}: $gesture")
            scope.launch { dispatchAction(button.bdAddr, gesture) }
        }
    }

    private suspend fun dispatchAction(bdAddr: String, gesture: String) {
        val settings = settingsRepository.get()
        val (click, dbl, hold) = when (bdAddr) {
            settings.flic1Address -> Triple(settings.flic1Click, settings.flic1DoubleClick, settings.flic1Hold)
            settings.flic2Address -> Triple(settings.flic2Click, settings.flic2DoubleClick, settings.flic2Hold)
            settings.flic3Address -> Triple(settings.flic3Click, settings.flic3DoubleClick, settings.flic3Hold)
            settings.flic4Address -> Triple(settings.flic4Click, settings.flic4DoubleClick, settings.flic4Hold)
            else -> return
        }
        val actionName = when (gesture) {
            "click" -> click
            "doubleClick" -> dbl
            "hold" -> hold
            else -> return
        }

        Log.i(TAG, "Dispatching action: $actionName")
        executeAction(actionName, settings)
    }

    fun dispatchActionByName(actionName: String) {
        if (actionName.isEmpty() || actionName == "NONE") return
        scope.launch {
            val settings = settingsRepository.get()
            executeAction(actionName, settings)
        }
    }

    /**
     * Executes the action identified by [key]. Unknown / blank / "NONE" keys
     * are no-ops. The dispatch table below is the SINGLE source of behavior
     * for every physical surface (Flic, volume key, watch) — adding a new
     * action means:
     *   1. Append to [com.eried.eucplanet.data.model.ActionCatalog.all] (metadata)
     *   2. Add a branch here (behavior)
     * That's it — no other file in the action layer needs to grow.
     *
     * Dashboard-only actions (OPEN_*, MUTE_ALARMS, RESET_TRIP, TOGGLE_UNITS)
     * route through a different executor in the UI layer because they need
     * navigation / Snackbar / Compose handles this service doesn't have.
     */
    /**
     * Resolve and fire a CUSTOM BLE action (key "B:<uuid>"). Family-gated: a
     * command only fires on a matching connected wheel, so raw user bytes never
     * reach the wrong wheel. No-op (logged) otherwise.
     */
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
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context, "Custom BLE: $reason", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        Log.i(TAG, "custom BLE $key -> ${cmd.frames.size} frame(s) on ${cmd.family}")
        wheelRepository.sendCustomBle(cmd.frames)
    }

    private suspend fun executeAction(key: String, settings: AppSettings) {
        if (key.isEmpty() || key == "NONE") return
        _lastActionAt.value = System.currentTimeMillis()
        Log.d(TAG, "executeAction key=$key")

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
                automationManager.notifyManualLightChange()
                wheelRepository.toggleLight()
            }
            "LOCK_TOGGLE" -> wheelRepository.toggleLock()
            "SAFETY_TOGGLE" -> wheelRepository.toggleSafetySpeed()
            "SAFETY_ON" -> wheelRepository.enableSafetySpeed()
            "SAFETY_OFF" -> wheelRepository.disableSafetySpeed()
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
            "MEDIA_PLAY_PAUSE" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "MEDIA_NEXT" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "MEDIA_PREVIOUS" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> Log.w(TAG, "Unknown / unsupported action key for physical surface: $key")
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }
}
