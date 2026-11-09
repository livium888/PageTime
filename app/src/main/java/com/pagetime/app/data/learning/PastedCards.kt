package com.pagetime.app.data.learning

import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the cards a chat model sends back when the reader pastes its reply.
 *
 * WHY THIS IS A TOLERANT SCANNER AND NOT A JSON PARSE
 *
 * The reply was typed by a chat model into a chat window and copied off a
 * phone. It will arrive wrapped in a markdown fence, with a sentence of
 * chatter before or after it, sometimes without the outer object, sometimes
 * cut off halfway through the last card because the model ran out of room.
 * A strict parse turns every one of those into "invalid JSON" and costs the
 * reader the whole batch, which is the friction this flow exists to remove.
 *
 * So this finds every balanced `{...}` object in the text, whatever surrounds
 * it, and keeps the ones that look like a card. Each is judged on its own: a
 * malformed card is reported and the others still import. A reply cut off
 * mid-card yields every complete card before the cut.
 *
 * WHAT IT DOES NOT DO
 *
 * It does not decide whether a card is any good. That is [ChapterPromptRules],
 * the same checks every card passes whoever wrote it, run on whatever this
 * returns.
 */
object PastedCards {

    data class Result(
        /** Cards found, in the order they appeared. May still fail the card rules. */
        val prompts: List<RawPrompt>,
        /** Objects that were found but could not be read as a card, one line each. */
        val problems: List<String>,
        /** False when the text contained no braces at all, so the reader pasted the wrong thing. */
        val sawJson: Boolean,
    )

    fun parse(reply: String): Result {
        val text = reply.removePrefix("﻿").replace("\r\n", "\n")
        val region = fencedRegions(text) ?: text
        val objects = balancedObjects(region)
        if (objects.isEmpty()) return Result(emptyList(), emptyList(), sawJson = region.contains('{'))

        val prompts = mutableListOf<RawPrompt>()
        val problems = mutableListOf<String>()
        var cardLike = 0
        for (found in objects) {
            val raw = found.text
            val obj = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                // An object that fails and has other objects inside it is the
                // wrapper ({"prompts": [...]}) failing because ONE of its cards
                // is damaged. That card is reported on its own below; reporting
                // the wrapper as well would count one mistake twice.
                val isWrapper = objects.any { it !== found && it.start > found.start && it.end <= found.end }
                if (!isWrapper &&
                    (raw.contains("prompt", ignoreCase = true) || raw.contains("question", ignoreCase = true))
                ) {
                    cardLike++
                    problems += "A card could not be read: ${raw.take(60).replace('\n', ' ')}…"
                }
                continue
            }
            val fields = fieldsOf(obj)
            // The wrapper ({"prompts": [...]}) and any other object that is not
            // a card carry no question; their cards are found separately.
            val question = fields.pick("prompt", "question", "front", "q") ?: continue
            cardLike++
            val answer = fields.pick("answer", "back", "a").orEmpty()
            val explanation = fields.pick("explanation", "why", "because", "note").orEmpty()
            val quote = fields.pick("sourcequote", "quote", "source", "evidence").orEmpty()
            val declared = fields.pick("type", "cardtype", "kind").orEmpty().lowercase()
            val isCloze = declared == "cloze" || CLOZE_MARKER.containsMatchIn(question)
            prompts += RawPrompt(
                // One passage is sent, so there is only ever one it can cite.
                // Whatever the model wrote here, even a 1-based count, is moot.
                passageIndex = 0,
                prompt = question,
                answer = answer,
                explanation = explanation,
                sourceQuote = quote,
                type = if (isCloze) RawPrompt.TYPE_CLOZE else RawPrompt.TYPE_QA,
            )
        }
        if (cardLike == 0) {
            problems += "The reply had structure but no cards in it."
        }
        return Result(prompts, problems, sawJson = true)
    }

    private val CLOZE_MARKER = Regex("""\{\{c\d+::""")

    /** A balanced object and where it sat, so one can be told to contain another. */
    internal data class Found(val start: Int, val end: Int, val text: String)

    /** Text inside ``` fences, joined, or null when the reply has none. */
    private fun fencedRegions(text: String): String? {
        val fence = Regex("```[a-zA-Z0-9_-]*\\s*\\n?(.*?)(?:```|$)", RegexOption.DOT_MATCHES_ALL)
        val parts = fence.findAll(text).map { it.groupValues[1] }.filter { it.contains('{') }.toList()
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * Every balanced `{...}` object at any depth, as substrings.
     *
     * String-aware: a brace or a quote inside a JSON string does not count.
     * An object that never closes — the reply was cut off — is simply not
     * returned, and the complete objects before it are.
     */
    internal fun balancedObjects(text: String): List<Found> {
        val found = mutableListOf<Found>()
        val starts = ArrayDeque<Int>()
        var inString = false
        var escaped = false
        var begun = false
        for ((i, c) in text.withIndex()) {
            if (!begun) {
                // Chatter before the JSON may contain quotes; start counting at
                // the first brace so they cannot flip the string state.
                if (c != '{') continue
                begun = true
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> starts.addLast(i)
                '}' -> if (starts.isNotEmpty()) {
                    val start = starts.removeLast()
                    found += Found(start, i, text.substring(start, i + 1))
                }
            }
        }
        // Inner objects close before the outer one, so reorder by position to
        // keep the cards in the order the reader saw them.
        return found.sortedBy { it.start }
    }

    /** Keys compared without case, spaces, underscores or hyphens. */
    private fun fieldsOf(obj: JSONObject): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            if (value is String) {
                out[key.lowercase().filter { it.isLetterOrDigit() }] = value.trim()
            }
        }
        return out
    }

    private fun Map<String, String>.pick(vararg names: String): String? =
        names.firstNotNullOfOrNull { this[it]?.takeIf { v -> v.isNotBlank() } }
}
