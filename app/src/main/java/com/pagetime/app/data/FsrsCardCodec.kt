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
        // FSRS uses null stability + null difficulty to identify a never-reviewed
        // card. Do not replace those nulls with zero: that sends NEW cards through
        // the reviewed-card branch and causes getStability() failures.
        .put("stability", card.stability)
        .put("difficulty", card.difficulty)
        .put("due", card.due?.toEpochMilli())
        .put("lastReview", card.lastReview?.toEpochMilli())
        .toString()

    fun fromJson(json: String): Card {
        val obj = JSONObject(json)
        val storedState = readState(obj)
        val storedStability = readNullableDouble(obj, "stability")
        val storedDifficulty = readNullableDouble(obj, "difficulty")

        // A NEW FSRS card is represented by BOTH values being null. Older builds
        // incorrectly persisted 0.0 for these fields; normalize those cards back
        // to the true NEW state before Scheduler.reviewCard() sees them. A partially
        // populated card is unsafe too, so reset it rather than letting the scheduler
        // dereference a missing stability/difficulty value.
        val isValidScheduledCard = storedStability != null &&
            storedDifficulty != null &&
            storedStability > 0.0 &&
            storedDifficulty > 0.0
        val state = if (storedState != State.LEARNING && !isValidScheduledCard) {
            State.LEARNING
        } else {
            storedState
        }
        val builder = Card.builder()
            .state(state)
        if (obj.has("step") && !obj.isNull("step") && state == storedState) {
            builder.step(obj.getInt("step"))
        }
        if (isValidScheduledCard) {
            // The boolean check above guarantees both values are present and
            // positive; keep the local non-null values explicit for the Java API.
            val stability = storedStability ?: 0.001
            val difficulty = storedDifficulty ?: 1.0
            builder.stability(stability)
            builder.difficulty(difficulty)
        }
        if (obj.has("due") && !obj.isNull("due")) builder.due(Instant.ofEpochMilli(obj.getLong("due")))
        if (obj.has("lastReview") && !obj.isNull("lastReview") && state == storedState) {
            builder.lastReview(Instant.ofEpochMilli(obj.getLong("lastReview")))
        }
        return builder.build()
    }

    private fun readNullableDouble(obj: JSONObject, key: String): Double? =
        obj.optString(key, "")
            .trim()
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() }

    private fun readState(obj: JSONObject): State =
        runCatching { State.valueOf(obj.optString("state", State.LEARNING.name)) }
            .getOrDefault(State.LEARNING)
}
