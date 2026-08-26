package com.pagetime.app.data

import com.pagetime.app.data.local.LearningCardEntity

data class LearningCardPreview(
    val id: String,
    val chapterLabel: String,
    val mastery: String,
    val reviewCount: Int,
    val hasSource: Boolean,
    val cardType: String
)

fun LearningCardEntity.preview(): LearningCardPreview = LearningCardPreview(
    id = id,
    chapterLabel = chapterTitle ?: "Chapter ${chapterIndex + 1}",
    mastery = masteryLabel(),
    reviewCount = reviewCount,
    hasSource = sourceLocator != null || sourceFraction != null,
    cardType = cardType
)
