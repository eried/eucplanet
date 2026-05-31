package com.eried.eucplanet.ble.radar

import com.eried.eucplanet.data.model.RadarThreat
import com.eried.eucplanet.data.model.RadarVendor

/**
 * Per-vendor adapter for a rear-view BLE radar. Mirrors
 * [com.eried.eucplanet.ble.gps.ExternalGpsAdapter]: one interface, one
 * implementation per family, registered into a Hilt set so the scanner and
 * connection manager iterate them without knowing the concrete types.
 *
 * Phase 1 ships only [VariaAdapter]. The shape is generic on purpose so
 * Magicshine Seemee / BSafe / aftermarket radars can plug in by adding a
 * [dagger.Binds] entry in [com.eried.eucplanet.di.RadarModule] when their
 * BLE protocols become public.
 */
interface RadarAdapter {
    val vendor: RadarVendor

    /**
     * Match the device by its advertised name. Called for every BLE scan
     * result. Vendors typically reserve a stable prefix (Varia: `RTL`,
     * `RVR`, `RCT`, `eRTL`, `Varia`).
     */
    fun matches(deviceName: String): Boolean

    /**
     * Service UUID the connection manager should look up and whose TX
     * (notify) characteristic should be subscribed to. Vendor-specific:
     * Varia uses `6A4E3200-…`.
     */
    val serviceUuid: java.util.UUID

    /**
     * Notification characteristic that carries the threat-report frames.
     * Subscribed to after the service is discovered.
     */
    val notifyCharacteristicUuid: java.util.UUID

    /**
     * Decode one BLE notification into the current list of threats. The
     * caller is responsible for stamping wall-clock + previous-frame
     * tracking ,  the adapter only owns the wire format. Returns null when
     * the frame is partial (mid-reassembly), unrecognised, or just a
     * status frame the device interleaves in the same channel.
     */
    fun decode(notification: ByteArray): List<DecodedThreat>?
}

/**
 * What an adapter emits per car, before the repository layers on
 * threat-level classification and wall-clock-based first-seen tracking.
 * Repository turns this into [RadarThreat].
 */
data class DecodedThreat(
    val id: Int,
    val distanceM: Int,
    val approachSpeedKmh: Int
)
