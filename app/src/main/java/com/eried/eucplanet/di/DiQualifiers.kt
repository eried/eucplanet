package com.eried.eucplanet.di

import org.koin.core.qualifier.named

/**
 * Koin qualifiers for the generic types the graph holds more than one of.
 * Koin keys a definition by its class, and every Set is the same class once
 * its type argument is erased, so each set gets its own name.
 */
object DiQualifiers {
    /** Every external-GPS BLE adapter, see [externalGpsModule]. */
    val ExternalGpsAdapters = named("ExternalGpsAdapters")

    /** Every rear-view radar BLE adapter, see [radarModule]. */
    val RadarAdapters = named("RadarAdapters")
}
