package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource

/**
 * Per-vendor adapter for an external BLE GPS box. Each implementation owns:
 *  * advertisement matching (so the scanner can list only its own kind),
 *  * decoding of the raw notification stream into [ExternalGpsSample]s.
 *
 * The architecture intentionally mirrors [com.eried.eucplanet.ble.WheelAdapter]:
 * one interface, one implementation per family, registered into a Hilt set so
 * the scanner / connection manager iterate them without knowing the concrete
 * types. Phase 1 ships only [RaceBoxAdapter]; future Draggy / VBox / Garmin
 * Catalyst adapters slot in by adding a [Binds] entry.
 */
interface ExternalGpsAdapter {
    val source: ExternalGpsSource

    /** Match the device by its advertised name. Called for every BLE scan result. */
    fun matches(deviceName: String): Boolean

    /**
     * Decode one BLE notification frame into a sample. Returns null when the
     * frame is partial (mid-reassembly), unrecognised, or just not a sample
     * frame (some devices interleave status frames). Stateful adapters keep
     * their reassembly buffer internally — the connection manager forwards
     * raw bytes verbatim.
     */
    fun decode(notification: ByteArray): ExternalGpsSample?

    /** Optional packets to send right after connect (e.g. enable streaming).
     *  RaceBox starts streaming on subscribe, so the default is empty. */
    fun initCommands(): List<ByteArray> = emptyList()
}
