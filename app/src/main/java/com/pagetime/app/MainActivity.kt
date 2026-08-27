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
import com.pagetime.app.ui.PageTimeAppUi
import com.pagetime.app.ui.theme.PageTimeTheme

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
                    PageTimeAppUi(openReader = openReader)
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
