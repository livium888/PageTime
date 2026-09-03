package com.pagetime.app.data.learning

/**
 * Once built multiple-choice review cards on-device, without a network
 * dependency. It builds nothing now: the app moved to the Explain Back chat
 * method and MCQ cards went with it.
 *
 * The sentence scoring, distractor picking and answer blanking that used to
 * produce the cards were removed rather than left behind, because a hundred
 * lines that read as live code and run never are worse than no code at all —
 * they cost real time to anyone reading this file during a hunt.
 */
object LocalRecallCardGenerator {

    /**
     * Always empty. The contract is pinned by LocalRecallCardGeneratorTest, so
     * bringing MCQ cards back is a deliberate act rather than an accident.
     */
    fun generate(context: LearningContext, limit: Int = 3): List<GeneratedLearningCard> = emptyList()
}
