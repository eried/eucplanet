package com.eried.eucplanet.data.model

/** Online-backup state of a trip, mirroring Android's per-trip EUC Stats status. */
enum class TripBackup {
    /** Not backed up (online backup off, or never attempted). */
    Off,

    /** Upload in flight / queued. */
    Pending,

    /** Successfully on the leaderboard. */
    Uploaded,

    /** Upload failed — the rider can retry. */
    Failed,
}

/** A recorded trip summary, shown in the Recordings list. */
data class TripSummary(
    val date: String,
    val distanceKm: Float,
    val durationMin: Int,
    val avgKmh: Float,
    val maxKmh: Float,
    val gpsLock: Boolean,
    /** Online-backup state (EUC Stats). Drives the cloud badge + retry. */
    val backup: TripBackup = TripBackup.Off,
    /** Stable id so an async upload result can be matched back to its trip. */
    val id: Long = 0L,
    /** Path of the exported DarknessBot-compatible CSV, or null (seed/unsaved). */
    val csvPath: String? = null,
    /** Per-sample telemetry for the trip-detail graphs (empty for seed trips). */
    val samples: List<WheelData> = emptyList(),
)
