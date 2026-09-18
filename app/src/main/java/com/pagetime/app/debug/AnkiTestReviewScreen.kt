package com.pagetime.app.debug

import android.content.pm.PackageManager
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.pagetime.app.anki.AnkiReviewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TEMPORARY EXPERIMENTAL — see AnkiReviewer.kt for why this exists. */
private sealed class ReviewState {
    object Loading : ReviewState()
    object PermissionNeeded : ReviewState()
    data class Error(val message: String) : ReviewState()
    object NoneDue : ReviewState()
    data class ShowingQuestion(val card: AnkiReviewer.Card, val startedAt: Long) : ReviewState()
    data class ShowingAnswer(val card: AnkiReviewer.Card, val startedAt: Long) : ReviewState()
}

@Composable
fun AnkiTestReviewDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ReviewState>(ReviewState.Loading) }

    suspend fun loadNext() {
        state = ReviewState.Loading
        state = try {
            val card = withContext(Dispatchers.IO) { AnkiReviewer.nextCard(context) }
            if (card == null) {
                ReviewState.NoneDue
            } else {
                ReviewState.ShowingQuestion(card, System.currentTimeMillis())
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
                    Text("Anki test reviewer (experimental)", style = MaterialTheme.typography.titleMedium)
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
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("No cards due right now in AnkiDroid's currently-selected deck.")
                            Text(
                                "This only checks the deck AnkiDroid currently has selected, not " +
                                    "every deck — switch decks inside AnkiDroid and retry to test another.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(onClick = { scope.launch { loadNext() } }) { Text("Check again") }
                        }
                    }
                    is ReviewState.ShowingQuestion -> {
                        current.card.cardName?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium)
                        }
                        AnkiCardWebView(html = current.card.question, modifier = Modifier.weight(1f))
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { state = ReviewState.ShowingAnswer(current.card, current.startedAt) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Show answer")
                        }
                    }
                    is ReviewState.ShowingAnswer -> {
                        current.card.cardName?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium)
                        }
                        AnkiCardWebView(html = current.card.answer, modifier = Modifier.weight(1f))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "Again", 2 to "Hard", 3 to "Good", 4 to "Easy").forEach { (ease, label) ->
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val elapsed = System.currentTimeMillis() - current.startedAt
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AnkiReviewer.answer(context, current.card, ease, elapsed)
                                            }
                                            loadNext()
                                        }
                                    }
                                ) { Text(label) }
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
 * type, template and CSS, exactly as AnkiDroid's backend produced it.
 * JavaScript is enabled so cards that use it actually attempt to run,
 * since seeing what breaks without AnkiDroid's own JS bridge present is
 * the entire point of this experiment — no bridge is stubbed in here.
 */
@Composable
private fun AnkiCardWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    )
}
