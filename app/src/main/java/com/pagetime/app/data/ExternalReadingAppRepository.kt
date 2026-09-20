package com.pagetime.app.data

import com.pagetime.app.data.local.ExternalReadingAppDao
import com.pagetime.app.data.local.ExternalReadingAppEntity
import kotlinx.coroutines.flow.Flow

/** Which installed apps the reader trusts for [com.pagetime.app.data.usage.ExternalReadingTracker]. */
class ExternalReadingAppRepository(private val dao: ExternalReadingAppDao) {

    fun observeEnabled(): Flow<List<ExternalReadingAppEntity>> = dao.observeEnabled()

    fun observeAll(): Flow<List<ExternalReadingAppEntity>> = dao.observeAll()

    suspend fun setTrusted(packageName: String, appName: String, enabled: Boolean) {
        if (enabled) {
            dao.upsert(ExternalReadingAppEntity(packageName, appName, enabled = true))
        } else {
            dao.delete(packageName)
        }
    }
}
