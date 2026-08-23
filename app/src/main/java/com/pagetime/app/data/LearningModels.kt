package com.pagetime.app.data

import com.pagetime.app.data.local.LearningCardEntity

fun LearningCardEntity.masteryLabel(): String = LearningPolicy.mastery(
    reviewCount = reviewCount,
    lastRating = lastRating?.let { LearningRating.fromValue(it) }
)
