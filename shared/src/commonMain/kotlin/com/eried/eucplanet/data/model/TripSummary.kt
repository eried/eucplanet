package com.eried.eucplanet.data.model

/** A recorded trip summary, shown in the Recordings list. */
data class TripSummary(
    val date: String,
    val distanceKm: Float,
    val durationMin: Int,
    val avgKmh: Float,
    val maxKmh: Float,
    val gpsLock: Boolean,
    val synced: Boolean,
    /** Path of the exported DarknessBot-compatible CSV, or null (seed/unsaved). */
    val csvPath: String? = null,
    /** Per-sample telemetry for the trip-detail graphs (empty for seed trips). */
    val samples: List<WheelData> = emptyList(),
)
