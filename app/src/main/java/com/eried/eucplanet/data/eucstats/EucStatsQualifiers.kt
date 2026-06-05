package com.eried.eucplanet.data.eucstats

import javax.inject.Qualifier

/** Qualifier for the app version string injected into [EucStatsRepository]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EucStatsAppVersion

/** Qualifier for the OS version string injected into [EucStatsRepository]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EucStatsOsVersion

/** Qualifier for the trip-file-bytes lambda injected into [EucStatsRepository]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EucStatsTripFileBytes

/** Qualifier for the wall-clock lambda injected into [EucStatsRepository]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EucStatsClock
