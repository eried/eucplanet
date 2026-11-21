package com.eried.eucplanet.service.hud.engo

import kotlinx.coroutines.flow.StateFlow

/**
 * The Bluetooth link to ENGO glasses that the shared code drives: the pairing
 * scan, the link to the paired glasses and the frames sent to them. Android:
 * EngoAdapter in :app. iPhone: IosEngoGlasses in iosMain.
 */
interface EngoGlasses {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    val state: StateFlow<State>

    /** Whether a pairing scan is running, and the glasses it has found. */
    val scanning: StateFlow<Boolean>
    val found: StateFlow<List<EngoFound>>

    /** True once the connected glasses say their display is colour. */
    val colorCapable: StateFlow<Boolean>

    /** Look for glasses to pair. Results arrive in [found]. */
    fun startScan()
    fun stopScan()

    /** Link to the paired glasses at [address]; a failed attempt ends as DISCONNECTED. */
    fun connect(address: String)
    fun disconnect()

    /** Queue a screen's frames; they are written respecting the glasses' flow control. */
    fun send(frames: List<ByteArray>)
}
