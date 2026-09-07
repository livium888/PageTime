package com.pagetime.app.data.learning

/**
 * The only thing the chapter generator needs from a language model.
 *
 * WHY THIS EXISTS
 *
 * The generator is the most intricate piece of the flashcard pipeline — it
 * splits a chapter into batches, keeps one sifter across all of them, pairs
 * every returned prompt with the passage it actually came from, and has to
 * survive some of its requests failing. All of that is ordinary logic, and
 * none of it was reachable by a test, because the generator held a concrete
 * [GeminiLearningClient] carrying OkHttp, a settings repository and a network
 * call.
 *
 * Three methods behind an interface makes the whole thing testable with a
 * scripted stand-in: batch two fails, batches one and three survive, and the
 * prompt from batch two is filed against passage nine rather than passage one.
 * Those are the failures that would otherwise be found by a reader.
 *
 * The real implementation is [GeminiLearningClient]; this adds no indirection
 * to it beyond the interface itself.
 */
interface ChapterPromptWriter {

    /** Whether a usable API key exists at all. */
    fun hasKey(): Boolean

    /** The model that will serve the request, for the usage log. */
    fun currentModel(): String

    /**
     * Writes prompts for one batch of passages.
     *
     * The returned prompts number their passages from zero WITHIN THIS CALL.
     * The caller is responsible for tying them back to the right passage; a
     * batch does not know where it sits in the chapter.
     */
    suspend fun generateChapterPrompts(
        bookTitle: String,
        chapterTitle: String,
        passages: List<String>,
    ): List<RawPrompt>
}
