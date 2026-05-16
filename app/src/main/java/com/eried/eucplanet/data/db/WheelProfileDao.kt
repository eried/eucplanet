package com.eried.eucplanet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eried.eucplanet.data.model.WheelProfile

@Dao
interface WheelProfileDao {

    @Query("SELECT * FROM wheel_profile WHERE bleName = :name LIMIT 1")
    suspend fun getByName(name: String): WheelProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: WheelProfile)
}
