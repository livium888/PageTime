package com.pagetime.app.data

import com.pagetime.app.data.local.BlockedAppDao
import com.pagetime.app.data.local.BlockedAppEntity
import kotlinx.coroutines.flow.Flow

class BlockedAppRepository(private val dao: BlockedAppDao) {

    fun observeEnabled(): Flow<List<BlockedAppEntity>> = dao.observeEnabled()

    fun observeAll(): Flow<List<BlockedAppEntity>> = dao.observeAll()

    suspend fun setBlocked(packageName: String, appName: String, enabled: Boolean) {
        if (enabled) {
            dao.upsert(BlockedAppEntity(packageName, appName, enabled = true))
        } else {
            dao.delete(packageName)
        }
    }
}
