package com.pagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextInboxTest {

    @Test
    fun `shared text waits until the reader says what it is`() {
        val inbox = SharedTextInbox()
        inbox.offer("An article.")
        assertEquals("An article.", inbox.arrived.value)
        assertNull(inbox.reply.value)
    }

    @Test
    fun `blank text is not an arrival`() {
        val inbox = SharedTextInbox()
        inbox.offer("   ")
        assertNull(inbox.arrived.value)
    }

    @Test
    fun `text called a reply moves to the waiting dialog and is taken once`() {
        val inbox = SharedTextInbox()
        inbox.startWaiting()
        inbox.offer("{\"cards\": []}")
        inbox.useArrivedAsReply()
        assertNull(inbox.arrived.value)
        assertEquals("{\"cards\": []}", inbox.takeReply())
        assertNull(inbox.takeReply())
    }

    @Test
    fun `dismissing an arrival leaves no reply behind`() {
        val inbox = SharedTextInbox()
        inbox.offer("text")
        inbox.clearArrived()
        assertNull(inbox.arrived.value)
        assertNull(inbox.takeReply())
    }

    @Test
    fun `a reply nobody is waiting for is not kept for the next dialog`() {
        val inbox = SharedTextInbox()
        inbox.startWaiting()
        inbox.offer("a reply")
        inbox.useArrivedAsReply()
        inbox.stopWaiting()
        inbox.startWaiting()
        assertNull(inbox.takeReply())
    }

    @Test
    fun `the waiting count follows dialogs opening and closing and never goes below zero`() {
        val inbox = SharedTextInbox()
        inbox.startWaiting()
        inbox.startWaiting()
        assertEquals(2, inbox.waiting.value)
        inbox.stopWaiting()
        inbox.stopWaiting()
        inbox.stopWaiting()
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun `a newer share replaces an unanswered older one`() {
        val inbox = SharedTextInbox()
        inbox.offer("first")
        inbox.offer("second")
        assertEquals("second", inbox.arrived.value)
    }
}
