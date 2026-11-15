package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// The Android side of StudioPorts: the existing classes, unchanged.

class AndroidStudioTrips(private val trips: TripRepository) : StudioTrips {
    // App-lifetime, like the repository whose fixes it mirrors.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val currentFix = trips.currentLocation.map { it?.toPhoneLocation() }
        .stateIn(scope, SharingStarted.Eagerly, trips.currentLocation.value?.toPhoneLocation())
    override val allTrips = trips.allTrips
    override val currentTripId = trips.currentTripId
    override fun startLocationUpdates() = trips.startLocationUpdates()
    override suspend fun tripById(id: Long): TripRecord? = trips.getTripById(id)
    override fun tripCsv(record: TripRecord): String? {
        val file = trips.getTripFile(record)
        return if (file.exists()) file.readText() else null
    }
}
