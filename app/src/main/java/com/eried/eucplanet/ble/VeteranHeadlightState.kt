package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.HeadlightReadback

/** Owns command-tracked state and measured levels for one Veteran connection. */
internal class VeteranHeadlightState {
    data class Snapshot(val lightOn: Boolean = false, val readback: HeadlightReadback? = null)

    @Volatile var snapshot = Snapshot()
        private set

    @Synchronized
    fun commanded(model: VeteranModel?, on: Boolean) {
        if (model?.brandOverride == "NOSFET") return
        snapshot = snapshot.copy(lightOn = on)
    }

    @Synchronized
    fun acceptFrame(frame: ByteArray, model: VeteranModel?) {
        if (model?.brandOverride != "NOSFET") {
            endReadback()
            return
        }

        beginReadback()
        val readback = AeonHeadlightDecoder.decode(frame, System.nanoTime()) ?: return
        val lightOn = when (readback.level) {
            null -> snapshot.lightOn
            HeadlightReadback.Level.OFF -> false
            else -> true
        }
        snapshot = Snapshot(lightOn, readback)
    }

    private fun beginReadback() {
        if (snapshot.readback != null) return
        // A command sent before identification is not a measurement.
        snapshot = Snapshot(readback = HeadlightReadback())
    }

    private fun endReadback() {
        if (snapshot.readback == null) return
        snapshot = Snapshot()
    }

    @Synchronized
    fun reset() {
        // A new connection cannot inherit the previous wheel's measured or commanded state.
        snapshot = Snapshot()
    }
}
