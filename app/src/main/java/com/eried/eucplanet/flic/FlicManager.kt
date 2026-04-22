package com.eried.eucplanet.flic

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.FlicAction
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

    fun startScan() {
        val manager = flic2Manager ?: return
        _scanning.value = true
        _scanStatus.value = ""

        manager.startScan(object : Flic2ScanCallback {
            override fun onDiscoveredAlreadyPairedButton(button: Flic2Button) {
                Log.i(TAG, "Already paired: ${button.bdAddr}")
                _scanStatus.value = context.getString(R.string.flic_status_found_paired)
                button.addListener(buttonListener)
                button.connect()
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
            val settings = settingsRepository.get()
            if (settings.flic1Address == button.bdAddr) {
                settingsRepository.update(settings.copy(flic1Address = null))
            } else if (settings.flic2Address == button.bdAddr) {
                settingsRepository.update(settings.copy(flic2Address = null))
            }
        }
    }

    private suspend fun saveButtonAddress(bdAddr: String) {
        val settings = settingsRepository.get()
        if (settings.flic1Address == null) {
            settingsRepository.update(settings.copy(flic1Address = bdAddr))
        } else if (settings.flic2Address == null && settings.flic1Address != bdAddr) {
            settingsRepository.update(settings.copy(flic2Address = bdAddr))
        }
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
        val actionName = when {
            bdAddr == settings.flic1Address -> when (gesture) {
                "click" -> settings.flic1Click
                "doubleClick" -> settings.flic1DoubleClick
                "hold" -> settings.flic1Hold
                else -> return
            }
            bdAddr == settings.flic2Address -> when (gesture) {
                "click" -> settings.flic2Click
                "doubleClick" -> settings.flic2DoubleClick
                "hold" -> settings.flic2Hold
                else -> return
            }
            else -> return
        }

        val action = try {
            FlicAction.valueOf(actionName)
        } catch (_: Exception) {
            FlicAction.NONE
        }

        Log.i(TAG, "Dispatching action: $action")
        executeAction(action, settings)
    }

    fun dispatchActionByName(actionName: String) {
        val action = try { FlicAction.valueOf(actionName) } catch (_: Exception) { FlicAction.NONE }
        if (action == FlicAction.NONE) return
        scope.launch {
            val settings = settingsRepository.get()
            executeAction(action, settings)
        }
    }

    private suspend fun executeAction(action: FlicAction, settings: AppSettings) {
        if (action != FlicAction.NONE) _lastActionAt.value = System.currentTimeMillis()
        when (action) {
            FlicAction.NONE -> {}
            FlicAction.HORN -> wheelRepository.sendHorn()
            FlicAction.LIGHT_TOGGLE -> {
                automationManager.notifyManualLightChange()
                wheelRepository.toggleLight()
            }
            FlicAction.LOCK_TOGGLE -> wheelRepository.toggleLock()
            FlicAction.SAFETY_TOGGLE -> wheelRepository.toggleSafetySpeed()
            FlicAction.SAFETY_ON -> wheelRepository.enableSafetySpeed()
            FlicAction.SAFETY_OFF -> wheelRepository.disableSafetySpeed()
            FlicAction.VOICE_ANNOUNCE -> {
                voiceService.announceTrigger(
                    wheelRepository.wheelData.value, settings,
                    isRecording = tripRepository.recording.value
                )
            }
            FlicAction.RECORD_TOGGLE -> {
                if (tripRepository.recording.value) {
                    tripRepository.stopRecording()
                } else {
                    tripRepository.startRecording()
                }
            }
            FlicAction.MEDIA_PLAY_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            FlicAction.MEDIA_NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            FlicAction.MEDIA_PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
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
