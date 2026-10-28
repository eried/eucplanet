package com.eried.eucplanet.data.eucstats

import org.koin.core.qualifier.named

/** Koin qualifiers for the plain values injected into [EucStatsRepository]. */
object EucStatsQualifiers {
    /** The app version string. */
    val AppVersion = named("EucStatsAppVersion")

    /** The OS version string. */
    val OsVersion = named("EucStatsOsVersion")

    /** The trip-file-bytes lambda. */
    val TripFileBytes = named("EucStatsTripFileBytes")

    /** The wall-clock lambda. */
    val Clock = named("EucStatsClock")
}
