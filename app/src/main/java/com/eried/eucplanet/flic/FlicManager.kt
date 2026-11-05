package com.eried.eucplanet.flic

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
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

class FlicManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    /** What a press does; shared with every other surface that fires actions. */
    private val executor: ActionExecutor,
) {
    companion object {
        private const val TAG = "FlicManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flic2Manager: Flic2Manager? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Timestamp of last dispatched Flic action. UI uses this to flash a Flic indicator briefly.
    val lastActionAt: StateFlow<Long> get() = executor.lastActionAt

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
        val updated = FlicSlots.reconcile(settings, addrs)
        if (updated != settings) {
            Log.i(TAG, "Reconciled Flic addresses: 1=${updated.flic1Address} 2=${updated.flic2Address} 3=${updated.flic3Address} 4=${updated.flic4Address}")
            settingsRepository.update(updated)
        }
    }

    fun startScan() {
        val manager = flic2Manager ?: return
        // Flic2's startScan throws when the adapter is off, which would crash
        // the app. Surface it as a status message instead.
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled != true) {
            _scanning.value = false
            _scanStatus.value = context.getString(R.string.scan_bluetooth_off_title)
            return
        }
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
                    val knownInApp = FlicSlots.isKnown(s, button.bdAddr)
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
        val updated = FlicSlots.assign(settingsRepository.get(), bdAddr) ?: return
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
        val actionName = FlicSlots.actionFor(settings, bdAddr, gesture) ?: return

        Log.i(TAG, "Dispatching action: $actionName")
        executor.executeAction(actionName, settings)
    }

    fun dispatchActionByName(actionName: String) {
        if (actionName.isEmpty() || actionName == "NONE") return
        scope.launch {
            val settings = settingsRepository.get()
            executor.executeAction(actionName, settings)
        }
    }

    /**
     * Executes the action identified by [key]. Unknown / blank / "NONE" keys
     * are no-ops. The dispatch table below is the SINGLE source of behavior
     * for every physical surface (Flic, volume key, watch), adding a new
     * action means:
     *   1. Append to [com.eried.eucplanet.data.model.ActionCatalog.all] (metadata)
     *   2. Add a branch here (behavior)
     * That's it, no other file in the action layer needs to grow.
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

    /**
     * Run a catalog action from somewhere that is not a button.
     *
     * Voice goes through here rather than calling the repositories itself, so
     * a spoken "lights" is gated by exactly what a Flic press is gated by:
     * legal-mode lockdown first, then the catalog's own precondition. A
     * second path would be a second place for those rules to be forgotten.
     */
    suspend fun runAction(key: String) {
        executor.executeAction(key, settingsRepository.get())
    }


}
