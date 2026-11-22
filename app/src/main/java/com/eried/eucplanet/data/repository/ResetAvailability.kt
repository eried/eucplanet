package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.TripMeterState

/*
 * "Is there anything to clear" for the data resets: the metric detail Reset,
 * the trip meter Reset, the Battery screen's Reset data and the dashboard's
 * Reset metrics. A reset button is live exactly when pressing it would clear
 * something the rider can see, so each check reads the same state its reset
 * clears.
 */

/** A buffer that holds only zeros draws a flat line at nothing, so it counts as empty. */
fun List<MetricSample>.hasNonZeroSample(): Boolean =
    any { it.value != 0f && !it.value.isNaN() }

/** The buffer [WheelRepository.resetHistory] clears for [key]. */
fun FullMetricHistory.samplesFor(key: String): List<MetricSample> = when (key) {
    "BATTERY" -> battery
    "TEMPERATURE" -> temperature
    "VOLTAGE" -> voltage
    "CURRENT" -> current
    "LOAD" -> load
    "SPEED" -> speed
    else -> extras[key].orEmpty()
}

/** True when the metric detail Reset for [key] would clear a recorded reading. */
fun FullMetricHistory.hasDataFor(key: String): Boolean = samplesFor(key).hasNonZeroSample()

/** True when Reset all ([WheelRepository.resetAllHistory]) would clear anything. */
fun FullMetricHistory.hasAnyData(): Boolean =
    battery.hasNonZeroSample() || temperature.hasNonZeroSample() ||
        voltage.hasNonZeroSample() || current.hasNonZeroSample() ||
        load.hasNonZeroSample() || speed.hasNonZeroSample() ||
        extras.values.any { it.hasNonZeroSample() }

/** True when the trip meter shows a distance, a time or a split. */
fun TripMeterState.hasData(): Boolean =
    distanceKm != 0f || activeMs != 0L || splits.isNotEmpty()

/**
 * True when the Battery screen's Reset data ([WheelRepository.resetChargingSession])
 * would clear a recorded session: a graph, an energy total, an ETA, a
 * prediction log or the added-charge band.
 */
fun ChargingSnapshot.hasSessionData(): Boolean =
    chargeHistory.isNotEmpty() || voltageHistory.isNotEmpty() || tempHistory.isNotEmpty() ||
        predictionHistory.isNotEmpty() ||
        packMinHistory.any { it.isNotEmpty() } || packMaxHistory.any { it.isNotEmpty() } ||
        packAvgHistory.any { it.isNotEmpty() } ||
        targetEtaMs != null || fullEtaMs != null ||
        sessionEnergyWh != 0f || sessionEnergyInWh != 0f || sessionEnergyOutWh != 0f ||
        estimatedChargedWh != 0f || estimate.addedPercent != 0f

/**
 * True when the dashboard's Reset metrics ([MetricsReset.resetAll]) would clear
 * something: the trip meter, the metric history or the ride's Wh used. A
 * connected wheel also counts, because the reset can zero the wheel's own trip
 * odometer and the history refills from it every second anyway.
 */
fun metricsResetAvailable(
    tripMeter: TripMeterState,
    history: FullMetricHistory,
    whConsumed: Float,
    wheelConnected: Boolean,
): Boolean =
    wheelConnected || tripMeter.hasData() || history.hasAnyData() ||
        (whConsumed != 0f && !whConsumed.isNaN())
