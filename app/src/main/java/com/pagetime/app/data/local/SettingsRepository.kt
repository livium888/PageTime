package com.pagetime.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val browseBalanceSeconds: Long = 0,
    /** Browse seconds earned per 1 second of reading. */
    val ratio: Double = 1.0,
    val totalReadingSeconds: Long = 0
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BALANCE = longPreferencesKey("browse_balance_seconds")
        val RATIO = doublePreferencesKey("ratio")
        val TOTAL_READING = longPreferencesKey("total_reading_seconds")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            browseBalanceSeconds = p[Keys.BALANCE] ?: 0L,
            ratio = p[Keys.RATIO] ?: 1.0,
            totalReadingSeconds = p[Keys.TOTAL_READING] ?: 0L
        )
    }

    suspend fun browseBalanceSeconds(): Long =
        context.dataStore.data.first()[Keys.BALANCE] ?: 0L

    suspend fun ratio(): Double =
        context.dataStore.data.first()[Keys.RATIO] ?: 1.0

    suspend fun setBrowseBalanceSeconds(value: Long) {
        context.dataStore.edit { it[Keys.BALANCE] = value.coerceAtLeast(0L) }
    }

    suspend fun addBrowseBalanceSeconds(delta: Long) {
        context.dataStore.edit { p ->
            p[Keys.BALANCE] = ((p[Keys.BALANCE] ?: 0L) + delta).coerceAtLeast(0L)
        }
    }

    suspend fun addTotalReadingSeconds(delta: Long) {
        context.dataStore.edit { p ->
            p[Keys.TOTAL_READING] = (p[Keys.TOTAL_READING] ?: 0L) + delta
        }
    }

    suspend fun setRatio(value: Double) {
        context.dataStore.edit { it[Keys.RATIO] = value.coerceIn(0.1, 10.0) }
    }
}
