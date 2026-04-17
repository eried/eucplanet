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
    val uploadedAt: Long? = null
)
