package com.pagetime.app.data.learning

/**
 * The way questions are asked, drawn from two people who wrote about how they
 * learned, and put into every prompt the reader sends to their own AI.
 *
 * FEYNMAN: knowing the name of a thing is not knowing the thing. His father's
 * bird-watching walk is the usual telling of it; the "Feynman technique" (explain
 * it in plain words, find where you stumble, go back and simplify) is a popular
 * modern summary, not his own formulation. So questions ask why and how, what
 * would change if one thing were different, and for an example the reader has
 * not been given.
 *
 * FRANKLIN: in his Autobiography he learned to write by jotting a hint of each
 * sentiment in an essay, setting them aside for days, rewriting the essay in his
 * own words, and comparing it with the original to find his faults. And the
 * Junto's standing queries asked, of what a member had last read, what was
 * remarkable in it and worth passing on. So the reader is asked to put things in
 * their own words from memory, and what they would do with an idea or pass on.
 *
 * Wording only: the replies are read exactly as before.
 */
object LearnerStyle {

    /** Added to the card-writing prompt, before the reply format, which stays last. */
    val CARDS: String =
        """
            STYLE OF THE QUESTIONS

            Ask the way Richard Feynman tested understanding, and the way
            Benjamin Franklin studied.

            - Prefer "why" and "how does it work" questions, and "what would
              change if...", over questions that only ask for a name, a date or
              a definition. Knowing the name of a thing is not knowing the thing.
            - Where a term really matters, ask the reader to say what it means in
              plain words, as to a bright twelve-year-old, with the term itself
              left out of the answer.
            - Make the reader put an idea in their own words from memory, not
              pick it out of the text.
            - At most one card may ask how the idea could be used, or what is
              worth passing on to someone else.
        """.trimIndent()

    /** Added to the Socratic "talk it through" prompt. */
    val SOCRATIC: String =
        "Ask in the way Richard Feynman tested understanding and Benjamin Franklin studied. " +
            "Feynman: ask me to explain the idea in plain words, as to a bright twelve-year-old and without the " +
            "technical term; where I stumble, ask what the term means without using it; ask for an example I have " +
            "not been given, and what would change or break if one part were different. A name is not " +
            "understanding. Franklin: make me put the idea in my own words from memory before you show it to me " +
            "again, and finish with one question about what I could do with it, or what in it is worth passing on to " +
            "someone else."

    /**
     * The Socratic conversation held as a roundtable, in the spirit of Franklin's
     * Junto: a club of curious friends who met to question each other about what
     * they had read and how it might serve others. The reader is a member.
     *
     * Franklin's own rules for it, as the Autobiography tells them, were to
     * avoid direct contradiction and flat assertion and to question instead,
     * from a Socratic habit he took from Xenophon. People no longer living only,
     * speaking in their known manner: a model invents quotations, so it is told not to.
     */
    fun roundtable(guests: String): String {
        val invited = guests.trim()
        val panel = if (invited.isNotEmpty()) {
            "The guests are: $invited. Keep them to people no longer living, and if one is a living person, " +
                "replace them with someone who is not."
        } else {
            "Choose two other guests, people no longer living whose way of thinking would best light up this " +
                "passage, and say who they are in your first message."
        }
        return "Run this as a roundtable in the spirit of Benjamin Franklin's Junto, a club of curious friends " +
            "who met to question each other about what they had read and how it might serve others. Franklin " +
            "chairs. $panel I am a member of the club: the others speak to me.\n" +
            "Each speaks in the manner they were known for, not in invented words: do not put quotations in " +
            "anyone's mouth. Franklin opens by asking what in the passage is remarkable and worth passing on, " +
            "and closes by asking how it might be useful to others. Socrates-style members ask humble questions; " +
            "Feynman-style members ask for plain words, an example not given, and what would change if a part " +
            "were different; a sceptic asks what would show the idea wrong. Franklin's rule: no flat " +
            "contradiction and no pronouncements, question instead.\n" +
            "Only ONE member speaks at a time, under 60 words, name them at the start of the line, and every " +
            "turn ends with a question to me. They may react briefly to each other but always come back to me."
    }

    /** Added to the explain-back marking prompt. */
    val MARKING: String =
        "Mark it in Richard Feynman's spirit: note any technical term used but not explained in plain words, " +
            "and any place the reader names something without showing they understand it. In Benjamin Franklin's " +
            "spirit, compare their version with the source and point to where they differ."
}
