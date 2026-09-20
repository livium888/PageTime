package com.pagetime.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalReadingAppDao {

    @Query("SELECT * FROM external_reading_apps WHERE enabled = 1")
    fun observeEnabled(): Flow<List<ExternalReadingAppEntity>>

    @Query("SELECT * FROM external_reading_apps")
    fun observeAll(): Flow<List<ExternalReadingAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: ExternalReadingAppEntity)

    @Query("DELETE FROM external_reading_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
