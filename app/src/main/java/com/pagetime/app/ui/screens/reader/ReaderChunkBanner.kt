package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The reader's accent, on whichever palette the reader chose.
 *
 * The reader can be reading black-on-white or grey-on-black, and the app's
 * accent is chosen for the app's own theme, not for the page. Deriving it from
 * the page's own background is what keeps the chunk bar legible on all five
 * palettes rather than on the two the app theme happens to match.
 */
internal fun readerChunkAccent(palette: ReaderPalette): Color =
    if (palette.background.luminance() < 0.5f) Color(0xFF6FD3C0) else Color(0xFF0F766E)

/**
 * A menu row with a reason under it.
 *
 * The chunks menu was two bare verbs — "Start chunk here" / "Close chunk" —
 * which said what they did and nothing about why anyone would. The second
 * line is the point of the feature, said where the reader is looking when they
 * wonder what it is for.
 */
@Composable
internal fun ChunkMenuText(label: String, hint: String) {
    Column {
        Text(label)
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The chunk the reader is inside, and the one action that closes it.
 *
 * WHY THIS IS ON THE SCREEN AND NOT IN THE MENU
 *
 * Incremental reading used to be two taps in an overflow menu — one before
 * reading and one after — and neither of them told the reader which span of
 * text they were in. So the feature was invisible, and a reader who did not
 * already know what a pagemark was had no way to find out.
 *
 * The bar goes where the reader is already looking when they decide to stop:
 * the foot of the page, beside the page number. It names the chunk, says how
 * far into the book that chunk runs (its start and end are otherwise nowhere
 * on the screen), and carries the finish action — which is the only action
 * there is, because finishing a chunk opens the next one where this one ended.
 */
@Composable
internal fun ChunkBanner(
    title: String,
    span: String,
    palette: ReaderPalette,
    onFinish: () -> Unit,
) {
    val accent = readerChunkAccent(palette)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = palette.background,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$title \u00b7 $span",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Tap Finish when you stop \u2014 this part comes back in a few days.",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onFinish) {
                Text("Finish", color = accent)
            }
        }
    }
}
