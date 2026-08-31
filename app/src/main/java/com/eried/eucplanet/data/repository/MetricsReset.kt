package com.eried.eucplanet.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Start counting again", from wherever the rider asks.
 *
 * Three things are reset and they live in two repositories: the app's running
 * trip meter, the in-memory metric history behind the sparklines and the
 * metric-detail charts, and, where the family has a command for it, the
 * wheel's own onboard trip odometer.
 *
 * One class because there are three surfaces asking - the dashboard button,
 * the service overlay, and physical buttons through the action catalog - and
 * they had drifted: the dashboard sent only the wheel command and reported
 * "not supported on this wheel" to everyone whose family lacks it, while the
 * overlay sent the same command and swallowed the answer, so on those wheels
 * it silently did nothing at all.
 *
 * Recorded trips and settings are never touched. This is the trip meter, not
 * the trip history.
 */
@Singleton
class MetricsReset @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val tripMeterRepository: TripMeterRepository,
) {

    /**
     * [wheelTripCleared] is true only when a reset command actually went to the
     * wheel, which is Veteran alone today. Everything else in the result
     * happened regardless, including with no wheel connected at all: clearing
     * yesterday's numbers before setting off is a reasonable thing to do in a
     * kitchen.
     */
    data class Result(val wheelTripCleared: Boolean)

    suspend fun resetAll(): Result {
        tripMeterRepository.resetAndPersist()
        wheelRepository.resetAllHistory()
        // BLE write, off the main thread like every other command path.
        val cleared = withContext(Dispatchers.IO) { wheelRepository.resetTripMeter() }
        return Result(wheelTripCleared = cleared)
    }
}
