package com.pagetime.app.data

import java.time.Instant

/** Pure scheduling policy kept separate from Room and Compose for deterministic tests. */
object LearningPolicy {
    fun isDue(due: Instant, now: Instant): Boolean = !due.isAfter(now)

    fun mastery(reviewCount: Int, lastRating: LearningRating?): String = when {
        reviewCount == 0 -> "Not tested"
        lastRating == LearningRating.AGAIN -> "Needs review"
        reviewCount >= 4 && lastRating == LearningRating.EASY -> "Mastered"
        reviewCount >= 2 && lastRating != LearningRating.AGAIN -> "Proficient"
        else -> "Familiar"
    }
}
