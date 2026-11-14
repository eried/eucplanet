package com.eried.eucplanet.nav

import android.content.Context
import android.content.Intent
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.service.WheelService
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.Executors

/**
 * The engine on Android: TripRepository's GPS, WheelService kept in the
 * foreground for the ride, and the engine's own daemon thread.
 */
class AndroidNavigationHost(
    private val context: Context,
    private val trips: TripRepository,
) : NavigationHost {

    override val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nav-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Every fix the repository publishes, converted as it arrives.
    override val fixes: Flow<PhoneLocation?> = trips.currentLocation.map { it?.toPhoneLocation() }

    override fun currentFix(): PhoneLocation? = trips.currentLocation.value?.toPhoneLocation()

    override fun startLocationUpdates() = trips.startLocationUpdates()

    override fun startNavigationService() {
        context.startForegroundService(
            Intent(context, WheelService::class.java).setAction(WheelService.ACTION_START_NAVIGATION)
        )
    }

    override fun setNavigating(on: Boolean) = trips.setNavigating(on)
}
