package com.eried.eucplanet.flic

import kotlinx.coroutines.flow.StateFlow

/** A paired Flic button, by its Bluetooth address (the key the settings bind actions to). */
data class FlicButtonInfo(val address: String, val name: String?)

/**
 * Pairing Flic buttons, for the Flic settings screen: the Flic SDK on each
 * phone (FlicManager on Android, flic2lib through the Swift bridge on iOS).
 */
interface FlicPairing {
    val scanning: StateFlow<Boolean>
    /** What the scan is doing, in the rider's language; empty when idle. */
    val scanStatus: StateFlow<String>
    val pairedButtons: StateFlow<List<FlicButtonInfo>>
    fun startScan()
    fun stopScan()
    fun forget(address: String)
}
