package com.pagetime.app.data.learning

import org.json.JSONObject

/**
 * What one Gemini request actually cost, as the API reported it.
 *
 * MEASURED, NOT ESTIMATED
 *
 * PageTime used to record the number of CHARACTERS sent and divide by 3.5 to
 * guess at tokens, while counting no output at all. Both halves of that were
 * wrong: the divisor is a rule of thumb that varies with language and
 * formatting, and on a generation call the output is a large share of the
 * bill. Meanwhile every response has carried the exact figures all along, in a
 * usageMetadata block that was parsed by nothing.
 *
 * THINKING TOKENS ARE NOT FREE
 *
 * gemini-2.5-flash — the default here — is a thinking model. It bills its
 * reasoning as output, and on a hard prompt that reasoning can outweigh the
 * answer. A reader shown only [outputTokens] would see a number far below what
 * they were charged for, which is worse than showing nothing.
 *
 * WHY totalTokens IS NOT A SUM
 *
 * It is whatever the API said it was. Whether thoughts are already counted
 * inside candidates, and how cached tokens are netted off, are Google's
 * business and have changed between API versions. Adding the parts up here
 * would produce a number that looks authoritative and drifts from the invoice.
 */
data class GeminiTokenUsage(
    val model: String,
    /** Everything sent: instructions, passages, the lot. */
    val promptTokens: Int,
    /** The answer itself. */
    val outputTokens: Int,
    /** Reasoning on a thinking model. Billed as output. */
    val thinkingTokens: Int,
    /** Charged at a discount, when context caching is in play. */
    val cachedTokens: Int,
    /** As reported. Never recomputed from the parts above. */
    val totalTokens: Int,
) {
    val isEmpty: Boolean get() = totalTokens <= 0 && promptTokens <= 0 && outputTokens <= 0
}

/**
 * Reads the usage block off a raw Gemini response.
 *
 * Every field is optional on purpose. Which counters appear depends on the
 * model and the API version — a non-thinking model reports no thoughts, an
 * uncached request no cached tokens — and a missing counter must read as zero
 * rather than failing the parse and losing the counts that ARE there.
 */
object GeminiUsageParser {

    fun parse(rawResponse: String, model: String): GeminiTokenUsage? {
        val usage = runCatching {
            JSONObject(rawResponse).optJSONObject("usageMetadata")
        }.getOrNull() ?: return null

        val parsed = GeminiTokenUsage(
            model = model,
            promptTokens = usage.optInt("promptTokenCount", 0),
            outputTokens = usage.optInt("candidatesTokenCount", 0),
            thinkingTokens = usage.optInt("thoughtsTokenCount", 0),
            cachedTokens = usage.optInt("cachedContentTokenCount", 0),
            totalTokens = usage.optInt("totalTokenCount", 0),
        )
        // A usageMetadata block containing nothing recognisable is not usage.
        // Recording zeroes would quietly replace "we do not know" with "it was
        // free", which is the wrong direction to be wrong in.
        return if (parsed.isEmpty) null else parsed
    }
}

/**
 * Where a request reports its cost back to whoever is logging it.
 *
 * A coroutine context element rather than a callback or a field on the client.
 * Two requests can be in flight at once — a capture while a concept map
 * generates — and anything shared between them would attribute one request's
 * tokens to the other's log row. The context travels with the call that made
 * it and nothing else, which is exactly the scope wanted.
 *
 * Nothing breaks when it is absent: a caller that did not install a sink
 * simply goes unmeasured.
 */
class GeminiUsageSink : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<GeminiUsageSink>

    @Volatile
    var usage: GeminiTokenUsage? = null
}
