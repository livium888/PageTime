package com.pagetime.app.data.learning

/**
 * The instructions sent to the model for one chapter.
 *
 * BUILT ON WOZNIAK'S RULES OF FORMULATING KNOWLEDGE
 *
 * Nine of the twenty are things a generator can act on. The rest are the
 * learner's job — do not learn what you do not understand, build on basics,
 * use imagery, personalise, prioritise — and no instruction to a model can
 * discharge them, so they are not pretended at here.
 *
 * The nine, and what each one does to the output:
 *
 * MINIMUM INFORMATION. One card, one idea, the smallest testable unit. The
 * rule Wozniak puts first in importance, and the one a language model breaks
 * by default, because a model asked for a question about a paragraph will
 * happily produce a question about the whole paragraph.
 *
 * AVOID SETS. Never "name the three causes". A set is unordered and partial
 * knowledge of it cannot be graded: the reader half-remembers, grades
 * themselves in the middle, and every fact in the set gets an interval that
 * suits none of them — the known ones over-reviewed, the unknown ones
 * forgotten.
 *
 * AVOID ENUMERATIONS. Same problem, slightly better because order helps. If
 * the passage genuinely enumerates, ask about ONE member with enough context
 * to pin it down.
 *
 * COMBAT INTERFERENCE. Cards from one chapter are siblings and will be
 * reviewed together, so two that are nearly alike become impossible to tell
 * apart. Each must be unambiguous on its own.
 *
 * OPTIMISE WORDING. The fewest words that remain precise. Every extra word is
 * something to read on every future review.
 *
 * CONTEXT CUES SIMPLIFY WORDING. A short cue lets the question be shorter and
 * still unambiguous, which is why naming the subject is encouraged rather than
 * discouraged.
 *
 * CLOZE DELETION IS EASY AND EFFECTIVE. Hence the second card type.
 *
 * REDUNDANCY IS NOT A VIOLATION. Wozniak is explicit that approaching one idea
 * from several angles does not breach the minimum information principle, which
 * is what makes a cloze and a question from the same passage legitimate rather
 * than duplication.
 *
 * PROVIDE SOURCES. Already structural here: every card carries the sentence it
 * came from, and the sentence is checked against the book.
 *
 * The source article could not be read directly while writing this — supermemo
 * and every mirror are blocked from the build environment — so this is built on
 * secondary summaries. Worth re-checking against the original.
 */
internal object ChapterPromptText {

    fun build(bookTitle: String, chapterTitle: String, passages: List<String>): String {
        val numbered = passages.mapIndexed { index, text ->
            "[$index]\n$text"
        }.joinToString("\n\n")

        return """
            Write recall prompts from the numbered passages below, taken from a
            book the reader is part-way through. Follow these rules exactly;
            they are Wozniak's rules of formulating knowledge, and they are the
            difference between a card that builds memory and one that wastes
            the reader's time for months.

            ONE IDEA PER PROMPT. The smallest testable unit. If a passage holds
            two ideas, pick the more important one and ignore the other. A
            prompt that needs a paragraph to answer it is worthless: the reader
            will half-remember, grade themselves in the middle, and the
            scheduler will get it wrong in both directions.

            NEVER ASK FOR A SET OR A LIST. No "name the three causes", no "what
            are the factors", no "list the stages". Partial knowledge of a set
            cannot be graded honestly. If the passage enumerates, ask about ONE
            member, with enough context that only that member fits.

            EACH PROMPT MUST STAND ALONE. These will be reviewed together, months
            from now, without the book. Two prompts that could be answered by
            each other's answers are one prompt asked twice, and worse than
            that: they become impossible to tell apart.

            FEWEST WORDS THAT STAY PRECISE. Every extra word is read again on
            every future review. Name the subject to keep the question short —
            "In the Continental System, why…" is better than a sentence of
            setup.

            ASK ABOUT THE WORLD, NOT THE TEXT. Never "what does this passage
            say", "what does the author argue", or anything that stops making
            sense once the book is closed.

            DO NOT PUT THE ANSWER IN THE QUESTION, including a near-synonym of
            it. The reader must produce it, not recognise it.

            TWO KINDS OF PROMPT

            type "qa": a question and a short answer. The answer should be a few
            words. If it needs a sentence, the prompt was too broad.

            type "cloze": one sentence COPIED VERBATIM from the passage with the
            load-bearing term replaced by {{c1::the term}}. Delete the word or
            phrase that carries the claim — not an incidental noun, not a date
            that happens to be there, not an adjective the sentence could lose.
            If removing it leaves a sentence that is still obviously completable
            from the rest, you deleted the wrong thing. Put the deleted text in
            "answer" as well.

            A passage can yield one of each where the idea genuinely supports
            both — asking one idea from two angles is not duplication. It can
            also yield nothing.

            sourceQuote must be copied from that passage CHARACTER FOR
            CHARACTER: the sentence the answer comes from. Do not paraphrase,
            shorten or tidy it. A quote that is not literally in the passage
            causes the whole prompt to be discarded. For a cloze, sourceQuote is
            that same sentence with nothing deleted.

            passageIndex is the number in brackets above the passage you used.

            If a passage carries no idea worth remembering — scene-setting, a
            transition, pure narrative — omit it. Four good prompts beat six
            with two weak ones.

            BOOK: $bookTitle
            CHAPTER: $chapterTitle

            PASSAGES:
            $numbered
        """.trimIndent()
    }
}
