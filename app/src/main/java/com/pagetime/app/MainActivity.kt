package com.pagetime.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
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

    private val openReaderState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openReaderState.value = intent.getBooleanExtra(EXTRA_OPEN_READER, false)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openReaderState.value = intent.getBooleanExtra(EXTRA_OPEN_READER, false)
    }
}
