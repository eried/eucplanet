package com.eried.eucplanet.data.eucstats

/**
 * The eucstats API base this build talks to. Each app sets [apiBaseUrl] at
 * start (Android from BuildConfig, iOS from its launch environment or
 * Info.plist); a crews pairing code is trusted without question only when it
 * names this server.
 */
object EucStatsServer {
    const val DEFAULT_API_BASE_URL = "https://eucstats.ried.no/api/v1"

    var apiBaseUrl: String = DEFAULT_API_BASE_URL
}
