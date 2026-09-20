package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AllowedSiteDao {

    @Query("SELECT * FROM allowed_sites WHERE enabled = 1 ORDER BY host, pathPrefix")
    fun observeEnabled(): Flow<List<AllowedSiteEntity>>

    @Query("SELECT * FROM allowed_sites ORDER BY host, pathPrefix")
    fun observeAll(): Flow<List<AllowedSiteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: AllowedSiteEntity)

    @Query("DELETE FROM allowed_sites WHERE id = :id")
    suspend fun delete(id: String)
}
