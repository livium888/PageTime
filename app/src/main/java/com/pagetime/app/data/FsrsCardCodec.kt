package com.pagetime.app.data

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.State
import java.time.Instant
import org.json.JSONObject

/**
 * (De)serializes the FSRS [Card] scheduling state to the JSON string persisted on
 * [com.pagetime.app.data.local.LearningCardEntity.fsrsCardJson].
 *
 * The underlying `io.github.open-spaced-repetition:fsrs` library does not ship JSON
 * support, so we persist exactly the fields that determine scheduling: state, step,
 * stability, difficulty, due and lastReview. cardId is intentionally not persisted;
 * it is only used internally by the library and our app keys cards by UUID.
 */
object FsrsCardCodec {

    fun toJson(card: Card): String = JSONObject()
        .put("state", card.state.name)
        .put("step", card.step)
        .put("stability", card.stability ?: 0.0)
        .put("difficulty", card.difficulty ?: 0.0)
        .put("due", card.due?.toEpochMilli())
        .put("lastReview", card.lastReview?.toEpochMilli())
        .toString()

    fun fromJson(json: String): Card {
        val obj = JSONObject(json)
        val builder = Card.builder()
            .state(readState(obj))
        if (obj.has("step") && !obj.isNull("step")) builder.step(obj.getInt("step"))
        // Always set stability — the FSRS scheduler requires it to be non-null,
        // but NEW cards serialize as null. Default to 0.0 so reviewCard() never NPEs.
        builder.stability(obj.optDouble("stability", 0.0))
        // Same for difficulty: default to 0.0 when null/missing.
        builder.difficulty(obj.optDouble("difficulty", 0.0))
        if (obj.has("due") && !obj.isNull("due")) builder.due(Instant.ofEpochMilli(obj.getLong("due")))
        if (obj.has("lastReview") && !obj.isNull("lastReview")) {
            builder.lastReview(Instant.ofEpochMilli(obj.getLong("lastReview")))
        }
        return builder.build()
    }

    private fun readState(obj: JSONObject): State =
        runCatching { State.valueOf(obj.optString("state", State.LEARNING.name)) }
            .getOrDefault(State.LEARNING)
}
