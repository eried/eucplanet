package com.eried.eucplanet.ble.tpms

import kotlinx.serialization.Serializable

/**
 * A TPMS sensor the rider has bound to the app. Multiple sensors are
 * supported, one per wheel; sensors stay nearby + auto-reconnect by the
 * advertising address rather than a stored GATT bond -- cheap aftermarket
 * BLE TPMS broadcast pressure in the advertising payload only, no
 * connection required.
 *
 * Identity:
 *  - [id6Hex] is the printed sensor ID (last 3 bytes of the BLE MAC,
 *    uppercase, no separators -- "5B611B" on the QR code / sticker).
 *    It's also what the rider types when binding manually.
 *  - We do NOT store the full 6-byte MAC because the printed ID is
 *    sufficient to filter advertising scans (suffix match) and is the
 *    user-readable handle the rider will recognise on the sensor.
 *
 * [label] is the rider's name for this sensor ("Oryx front", "Lynx S
 * rear"). Empty until the rider edits it.
 */
@Serializable
data class TpmsSensor(
    val id6Hex: String,
    val label: String = "",
    val addedAtMs: Long = 0L,
)

/**
 * Live reading from a TPMS sensor. Internal pressure unit is kPa; the UI
 * converts to the rider's chosen display unit (kPa / bar / psi) via the
 * pressure-unit preference. Storing as kPa makes the CSV column lossless
 * across the three display units.
 *
 * Any field may be null when the sensor's advertising format doesn't
 * expose that reading:
 *  - the well-documented TomTom-style sensors carry pressure + temp +
 *    battery in the advert and all four are populated.
 *  - some cheap aftermarket sensors only broadcast an alarm-state enum
 *    in advertising and require a GATT connection for the pressure
 *    value; for those we surface [alarm] only.
 *
 * [lastSeenMs] is monotonic clock time of the latest matching advert,
 * so the UI can mark stale readings without needing the sensor's own
 * timestamp.
 */
data class TpmsReading(
    val pressureKPa: Float? = null,
    val temperatureC: Float? = null,
    val batteryPct: Int? = null,
    val alarm: TpmsAlarm = TpmsAlarm.UNKNOWN,
    val lastSeenMs: Long,
    /** Raw advertising payload bytes (after the AD-type byte) for diagnostics. */
    val rawHex: String = "",
)

/** Alarm state broadcast by most TPMS sensors. */
enum class TpmsAlarm {
    /** Pressure within the configured low/high thresholds. */
    OK,
    /** Pressure above the high threshold (over-inflation). */
    HIGH,
    /** Pressure below the low threshold (leak / under-inflation). */
    LOW,
    /** Sensor hasn't communicated yet, or its format doesn't expose an alarm. */
    UNKNOWN,
}

/**
 * Result of a single scan tick used by the bind UI: a sensor that's
 * currently advertising and not yet bound to the rider's account.
 */
data class TpmsDiscoveredSensor(
    val id6Hex: String,
    val rssi: Int,
    val reading: TpmsReading,
)
