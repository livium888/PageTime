package com.pagetime.app.data.review

/**
 * Whether the reader must answer more before the book opens.
 *
 * WHY A GATE AT ALL
 *
 * FSRS scheduled cards were built to be answered when due, and nothing about
 * that changes what happens when the reader would rather skip straight to the
 * next chapter — a due card that is never seen is a card the scheduler
 * believed would be remembered, and wasn't checked. Making the queue visible
 * (the Review screen, the due count) was necessary and not sufficient: a
 * count in a corner is easy competition for a book the reader already opened
 * PageTime to read.
 *
 * WHY BATCHES OF FIVE, NOT "CLEAR EVERYTHING" OR "ONE CARD"
 *
 * One card is not a commitment, it is a formality — answer it on reflex and
 * the book opens exactly as fast as with no gate at all. Clearing the whole
 * backlog before any reading is the opposite failure: a reader back from a
 * week away can face fifty cards, and "read this evening" now means "clear
 * fifty cards first", which is the same trap [ReviewSession.MAX_SESSION]
 * exists to avoid on the review side. Five is a real but short sitting, and
 * asking again after every five keeps the choice — more cards, or read now —
 * in front of the reader rather than deciding it for them once at the start.
 *
 * WHAT THIS DOES NOT DECIDE
 *
 * Nothing here reads a database or shows a screen. It answers one question —
 * given how many have been answered since the last time this was asked, and
 * how many are still due, should the reader be stopped and asked again — so
 * the rule can be tested without a ViewModel, a Room query, or a phone.
 */
object ReadingGate {

    /** How many cards make one mandatory sitting before the choice comes back. */
    const val BATCH_SIZE = 5

    /**
     * Whether to stop and offer "more, or read" right now.
     *
     * Never true with nothing left due — a batch that finished by exhausting
     * the queue has nothing to offer more OF, and the empty queue is its own
     * answer. That is what lets clearing everything and being GIVEN the
     * choice both count as legitimate ways through the gate.
     */
    fun shouldPauseForChoice(answeredSinceLastPause: Int, remainingDue: Int): Boolean =
        remainingDue > 0 && answeredSinceLastPause >= BATCH_SIZE
}
