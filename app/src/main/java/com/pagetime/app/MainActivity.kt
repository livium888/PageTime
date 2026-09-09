package com.pagetime.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.pagetime.app.data.youtube.YouTubeTranscriptFetcher
import com.pagetime.app.data.review.ReviewReminderWorker
import com.pagetime.app.ui.PageTimeAppUi
import com.pagetime.app.ui.theme.PageTimeTheme
import com.pagetime.app.ui.screens.reader.VolumeKeyPaging
import com.pagetime.app.ui.screens.reader.ReaderPageTurns
import android.view.KeyEvent

/**
 * FragmentActivity (not plain ComponentActivity) because the Readium EPUB navigator
 * is a Fragment hosted in this activity's supportFragmentManager.
 */
class MainActivity : FragmentActivity() {

    companion object {
        const val EXTRA_OPEN_READER = "open_reader"
    }

    private val importViewModel: BookImportViewModel by viewModels()
    private val openReaderState = mutableStateOf(false)

    /**
     * Set when the launch came from a review reminder.
     *
     * Consumed once by the UI. A notification that opens the library and
     * leaves the reader to find the review themselves has wasted the
     * interruption it just spent.
     */
    private val openReviewState = mutableStateOf(false)

    /**
     * Volume keys turn pages while the reader is open and the reader asked for
     * it.
     *
     * Intercepted here rather than in a Composable because a key event never
     * reaches the composition — it arrives at the Activity, and the thing that
     * knows how to turn a page is several layers down and different for each
     * format. The reader publishes a handler while it is on screen; see
     * ReaderPageTurns.
     *
     * BOTH HALVES OF THE PRESS ARE SWALLOWED. Android raises the volume panel
     * on key-UP, so handling only the down stroke turns the page and then
     * slides the volume UI over the text.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val enabled = ReaderPageTurns.enabled && ReaderPageTurns.active
        if (event.action == KeyEvent.ACTION_DOWN) {
            val turn = VolumeKeyPaging.turnFor(
                keyCode = event.keyCode,
                enabled = enabled,
                readerVisible = ReaderPageTurns.active,
                repeatCount = event.repeatCount,
            )
            if (turn != null && ReaderPageTurns.turn(turn)) return true
        }
        if (VolumeKeyPaging.consumesWithoutTurning(
                keyCode = event.keyCode,
                enabled = enabled,
                readerVisible = ReaderPageTurns.active,
            )
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            PageTimeTheme {
                val openReader by openReaderState
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val openReview by openReviewState
                    PageTimeAppUi(
                        openReader = openReader,
                        openReview = openReview,
                        onReviewOpened = { openReviewState.value = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as? PageTimeApp)?.container
        // PageTime itself is in front, so there is nothing to enforce. The blocker
        // deliberately does not treat our own package as a foreground change (the
        // time-up overlay's own focus event must not dismiss the overlay), so the
        // block state has to be released here instead — otherwise re-reading the
        // blocked-app set while the user sits in Settings would raise the block
        // screen over our own UI.
        container?.blockController?.releaseBlock()
        container?.usageReconciler?.requestReconcile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        openReaderState.value = intent?.getBooleanExtra(EXTRA_OPEN_READER, false) ?: false
        if (intent?.action == ReviewReminderWorker.ACTION_OPEN_REVIEW) {
            openReviewState.value = true
        }
        // A book handed over from outside the app: "Open with PageTime" from a
        // file manager/browser (ACTION_VIEW) or a share-sheet file (ACTION_SEND
        // carrying a content stream). Raw shared text without a stream is ignored.
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            importViewModel.onIncomingUri(uri)
            return
        }
        // YouTube share: ACTION_SEND with EXTRA_TEXT containing a YouTube URL.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val fetcher = YouTubeTranscriptFetcher()
            if (fetcher.isYouTubeUrl(text)) {
                importViewModel.onYouTubeUrl(text)
            }
        }
    }
}
