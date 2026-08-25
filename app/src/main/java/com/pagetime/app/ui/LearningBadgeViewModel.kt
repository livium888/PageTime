package com.pagetime.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LearningBadgeViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as PageTimeApp).container.learningRepository

    val dueCount = repository.observeStats()
        .map { it.dueCards }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
