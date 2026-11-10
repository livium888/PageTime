package com.pagetime.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Text another app shared into PageTime, on its way to where it belongs.
 *
 * Shared text is one of two things and only the reader knows which: something
 * to read (an article, notes) or the reply to a prompt they just took to their
 * AI app. So it waits here as [arrived] until they say. If they call it a
 * reply it moves to [reply], and whichever "ask your AI" dialog is open takes
 * it from there, exactly as if they had copied it and tapped paste.
 *
 * [waiting] is how the question is kept short: with no dialog open there is
 * nothing a reply could be for, so the only offer is to add the text as a book.
 *
 * Plain state with no Android in it, so the hand-over can be tested.
 */
class SharedTextInbox {

    private val _arrived = MutableStateFlow<String?>(null)
    /** Shared text the reader has not yet said what to do with. */
    val arrived: StateFlow<String?> = _arrived.asStateFlow()

    private val _reply = MutableStateFlow<String?>(null)
    /** Text the reader said is their AI app's reply, not yet taken by a dialog. */
    val reply: StateFlow<String?> = _reply.asStateFlow()

    private val _waiting = MutableStateFlow(0)
    /** How many dialogs are open and waiting for a reply. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    /** Text arrived from another app. Blank text is nothing to ask about. */
    fun offer(text: String) {
        if (text.isBlank()) return
        _arrived.value = text
    }

    /** The reader dismissed the arrival, or it has been handed on. */
    fun clearArrived() {
        _arrived.value = null
    }

    /** The reader said the arrived text is their AI app's reply. */
    fun useArrivedAsReply() {
        val text = _arrived.value ?: return
        _arrived.value = null
        _reply.value = text
    }

    /** Takes the reply, so exactly one dialog acts on it. Null when there is none. */
    fun takeReply(): String? {
        while (true) {
            val current = _reply.value ?: return null
            if (_reply.compareAndSet(current, null)) return current
        }
    }

    /** A dialog opened and is waiting for a reply. Pair with [stopWaiting]. */
    fun startWaiting() {
        _waiting.value = _waiting.value + 1
    }

    fun stopWaiting() {
        _waiting.value = (_waiting.value - 1).coerceAtLeast(0)
        // A reply nobody is left to take would otherwise be delivered to the
        // next, unrelated dialog the reader opens.
        if (_waiting.value == 0) _reply.value = null
    }
}
