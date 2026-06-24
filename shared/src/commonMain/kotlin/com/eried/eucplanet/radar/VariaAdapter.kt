package com.eried.eucplanet.radar

import com.eried.eucplanet.data.DecodedThreat

/**
 * Garmin Varia (RTL5xx / RVR / RCT / eRTL / RearVue) rear-view radar parser —
 * faithful port of Android's VariaAdapter (`…3203` notify characteristic).
 *
 * Wire format: byte[0] header (low nibble = fragment flag: 0 = more coming,
 * 2 = final/standalone), then (id u8, distance_m u8, approach_kmh u8) triplets.
 * Varia caps a notification at ≤20 payload bytes (6 cars), splitting 7+ cars
 * across a `_0` then `_2` fragment — so we buffer `_0` and emit on `_2`.
 * (0,0,0) padding triplets are skipped.
 */
class VariaAdapter {
    private val namePrefixes = listOf("RTL", "RVR", "RCT", "eRTL", "Varia")
    private var pendingPayload = ByteArray(0)

    fun matches(deviceName: String): Boolean {
        val n = deviceName.trim()
        return namePrefixes.any { n.startsWith(it, ignoreCase = true) }
    }

    fun decode(notification: ByteArray): List<DecodedThreat>? {
        if (notification.isEmpty()) return null
        val fragmentFlag = (notification[0].toInt() and 0xFF) and 0x0F
        val payload = if (notification.size > 1) notification.copyOfRange(1, notification.size) else ByteArray(0)
        return when (fragmentFlag) {
            0 -> { pendingPayload = payload; null }
            2 -> {
                val combined = if (pendingPayload.isNotEmpty()) pendingPayload + payload else payload
                pendingPayload = ByteArray(0)
                parseTriplets(combined)
            }
            else -> { pendingPayload = ByteArray(0); parseTriplets(payload) }
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
            if (id != 0 || distance != 0 || speed != 0) out += DecodedThreat(id, distance, speed)
            i += 3
        }
        return out
    }
}
