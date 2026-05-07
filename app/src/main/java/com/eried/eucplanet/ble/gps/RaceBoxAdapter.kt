package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RaceBox Mini / Mini S / Pro adapter.
 *
 * BLE: RaceBox advertises Nordic UART (same UUID as InMotion V14 — that's why
 * matching is name-based, not service-based). Devices appear with names like
 * "RaceBox Mini 1234567" — prefix `RaceBox` is the reliable filter per the
 * vendor's BLE protocol notes (github.com/Racebox/racebox-public).
 *
 * Phase 1: just identity + match. The decode() returns null until Phase 2
 * lands the UBX-NAV-PVT parser.
 */
@Singleton
class RaceBoxAdapter @Inject constructor() : ExternalGpsAdapter {

    override val source: ExternalGpsSource = ExternalGpsSource.RACEBOX

    override fun matches(deviceName: String): Boolean =
        deviceName.startsWith("RaceBox", ignoreCase = true)

    override fun decode(notification: ByteArray): ExternalGpsSample? {
        // Phase 2: UBX-NAV-PVT parser (header B5 62, class 01, id 07, payload 92 bytes,
        // CK_A/CK_B Fletcher-8 checksum). Wrapped in BLE-fragmented chunks the
        // connection manager forwards verbatim — reassemble here.
        return null
    }
}
