package com.eried.eucplanet.ble.radar

import com.eried.eucplanet.data.model.RadarVendor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin Varia (RTL515, RTL516, RVR315, RCT715, eRTL615, RearVue 820,
 * RVR53320, ...) rear-view radar adapter.
 *
 * Wire format (the `...3203` notify characteristic, the only publicly-
 * readable one on Varia today; the rest of the `6A4E32xx` family is NDA-
 * gated behind Garmin's Radar Data BLE Program):
 *
 *     byte[0]      = packet header
 *                    Low nibble = fragment flag:
 *                      0 = "more fragments coming"
 *                      2 = "final fragment, or standalone frame"
 *                    High nibble = wrapping sequence counter (unused here;
 *                                  the 0/2 flag is enough for reassembly).
 *     byte[1..]    = (id: u8, distance_m: u8, approach_speed_kmh: u8)
 *                    per detected vehicle.
 *
 * **Fragmentation.** Even with a larger negotiated MTU, the Varia firmware
 * caps each BLE notification at <= 20 payload bytes, so one notification
 * carries at most 6 cars (1 header + 6 * 3 bytes). When the radar has 7+
 * cars in view it splits the logical frame across two notifications: the
 * first ends in `_0` (with up to 6 cars), the second ends in `_2` (with
 * the remaining cars). Treating each notification standalone produces the
 * visible-car-count "jumping" the testers report (a 7-car scene reads as
 * alternating 6 / 1 / 6 / 1 frames). We buffer `_0` payloads and emit the
 * concatenated triplets only when the matching `_2` arrives.
 *
 * Reference implementations:
 *  - pycycling/rear_view_radar.py , triplet parser
 *  - Wunderfitz/harbour-tacho src/variaconnectivity.cpp , C++ port with
 *    fragment-aware reassembly using the same low-nibble flag.
 *
 * Vehicles that have moved out of range simply stop appearing in the list,
 * there's no explicit "dropped" event, so a missing id this frame means
 * the radar has lost the track. [RadarRepository] handles drop-out timing.
 *
 * The "threat level" field that the Garmin head units display (none /
 * medium / high) is NOT in this channel; the marketing description
 * suggests it comes from the NDA `...3201` characteristic. We derive it
 * locally from distance + closing rate in the repository, that's what
 * every other open-source client does, and it works fine in practice.
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

    /**
     * Buffer for the payload of `_0` fragments waiting for their `_2`
     * completion. Decode runs sequentially from a single Flow collector
     * on Dispatchers.IO ([RadarRepository]) so no synchronisation is
     * needed. Reset whenever a new `_0` arrives so a lost `_2` (BLE drop)
     * doesn't taint the next logical frame.
     */
    private var pendingPayload: ByteArray = ByteArray(0)

    override fun matches(deviceName: String): Boolean {
        val n = deviceName.trim()
        return namePrefixes.any { n.startsWith(it, ignoreCase = true) }
    }

    override fun decode(notification: ByteArray): List<DecodedThreat>? {
        if (notification.isEmpty()) return null
        val header = notification[0].toInt() and 0xFF
        val fragmentFlag = header and 0x0F
        val payload = if (notification.size > 1) {
            notification.copyOfRange(1, notification.size)
        } else ByteArray(0)

        return when (fragmentFlag) {
            0 -> {
                // First / continuation fragment. Reset the buffer (rather
                // than append) so we drop any half-completed frame left
                // behind by a missing `_2`. Wait for the matching `_2`
                // before emitting anything.
                pendingPayload = payload
                null
            }
            2 -> {
                // Final fragment (or standalone if no `_0` preceded). Glue
                // any buffered payload onto this one, then parse triplets.
                val combined = if (pendingPayload.isNotEmpty()) {
                    pendingPayload + payload
                } else payload
                pendingPayload = ByteArray(0)
                parseTriplets(combined)
            }
            else -> {
                // Unknown fragment flag. Treat the payload as standalone
                // so a firmware revision adding new flags doesn't silently
                // freeze the listener. Clear any pending buffer for safety.
                pendingPayload = ByteArray(0)
                parseTriplets(payload)
            }
        }
    }

    private fun parseTriplets(payload: ByteArray): List<DecodedThreat>? {
        if (payload.size % 3 != 0) return null
        val count = payload.size / 3
        if (count == 0) return emptyList()
        val out = ArrayList<DecodedThreat>(count)
        var i = 0
        repeat(count) {
            val id = payload[i].toInt() and 0xFF
            val distance = payload[i + 1].toInt() and 0xFF
            val speed = payload[i + 2].toInt() and 0xFF
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
