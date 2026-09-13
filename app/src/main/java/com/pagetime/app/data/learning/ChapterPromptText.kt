package com.pagetime.app.data.learning

import com.pagetime.app.data.embed.ChapterTopics

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
 *
 * WHY BLOOM'S FIRST TWO LEVELS AND NOT THE INTERESTING ONES
 *
 * Reported from the device: the questions were poor — lookup, not thought.
 * That was true and Wozniak's rules alone caused it. Optimising for the
 * smallest testable unit, with nothing pulling the other way, reliably
 * produces trivia.
 *
 * The instinct is then to reach for Bloom's top: evaluate, critique, create.
 * That is the wrong fix and would be worse. A spaced-repetition prompt has to
 * be answerable in seconds and gradeable the same way twice — "assess whether
 * this argument survives" is a fine question and a useless card, because it
 * takes minutes, has no checkable answer, and hands the scheduler noise.
 * Matuschak makes the same point as a criterion: a prompt should be tractable,
 * near-always answerable, or the reader stops reviewing it.
 *
 * So the target is levels one and two — remember, and understand. Why
 * something follows, what breaks without it, how two things differ. Still a
 * sentence, still gradeable, and genuinely requiring thought rather than
 * lookup.
 *
 * WHY THIS IS ITS OWN FILE
 *
 * It used to be a raw string inline in the client, and it shipped with every
 * template marker escaped as a LITERAL dollar sign — so the model was sent
 * "BOOK: ${'$'}bookTitle" and, worse, "PASSAGES: ${'$'}numbered" with no
 * passages in it at all. The request never carried the book. Nothing could
 * catch that, because a prompt built inside a suspend function behind a
 * network call is not reachable by any test.
 *
 * Pulled out here it is a pure function over its inputs, and the test can
 * simply assert that the passages are in it.
 *
 * THE INSTRUCTIONS ARE THE READER'S, THE CONTRACT IS NOT
 *
 * Report from the device: the cards are bad, and the reader wants to say what
 * a good one is rather than wait for another release. So the rule block is a
 * template a reader can edit in Settings, exactly as the Lumen capture prompt
 * is, and [DEFAULT_TEMPLATE] is the shipped one.
 *
 * What a reader CANNOT change is the shape of the reply. The parser reads
 * fixed fields and [ChapterPromptRules] checks them against the book, so
 * [render] appends the output contract to whatever instructions are in force.
 * That is deliberate: the fields are the difference between a card grounded in
 * the passage and a plausible sentence the model made up, and a settings field
 * that could remove the check would be a field that can quietly install
 * falsehoods into a review schedule.
 *
 * The how-many paragraph is likewise not optional in effect: when the template
 * does not place [HOW_MANY_TOKEN], the app appends it, so the floor that a
 * hand-picked passage relies on cannot be edited away by accident.
 */
internal object ChapterPromptText {

    /** Placeholder replaced with the numbered passages. Required. */
    const val PASSAGES_TOKEN = "{{passages}}"

    /** Placeholder replaced with the book's title. */
    const val BOOK_TOKEN = "{{book}}"

    /** Placeholder replaced with the chapter's title. */
    const val CHAPTER_TOKEN = "{{chapter}}"

    /**
     * Placeholder replaced with the how-many paragraph.
     *
     * Optional. Missing one is not an error — the app appends the shipped
     * paragraph — because the paragraph carries the switch from a ceiling to a
     * floor when the reader hand-picked the passages, and a template that
     * silently dropped it would make a chosen passage produce nothing.
     */
    const val HOW_MANY_TOKEN = "{{howmany}}"

    /**
     * How many to write, and how hard to lean on omitting.
     *
     * THIS PARAGRAPH WAS THE BUG
     *
     * The first version of it said a passage with no idea "should return none
     * at all", that reaching the ceiling was "padding", that padding was
     * "worse than a short chapter", and closed by asking the model to omit
     * anything not worth remembering. Counted against the rest of the
     * instructions, that is eleven prohibitions and one permission.
     *
     * A chapter then came back with two cards from twenty-four passages. The
     * ceiling had been raised to three per passage and the surrounding text
     * simultaneously made refusing the safest possible answer, so the model
     * did the sensible thing and refused.
     *
     * The quality rules are all still here — one idea per prompt, no sets, no
     * giveaways, a real quote. What is gone is the editorialising ON TOP of
     * them, which added no rule and cost most of the output.
     */
    private fun howMany(perPassage: Int, insist: Boolean): String = if (insist) {
        """
            A person read these passages and chose them. Write AT LEAST ONE
            prompt for EVERY passage below, and up to $perPassage where the
            passage carries more than one idea.

            Do not skip a passage. If it seems thin, find the most specific
            checkable fact in it — a name, a number, a definition, a cause, a
            consequence — and ask about that. Someone has already decided this
            is worth remembering; your job is to find the question, not to
            judge the choice.
        """.trimIndent()
    } else {
        """
            Write one to $perPassage prompts per passage. Most passages in a
            non-fiction chapter carry at least one fact worth remembering, so
            one is the normal answer and $perPassage is for a passage genuinely
            holding that many separate ideas.

            Skip a passage only when it truly holds nothing checkable — pure
            scene-setting, a transition, a sentence of connective tissue. That
            is the exception, not the expectation.
        """.trimIndent()
    }

    /** The passages as the model cites them back: numbered from zero. */
    private fun numbered(passages: List<String>): String =
        passages.mapIndexed { index, text -> "[$index]\n$text" }.joinToString("\n\n")

    /**
     * The reply's shape, in words, appended to every set of instructions.
     *
     * NOT EDITABLE, ON PURPOSE
     *
     * `passageIndex` is how a returned prompt is tied to the passage it came
     * from, and therefore to the offset that decides where it surfaces in the
     * book; `sourceQuote` is what [ChapterPromptRules] checks against the
     * passage, and the check that cannot be argued with is the reason an
     * invented sentence cannot become a scheduled card. A reader editing these
     * away would not get different-shaped cards, they would get no cards — the
     * parser would find nothing and the rules would discard it.
     *
     * So the app says this part, in the same words, whichever instructions are
     * in force.
     */
    private fun contract(): String =
        """
            Each passage above is numbered in brackets. passageIndex is that
            number.

            type is either "qa" or "cloze". sourceQuote must be copied from
            that passage CHARACTER FOR CHARACTER: the sentence the answer comes
            from. Do not paraphrase, shorten or tidy it. A quote that is not
            literally in the passage causes the whole prompt to be discarded.
            For a cloze, sourceQuote is that same sentence with nothing deleted.
        """.trimIndent()

    /**
     * The instructions shipped with the app, and the text a reader edits when
     * they tailor flashcards in Settings.
     *
     * Everything a rule can act on lives here; only the reply's shape does not.
     */
    val DEFAULT_TEMPLATE: String =
        """
            Write recall prompts from the numbered passages below, taken from a
            book the reader is part-way through. Follow these rules exactly;
            they are Wozniak's rules of formulating knowledge, and they are the
            difference between a card that builds memory and one that wastes
            the reader's time for months.

            ONE IDEA PER PROMPT. The smallest testable unit. If a passage holds
            two ideas, that is two prompts, not one prompt covering both. A
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

            WHAT KIND OF QUESTION

            Two levels, and nothing above them.

            REMEMBERING. A fact, a name, a number, a definition — something
            stated in the passage that a reader should carry away.

            UNDERSTANDING. Why something follows, what would break without it,
            how two things differ, what a claim predicts about a case the book
            does not mention. These take a moment's thought and are answerable
            in one sentence.

            Aim for a mix, leaning toward understanding. A chapter answered
            entirely from the first level is trivia, and the reader will
            correctly stop caring about it.

            NOT ABOVE THAT. Do not ask the reader to evaluate an argument,
            weigh evidence, compare against something outside the passage, or
            produce anything original. Those are good questions and terrible
            prompts: they take minutes rather than seconds, two people would
            grade them differently, and a prompt that cannot be graded
            consistently poisons the schedule that decides when it comes back.

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

            HOW MANY

            $HOW_MANY_TOKEN

            Two prompts from one passage must be answerable independently. If
            knowing the answer to one gives away the other, they are one prompt
            written twice.

            EXPLANATION

            Every prompt carries one, and it is the part the reader learns
            from. Two or three sentences saying WHY the answer is the answer:
            the mechanism, the reason, the consequence — what a good teacher
            says after you have guessed.

            It must be YOUR OWN WORDS. Do not copy or lightly reword the
            passage. The passage is shown underneath already; repeating it
            teaches nothing and is the single most common way this goes wrong.
            If the only thing you can say is what the passage said, the prompt
            was not worth asking.

            Where it helps, say what the reader would get wrong, or what the
            answer rules out. Never open with "The passage states" or "The
            author says" — explain the world, not the text.

            BOOK: $BOOK_TOKEN
            CHAPTER: $CHAPTER_TOKEN

            PASSAGES:
            $PASSAGES_TOKEN
        """.trimIndent()

    /**
     * Fills a template's placeholders and appends the fixed output contract.
     *
     * The how-many paragraph is inserted by the caller when the template does
     * not place [HOW_MANY_TOKEN], so the floor for a hand-picked passage
     * survives an edit that removes the paragraph by accident.
     */
    fun render(
        template: String,
        bookTitle: String,
        chapterTitle: String,
        passages: List<String>,
        perPassage: Int = ChapterTopics.PROMPTS_PER_PASSAGE,
        insist: Boolean = false,
    ): String {
        val how = howMany(perPassage, insist)
        val placed = template.contains(HOW_MANY_TOKEN)
        val body = template
            .replace(BOOK_TOKEN, bookTitle)
            .replace(CHAPTER_TOKEN, chapterTitle)
            .replace(HOW_MANY_TOKEN, how)
            .replace(PASSAGES_TOKEN, numbered(passages))
        val withCount = if (placed) body else body.trimEnd() + "\n\n" + how
        return withCount.trimEnd() + "\n\n" + contract()
    }

    /**
     * The instructions for one chapter, edited or shipped.
     *
     * [template] defaults to the built-in text; the reader's own reaches here
     * from storage through [ChapterPromptGenerator].
     */
    fun build(
        bookTitle: String,
        chapterTitle: String,
        passages: List<String>,
        perPassage: Int = ChapterTopics.PROMPTS_PER_PASSAGE,
        /**
         * The reader picked these passages by hand and wants a card from each.
         *
         * Changes the instruction from a ceiling into a floor. Omitting is the
         * right default when the app chose the passages; it is the wrong answer
         * when a person looked at this exact paragraph and said they wanted to
         * remember it.
         */
        insist: Boolean = false,
        /** The reader's own instructions, or null for the shipped ones. */
        template: String? = null,
    ): String = render(
        template = template?.takeIf { it.isNotBlank() } ?: DEFAULT_TEMPLATE,
        bookTitle = bookTitle,
        chapterTitle = chapterTitle,
        passages = passages,
        perPassage = perPassage,
        insist = insist,
    )

    /**
     * Why [template] cannot be used, or null when it is fine.
     *
     * A template without [PASSAGES_TOKEN] would send the model instructions
     * about passages it never sees — the reader would wait, pay, and get
     * questions about nothing — so that one is refused rather than warned
     * about. A missing [HOW_MANY_TOKEN] is not a problem: the app appends the
     * shipped paragraph.
     */
    fun templateProblem(template: String): String? =
        when {
            template.isBlank() -> "The prompt is empty."
            !template.contains(PASSAGES_TOKEN) ->
                "The prompt must contain $PASSAGES_TOKEN, or the model never sees the passages."
            else -> null
        }
}
