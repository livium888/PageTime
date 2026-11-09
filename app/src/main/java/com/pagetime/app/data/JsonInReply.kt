package com.pagetime.app.data

import org.json.JSONException
import org.json.JSONObject

/**
 * Finds the JSON object inside a chat app's reply.
 *
 * A model asked for "only JSON" still wraps it in a code fence and adds a
 * sentence before or after. So the object is looked for anywhere in the reply,
 * as the first balanced `{...}` that actually parses, rather than expecting
 * the reply to be nothing else.
 */
object JsonInReply {

    fun firstObject(text: String): JSONObject? {
        var from = text.indexOf('{')
        while (from >= 0) {
            val end = matchingBrace(text, from)
            if (end > from) {
                try {
                    return JSONObject(text.substring(from, end + 1))
                } catch (_: JSONException) {
                    // Not JSON after all; try the next opening brace.
                }
            }
            from = text.indexOf('{', from + 1)
        }
        return null
    }

    /** Index of the brace closing the one at [open], or -1. Braces inside strings do not count. */
    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until text.length) {
            val c = text[i]
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
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}
