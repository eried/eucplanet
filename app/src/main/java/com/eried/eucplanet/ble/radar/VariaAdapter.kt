package com.eried.eucplanet.ble.radar

import com.eried.eucplanet.data.model.RadarVendor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin Varia (RTL515, RTL516, RVR315, RCT715, eRTL615, RearVue 820) rear-view
 * radar adapter.
 *
 * Wire format (the `…3203` notify characteristic, the only publicly-readable
 * one on Varia today; the rest of the `6A4E32xx` family is NDA-gated behind
 * Garmin's Radar Data BLE Program):
 *
 *     byte[0]      = packet identifier / fragment sequence
 *                    Low nibble is the fragment ID; frames larger than the
 *                    default 20-byte ATT MTU are split across multiple
 *                    notifications. We currently ignore reassembly: with the
 *                    larger MTU we negotiate in [RadarConnectionManager] every
 *                    real-world Varia frame fits in one packet (up to ~6 cars).
 *     byte[1..]    = (id: u8, distance_m: u8, approach_speed_kmh: u8) per car
 *
 * Reference implementations:
 *  - pycycling/rear_view_radar.py ,  cleanest parser, same struct
 *  - Wunderfitz/harbour-tacho src/variaconnectivity.cpp ,  C++ port with the
 *    same shape
 *
 * Vehicles that have moved out of range simply stop appearing in the list , 
 * there's no explicit "dropped" event, so a missing id this frame means
 * the radar has lost the track. [RadarRepository] handles drop-out timing.
 *
 * The "threat level" field that the Garmin head units display (none / medium
 * / high) is NOT in this channel; the marketing description suggests it
 * comes from the NDA `…3201` characteristic. We derive it locally from
 * distance + closing rate in the repository ,  that's what every other
 * open-source client does, and it works fine in practice.
 */
@Singleton
class VariaAdapter @Inject constructor() : RadarAdapter {

    override val vendor = RadarVendor.VARIA

    /** Garmin Varia rear-view radar (RDR) primary service. */
    override val serviceUuid: UUID = UUID.fromString("6a4e3200-667b-11e3-949a-0800200c9a66")

    /**
     * Threat-measurement characteristic (notify-only). Pycycling, harbour-tacho
     * and every other public client subscribe to this exact UUID; the official
     * Garmin spec at developer.garmin.com/radar-data-ble is NDA so we stick to
     * what is empirically known to work.
     */
    override val notifyCharacteristicUuid: UUID =
        UUID.fromString("6a4e3203-667b-11e3-949a-0800200c9a66")

    /** All known Varia advertised-name prefixes. Case-insensitive. */
    private val namePrefixes = listOf("RTL", "RVR", "RCT", "eRTL", "Varia")

    override fun matches(deviceName: String): Boolean {
        val n = deviceName.trim()
        return namePrefixes.any { n.startsWith(it, ignoreCase = true) }
    }

    override fun decode(notification: ByteArray): List<DecodedThreat>? {
        // Smallest valid frame is the 1-byte header alone (lane clear). A
        // frame with cars adds (id, distance, speed) triples after byte 0.
        if (notification.isEmpty()) return null
        val payloadLen = notification.size - 1
        if (payloadLen < 0 || payloadLen % 3 != 0) {
            // Either a status frame the device interleaves, or a fragment we
            // can't reassemble without more state. Treat as "no info this
            // tick" rather than crashing the listener.
            return null
        }
        val count = payloadLen / 3
        if (count == 0) return emptyList()
        val out = ArrayList<DecodedThreat>(count)
        var i = 1
        repeat(count) {
            val id = notification[i].toInt() and 0xFF
            val distance = notification[i + 1].toInt() and 0xFF
            val speed = notification[i + 2].toInt() and 0xFF
            // The Varia firmware sometimes pads the trailing triples with
            // (0,0,0) when fewer than the maximum cars are tracked. Skip
            // those so the UI doesn't show ghost cars at 0 m.
            if (id != 0 || distance != 0 || speed != 0) {
                out += DecodedThreat(id, distance, speed)
            }
            i += 3
        }
        return out
    }
}
