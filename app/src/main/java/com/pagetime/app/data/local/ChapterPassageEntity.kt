package com.pagetime.app.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * What happened to one passage the last time this chapter was generated.
 *
 * WHY THIS TABLE EXISTS
 *
 * Reported from the device: twenty-four passages sent, two cards back. The app
 * could report those two numbers and nothing in between, so the only honest
 * answer to "why?" was that nobody knew — the model's omissions and the rules'
 * rejections were both discarded the instant they happened.
 *
 * That is the same failure this feature has now had six times: the information
 * existed at the moment it mattered and nothing wrote it down. Passages are
 * chosen deterministically and can always be recomputed; what CANNOT be
 * recovered is what the model did with them.
 *
 * WHAT IT MAKES POSSIBLE
 *
 * Two things, and the second is the point. The reader can see why a chapter
 * produced almost nothing — and they can look at the passages that produced
 * nothing, disagree, and ask for a card from the six they care about.
 *
 * Keyed by position within the chapter, so regenerating replaces the record
 * rather than accumulating a history nobody asked for.
 */
@Entity(
    tableName = "chapter_passages",
    primaryKeys = ["bookId", "chapterIndex", "ordinal"],
    indices = [Index(value = ["bookId", "chapterIndex"])]
)
data class ChapterPassageEntity(
    val bookId: String,
    val chapterIndex: Int,
    /** Position of the source chunk within its chapter. */
    val ordinal: Int,
    val startOffset: Int,
    val endOffset: Int,
    /** The passage as it was sent, so the reader judges what the model saw. */
    val text: String,
    /** Where the passage ends, as a fraction of the chapter. */
    val progression: Float,
    /** How many cards survived from this passage. */
    val cardsMade: Int,
    /**
     * Why it produced none.
     *
     * A [PassageOutcome] name. Null when it produced cards.
     */
    val outcome: String?,
    /**
     * Extra detail for a rejection — the rule that threw it out.
     *
     * Separate from [outcome] because "the model wrote something and the rules
     * refused it" and "the rules refused it for THIS reason" are different
     * amounts of knowledge, and the second is not always available.
     */
    val detail: String?,
    val generationKey: String,
    val updatedAt: Long,
)

/** What became of a passage that was sent to the model. */
enum class PassageOutcome {
    /** Cards were made from it. */
    USED,

    /**
     * The model was given it and wrote nothing about it.
     *
     * The most likely outcome to be the app's own fault rather than the
     * passage's: instructions that lean hard on omitting produce exactly this.
     */
    MODEL_SKIPPED,

    /** The model wrote something and the local rules threw it out. */
    REJECTED,

    /** Sent in a request that never came back. */
    REQUEST_FAILED;

    companion object {
        fun of(name: String?): PassageOutcome? =
            entries.firstOrNull { it.name == name }
    }
}
