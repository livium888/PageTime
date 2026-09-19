package com.pagetime.app.anki

import android.content.pm.PackageManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.pagetime.app.PageTimeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ReviewState {
    object Loading : ReviewState()
    object PermissionNeeded : ReviewState()
    data class Error(val message: String) : ReviewState()
    object NoneDue : ReviewState()

    /** Every due card needs an image or sound file — see [AnkiReviewer.NextCardResult.OnlyUnsupportedMediaDue]. */
    object OnlyMediaDue : ReviewState()
    data class ShowingQuestion(val card: AnkiReviewer.Card, val startedAt: Long) : ReviewState()
    data class ShowingAnswer(val card: AnkiReviewer.Card, val startedAt: Long) : ReviewState()
}

/**
 * Reviews the reader's real Anki deck from inside PageTime, crediting the
 * same reading-time reward as a PageTime flashcard for every card answered
 * Hard, Good or Easy — Again earns nothing, matching how PageTime's own
 * flashcards already work. See [AnkiReviewer] for why this exists and how
 * it talks to AnkiDroid.
 *
 * Deliberately separate from the flashcard gate that can block opening a
 * book ([com.pagetime.app.ui.screens.reader.ReaderEntryGate]): an Anki card
 * failing to render or behave correctly should never stand between the
 * reader and their book, so this is purely an extra way to earn time, never
 * a requirement.
 */
@Composable
fun AnkiReviewDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val balanceManager = (context.applicationContext as PageTimeApp).container.balanceManager
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ReviewState>(ReviewState.Loading) }
    // Diagnostic only, for tracking down why a custom card's script doesn't
    // render — this WebView has no AnkiDroid JS bridge and no base URL, so a
    // template's script erroring or a relative resource 404ing are both real
    // possibilities. Cleared on every new card.
    val jsMessages = remember { mutableStateListOf<String>() }

    suspend fun loadNext() {
        state = ReviewState.Loading
        jsMessages.clear()
        state = try {
            when (val result = withContext(Dispatchers.IO) { AnkiReviewer.nextCard(context) }) {
                is AnkiReviewer.NextCardResult.Found ->
                    ReviewState.ShowingQuestion(result.card, System.currentTimeMillis())
                AnkiReviewer.NextCardResult.NoneDue -> ReviewState.NoneDue
                AnkiReviewer.NextCardResult.OnlyUnsupportedMediaDue -> ReviewState.OnlyMediaDue
            }
        } catch (e: SecurityException) {
            ReviewState.PermissionNeeded
        } catch (e: Exception) {
            ReviewState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { loadNext() } else state = ReviewState.PermissionNeeded
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, AnkiReviewer.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) loadNext() else state = ReviewState.PermissionNeeded
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Anki", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(12.dp))

                when (val current = state) {
                    is ReviewState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is ReviewState.PermissionNeeded -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "PageTime needs your permission to read AnkiDroid's due cards. " +
                                    "Android will show its own permission dialog — approve it, then " +
                                    "come back here."
                            )
                            Button(onClick = { permissionLauncher.launch(AnkiReviewer.PERMISSION) }) {
                                Text("Grant access")
                            }
                        }
                    }
                    is ReviewState.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Something went wrong: ${current.message}")
                            Text(
                                "If this says AnkiDroid's storage isn't configured, open AnkiDroid " +
                                    "itself once to finish its first-run setup, then try again.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(onClick = { scope.launch { loadNext() } }) { Text("Retry") }
                        }
                    }
                    is ReviewState.NoneDue -> {
                        Text("No Anki cards due right now.")
                    }
                    is ReviewState.OnlyMediaDue -> {
                        Text(
                            "The Anki cards due right now use images or sound. AnkiDroid " +
                                "doesn't let other apps read those back, so they can't display " +
                                "here — open AnkiDroid to review them. Anything without media " +
                                "will still show up here."
                        )
                    }
                    is ReviewState.ShowingQuestion -> {
                        current.card.cardName?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium)
                        }
                        AnkiCardWebView(
                            html = current.card.question,
                            modifier = Modifier.weight(1f),
                            onJsMessage = { jsMessages.add(it) },
                        )
                        JsDiagnostics(jsMessages)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                jsMessages.clear()
                                state = ReviewState.ShowingAnswer(current.card, current.startedAt)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Show answer")
                        }
                    }
                    is ReviewState.ShowingAnswer -> {
                        current.card.cardName?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium)
                        }
                        AnkiCardWebView(
                            html = current.card.answer,
                            modifier = Modifier.weight(1f),
                            onJsMessage = { jsMessages.add(it) },
                        )
                        JsDiagnostics(jsMessages)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "Again", 2 to "Hard", 3 to "Good", 4 to "Easy").forEach { (ease, label) ->
                                Button(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                                    onClick = {
                                        val elapsed = System.currentTimeMillis() - current.startedAt
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AnkiReviewer.answer(context, current.card, ease, elapsed)
                                            }
                                            // Again earns nothing, matching PageTime's own flashcards.
                                            if (ease != 1) {
                                                runCatching { balanceManager.earnFromFlashcard(ratingCorrect = true) }
                                            }
                                            loadNext()
                                        }
                                    }
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loads AnkiDroid's own rendered HTML for a card — the reader's real note
 * type, template and CSS. JavaScript is enabled so cards that use it behave
 * as closely as possible to AnkiDroid's own reviewer, though no JS bridge is
 * stubbed in: a card built around AnkiDroid-specific JS APIs may still
 * behave differently here. [onJsMessage] surfaces script console output and
 * failed resource loads (both likely, given there's no base URL for a
 * relative script/fetch to resolve against, and no AnkiDroid JS API object)
 * so a broken custom template can be diagnosed instead of guessed at.
 *
 * Confirmed on-device: a null base URL gives the page an opaque origin, and
 * an opaque origin can't touch sessionStorage/localStorage at all — any
 * script that reads either on load throws an uncaught SecurityError and
 * stops right there, taking every later DOM update (revealing the question,
 * filling in the answer) down with it. [BASE_URL] is a fake but real origin
 * (an RFC 2606 .invalid host, so it can never resolve to an actual site) so
 * storage access works like it would in AnkiDroid's own reviewer.
 */
private const val BASE_URL = "https://pagetime-anki-card.invalid/"

@Composable
private fun AnkiCardWebView(html: String, modifier: Modifier = Modifier, onJsMessage: (String) -> Unit = {}) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        onJsMessage("console.${message.messageLevel()}: ${message.message()} (line ${message.lineNumber()})")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) return
                        onJsMessage("failed to load ${request.url}: ${error.description}")
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
        }
    )
}

/** Diagnostic panel for [AnkiCardWebView]'s captured console/resource errors — see its doc for why this exists. */
@Composable
private fun JsDiagnostics(messages: List<String>) {
    if (messages.isEmpty()) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Script diagnostics (${messages.size}):",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        messages.takeLast(6).forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
