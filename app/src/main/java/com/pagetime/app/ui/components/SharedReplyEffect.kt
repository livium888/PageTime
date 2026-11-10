package com.pagetime.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagetime.app.PageTimeApp

/**
 * Lets a dialog that is waiting for an AI reply receive one shared into the
 * app, not only one pasted from the clipboard.
 *
 * While this is in the composition the app knows a reply is expected, so text
 * arriving from the share sheet is offered as "the AI's reply". When the
 * reader says it is, [onReply] gets it exactly as a paste would have. See
 * [com.pagetime.app.data.SharedTextInbox].
 */
@Composable
fun SharedReplyEffect(onReply: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as? PageTimeApp ?: return
    val inbox = app.container.sharedTextInbox
    val deliver by rememberUpdatedState(onReply)
    DisposableEffect(inbox) {
        inbox.startWaiting()
        onDispose { inbox.stopWaiting() }
    }
    val reply by inbox.reply.collectAsStateWithLifecycle()
    LaunchedEffect(reply) {
        if (reply != null) inbox.takeReply()?.let { deliver(it) }
    }
}
