package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedSiteDao {

    @Query("SELECT * FROM blocked_sites WHERE enabled = 1 ORDER BY host, pathPrefix")
    fun observeEnabled(): Flow<List<BlockedSiteEntity>>

    @Query("SELECT * FROM blocked_sites ORDER BY host, pathPrefix")
    fun observeAll(): Flow<List<BlockedSiteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: BlockedSiteEntity)

    @Query("DELETE FROM blocked_sites WHERE id = :id")
    suspend fun delete(id: String)
}
