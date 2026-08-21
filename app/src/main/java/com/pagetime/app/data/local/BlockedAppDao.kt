package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {

    @Query("SELECT * FROM blocked_apps WHERE enabled = 1")
    fun observeEnabled(): Flow<List<BlockedAppEntity>>

    @Query("SELECT * FROM blocked_apps")
    fun observeAll(): Flow<List<BlockedAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: BlockedAppEntity)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
