package com.eried.eucplanet.flic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** FlicManager as the shared Flic settings screen's [FlicPairing]. */
class AndroidFlicPairing(private val flic: FlicManager) : FlicPairing {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val scanning = flic.scanning
    override val scanStatus = flic.scanStatus
    override val pairedButtons = flic.pairedButtons
        .map { list -> list.map { FlicButtonInfo(it.bdAddr, it.name) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun startScan() = flic.startScan()
    override fun stopScan() = flic.stopScan()

    override fun forget(address: String) {
        val button = flic.pairedButtons.value.find { it.bdAddr == address }
        if (button != null) flic.forgetButton(button)
    }
}
