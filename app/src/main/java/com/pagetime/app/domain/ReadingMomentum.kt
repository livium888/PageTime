package com.pagetime.app.domain

/**
 * Decides *when* a reading sitting earns a surprise bonus on top of its
 * steady per-second credit — never *whether* it's deserved, which is
 * [com.pagetime.app.domain.BalanceManager.earnReadingMomentumBonus]'s job.
 *
 * The interval is drawn at random from a range, not fixed, on purpose: a
 * bonus every exactly N minutes is a countdown a reader can watch and wait
 * out — predictable, and nothing like what makes it compelling. An
 * unpredictable one is the variable-ratio schedule behind why a slot machine
 * (and, closer to home, a social feed's refresh) is so much stickier than a
 * fixed payout of the same average size: the anticipation itself is what
 * fires, not just the payoff (Schultz et al. on dopamine prediction-error
 * signaling; Skinner's original variable-ratio reinforcement work). Applied
 * here to time a reader is already earning honestly by reading, it borrows
 * the mechanism without borrowing anything dishonest — no countdown is ever
 * shown, and nothing is owed sooner than a genuine stretch of reading.
 */
object ReadingMomentum {
    private const val MIN_INTERVAL_SECONDS = 180L
    private const val MAX_INTERVAL_SECONDS = 420L

    /** A fresh, unpredictable gap to the next bonus — call again once one fires. */
    fun nextThresholdSeconds(random: () -> Double = Math::random): Long {
        val span = MAX_INTERVAL_SECONDS - MIN_INTERVAL_SECONDS
        return MIN_INTERVAL_SECONDS + (random().coerceIn(0.0, 1.0) * span).toLong()
    }

    /** Whether enough credited reading has piled up since the last bonus to pay another. */
    fun shouldFire(creditedSecondsSinceLastBonus: Long, threshold: Long): Boolean =
        creditedSecondsSinceLastBonus >= threshold
}
