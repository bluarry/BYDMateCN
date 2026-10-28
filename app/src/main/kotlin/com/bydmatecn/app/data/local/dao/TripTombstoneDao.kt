package com.bydmatecn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bydmatecn.app.data.local.entity.TripTombstoneEntity

@Dao
interface TripTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tombstone: TripTombstoneEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM trip_tombstones WHERE byd_id = :bydId)")
    suspend fun exists(bydId: Long): Boolean
}
