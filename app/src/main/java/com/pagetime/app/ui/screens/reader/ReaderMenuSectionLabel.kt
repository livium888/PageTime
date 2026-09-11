package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A group label inside the reader's overflow menu.
 *
 * The menu carries every book-level action the reader has — navigation,
 * study, notes, chunks, highlights, display, transcript — and as a flat list
 * of two dozen rows it read as a wall. The label is the only thing that says
 * which part of the app a row belongs to.
 *
 * Purely presentational: it draws nothing clickable and changes no action's
 * behaviour. It lives beside [ReaderScreen] rather than inside it only because
 * that file is already very large.
 */
@Composable
internal fun MenuSectionLabel(text: String, first: Boolean = false) {
    if (!first) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}
