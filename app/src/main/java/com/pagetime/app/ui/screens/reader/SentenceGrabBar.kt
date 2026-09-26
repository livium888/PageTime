package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What can be done with a grabbed sentence.
 *
 * The two step arrows are deliberately not here. They belong against the words
 * they move — an arrow in a toolbar moves a boundary the reader cannot see —
 * so the page keeps the arrows and this keeps the verbs.
 *
 * Inverted against the reader's palette, like the step arrows and the top bar:
 * the page's text colour is the surface, the page's background is the ink, so
 * one bar is legible over paper, sepia, light, dark and night.
 *
 * Scrollable rather than wrapping. A large system font scale or a narrow phone
 * can push four labels past the width, and a wrapped row inside a floating bar
 * changes height mid-grab; a scroll keeps the bar one line whatever the size.
 */
@Composable
fun SentenceGrabBar(
    canUndo: Boolean,
    palette: ReaderPalette,
    onSave: () -> Unit,
    onParagraph: () -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = palette.text,
        contentColor = palette.background,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            GrabAction(label = "Cancel", onClick = onCancel)
            GrabAction(label = "Paragraph", onClick = onParagraph)
            // Only offered once a step has been taken: there is nothing to undo
            // on a fresh grab, and a dead button reads as a broken one.
            if (canUndo) {
                GrabAction(label = "Undo", onClick = onUndo)
            }
            GrabAction(label = "Save", onClick = onSave, emphasised = true)
        }
    }
}

@Composable
private fun GrabAction(
    label: String,
    onClick: () -> Unit,
    emphasised: Boolean = false
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(
            text = label,
            fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
