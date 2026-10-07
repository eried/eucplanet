package com.eried.eucplanet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val fileName: String,
    val distanceKm: Float = 0f,
    // Cloud upload state: 0=not applicable/none, 1=pending, 2=uploaded, 3=failed
    val uploadStatus: Int = 0,
    val uploadedAt: Long? = null,
    // --- eucstats online upload (separate from folder-sync uploadStatus above) ---
    val tripUuid: String? = null,            // UUIDv4 minted at trip save (live-only)
    val eucstatsStatus: Int = 0,             // 0=n/a 1=pending 2=uploaded 3=failed(terminal)
    val eucstatsUploadedAt: Long? = null,
    val eucstatsValidation: String? = null,  // "validated" | "flagged"
    val isMockLocation: Boolean = false,     // any fix during recording was mock
    val sampleCount: Int = 0,                // CSV data rows
    val wheelMetaJson: String? = null,       // {brand,model,serial,ble_mac,ble_name,firmware}
    // Rider-set trip name; null = fall back to the formatted date. Also written
    // into the CSV Extra column as trip.name= so it survives export / Dropbox.
    val customName: String? = null,
    // Dropbox backup state, separate from uploadStatus above, which is the
    // backup FOLDER. The two are different destinations that fail
    // independently, and a rider with only Dropbox linked had no per-trip
    // state at all: the list showed a green tick that meant the folder, or
    // nothing whatsoever. 0=n/a 1=pending 2=uploaded 3=failed
    val dropboxStatus: Int = 0,
    val dropboxUploadedAt: Long? = null,
)
