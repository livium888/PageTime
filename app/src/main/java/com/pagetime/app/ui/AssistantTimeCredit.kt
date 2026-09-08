package com.pagetime.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pagetime.app.PageTimeApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Credits time spent on an assistant screen toward the access gate.
 *
 * WHY ASSISTANT TIME COUNTS AT ALL
 *
 * The gate asks for two hours before the blocked apps open. If only page-turning
 * counted, the reader who wants to spend twenty minutes working out WHAT to read
 * is penalised for the most useful thing they could do, and the obvious response
 * is to leave the reader open on a page while doing it — which teaches the app to
 * measure the wrong thing and teaches the reader to fake it.
 *
 * So it counts, and it is capped, and the cap is what stops talking about reading
 * from becoming the whole two hours. The cap lives in [GateState]; nothing here
 * knows about it, because a screen that had to know its own credit limit would
 * have to be updated every time the limit moved.
 *
 * WHY IT IS TIED TO THE LIFECYCLE AND NOT THE COMPOSITION
 *
 * A composition survives the screen going to the background. Crediting time to a
 * reader whose phone is in their pocket is the same theft the spend ticker
 * refuses to commit in the other direction, so this runs only while the screen
 * is actually RESUMED.
 *
 * The flush goes through the container's scope rather than this coroutine,
 * because this coroutine is cancelled at the exact moment the last few seconds
 * need writing — leaving the screen is precisely when the record is lost.
 */
@Composable
fun AssistantTimeCredit(flushEverySeconds: Int = 15) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as PageTimeApp).container
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, container) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var pending = 0L
            try {
                while (true) {
                    delay(1_000)
                    pending++
                    if (pending >= flushEverySeconds) {
                        val seconds = pending
                        pending = 0
                        container.scope.launch { container.balanceManager.earnFromPlanning(seconds) }
                    }
                }
            } finally {
                if (pending > 0) {
                    val seconds = pending
                    pending = 0
                    container.scope.launch { container.balanceManager.earnFromPlanning(seconds) }
                }
            }
        }
    }
}
