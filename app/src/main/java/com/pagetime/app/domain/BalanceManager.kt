package com.pagetime.app.domain

import com.pagetime.app.data.local.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BalanceManager(private val repository: SettingsRepository) {

    val browseBalanceSeconds: Flow<Long> =
        repository.settings.map { it.browseBalanceSeconds }

    val totalReadingSeconds: Flow<Long> =
        repository.settings.map { it.totalReadingSeconds }

    val ratio: Flow<Double> =
        repository.settings.map { it.ratio }

    suspend fun browseBalance(): Long = repository.browseBalanceSeconds()

    suspend fun earnFromReading(seconds: Long) {
        if (seconds <= 0) return
        val ratio = repository.ratio()
        repository.addTotalReadingSeconds(seconds)
        repository.addBrowseBalanceSeconds((seconds * ratio).toLong())
    }

    suspend fun setBrowseBalance(seconds: Long) = repository.setBrowseBalanceSeconds(seconds)

    suspend fun setRatio(value: Double) = repository.setRatio(value)
}
