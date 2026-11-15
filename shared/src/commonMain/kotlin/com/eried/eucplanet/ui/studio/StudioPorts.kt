package com.eried.eucplanet.ui.studio

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.PhoneImu
import com.eried.eucplanet.data.repository.PhoneLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// What OverlayStudioViewModel needs from the rest of the app, one small
// interface per collaborator with only the members it calls, like
// DashboardPorts. The Android classes implement them as they are (or through
// a thin adapter); iOS supplies its own when the studio lands there.

/** The trip recorder and the phone's location (TripRepository). */
interface StudioTrips {
    /** The live phone fix the MAP element centres on, null before the first. */
    val currentFix: StateFlow<PhoneLocation?>
    /** Every recorded trip, newest first. */
    val allTrips: Flow<List<TripRecord>>
    /** The trip being recorded now, which has no finished CSV yet. */
    val currentTripId: StateFlow<Long?>
    /** Starts location updates; a second call does nothing. */
    fun startLocationUpdates()
    suspend fun tripById(id: Long): TripRecord?
    /** The trip's CSV text, or null when its file is gone. Blocking I/O. */
    fun tripCsv(record: TripRecord): String?
}

/** The phone's accelerometer, plus the re-arm Android needs after the background. */
interface StudioImu : PhoneImu {
    /** Re-registers the listener after the app was in the background. */
    fun refresh()
}
