package com.eried.eucplanet.garmin

import android.content.Context
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.nav.NavigationEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op stub used when the Connect IQ Mobile SDK .aar isn't available at build
 * time. See `app/libs/README.md` for how to drop the .aar in, or build with
 * `-PgarminEnabled=false` to keep this stub on a machine that has the .aar.
 *
 * Keep the public surface 1:1 with `src/garminEnabled/kotlin/.../GarminBridge.kt`
 * so EucPlanetApp.kt doesn't have to know which variant it's calling.
 */
@Singleton
class GarminBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val externalGpsRepository: com.eried.eucplanet.data.repository.ExternalGpsRepository,
    private val tripRepository: com.eried.eucplanet.data.repository.TripRepository,
    private val navigationEngine: NavigationEngine
) {
    /** Always-empty in the stub variant — no Garmin devices ever pair when
     *  the CIQ Mobile SDK isn't on the classpath. */
    val pairedDevices: kotlinx.coroutines.flow.StateFlow<List<String>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    /** Always-zero in the stub variant — no Garmin sends ever fire. */
    val deliveryRateHz: kotlinx.coroutines.flow.StateFlow<Double> =
        kotlinx.coroutines.flow.MutableStateFlow(0.0)

    fun start() = Unit
    fun pingWatchToWake() = Unit
    fun publishFarewell() = Unit
    fun sendCloseToWatchBlocking() = Unit
}
