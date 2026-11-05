package com.eried.eucplanet.app

import com.eried.eucplanet.flic.FlicButtonInfo
import com.eried.eucplanet.flic.FlicPairing
import com.eried.eucplanet.flic.FlicSlots
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the Swift wrapper around Flic's iOS SDK (flic2lib) does when asked.
 * The Swift side registers one in [IosFlic.sdk] at launch and reports back
 * through IosFlic's on* functions.
 */
interface FlicSdk {
    fun startScan()
    fun stopScan()
    fun forget(address: String)
}

/**
 * Flic on iOS: the pairing state the shared Flic screen shows, the same
 * slots Android keeps ([FlicSlots]), and presses run through the shared
 * action executor. Button addresses are upper case, as Android writes them,
 * so a binding made on one phone works on the other.
 */
object IosFlic : FlicPairing {
    var sdk: FlicSdk? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scanStatus = MutableStateFlow("")
    override val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()
    private val _paired = MutableStateFlow<List<FlicButtonInfo>>(emptyList())
    override val pairedButtons: StateFlow<List<FlicButtonInfo>> = _paired.asStateFlow()

    override fun startScan() {
        val s = sdk ?: return
        _scanning.value = true
        _scanStatus.value = ""
        s.startScan()
    }

    override fun stopScan() {
        _scanning.value = false
        sdk?.stopScan()
    }

    override fun forget(address: String) {
        sdk?.forget(address)
    }

    // --- Called from Swift ---

    /** The SDK's paired buttons, after launch and after any change; reconciles the slots. */
    fun onButtons(buttons: List<FlicButtonInfo>) {
        val list = buttons.map { it.copy(address = it.address.uppercase()) }
        _paired.value = list
        scope.launch {
            val settings = IosAppGraph.settings.get()
            val updated = FlicSlots.reconcile(settings, list.map { it.address }.toSet())
            if (updated != settings) IosAppGraph.settings.update(updated)
        }
    }

    /** Scan progress, as Android's FlicManager reports it: "discovered", "connected", "verified". */
    fun onScanProgress(event: String) {
        _scanStatus.value = IosPhoneDevice.text(
            when (event) {
                "discovered" -> Res.string.flic_status_found_connecting
                "connected" -> Res.string.flic_status_connected_pairing
                else -> Res.string.flic_status_accept_pairing
            }
        )
    }

    /** The scan ended: [address] paired, or [errorCode] when it did not. */
    fun onScanComplete(address: String?, name: String?, errorCode: Int) {
        _scanning.value = false
        if (address == null) {
            _scanStatus.value = IosPhoneDevice.text(Res.string.flic_status_scan_failed_fmt, errorCode)
            return
        }
        _scanStatus.value = IosPhoneDevice.text(Res.string.flic_status_paired_fmt, name ?: address)
        scope.launch {
            val updated = FlicSlots.assign(IosAppGraph.settings.get(), address.uppercase()) ?: return@launch
            IosAppGraph.settings.update(updated)
        }
    }

    /** A press: [gesture] is "click", "doubleClick" or "hold". */
    fun onPress(address: String, gesture: String) {
        scope.launch {
            val settings = IosAppGraph.settings.get()
            val action = FlicSlots.actionFor(settings, address.uppercase(), gesture) ?: return@launch
            Log.i("Flic", "Dispatching action: $action")
            IosAppGraph.actions.executeAction(action, settings)
        }
    }
}
