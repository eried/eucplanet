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

    /** Every wheel this phone has a profile for, for the trip-tools wheel
     *  picker. bleName is the same identity a trip CSV records. */
    @Query("SELECT bleName FROM wheel_profile ORDER BY bleName")
    suspend fun allNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: WheelProfile)
}
