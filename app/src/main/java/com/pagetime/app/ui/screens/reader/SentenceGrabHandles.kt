package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Big enough to hit with a thumb without covering the words it points at. */
private val GrabHandleSize = 34.dp

/**
 * The two sentence steps, pinned to the ends of the span being grabbed.
 *
 * A phone cannot drag a text selection onto a sentence boundary — that is the
 * whole reason this exists — so the span is built by stepping instead: one
 * arrow at the start of the sentence, one at the end, each pulling in exactly
 * one more sentence. Placed against the text rather than in a toolbar, so the
 * arrow and the boundary it moves are in the same glance.
 *
 * [span] is page-relative, matching [layout], because these are drawn inside
 * the page's own content box — the same coordinate space the text was laid out
 * in. A span that runs over a page turn is clipped to this page by the caller,
 * so the start arrow appears on the page the sentence starts on and the end
 * arrow on the page it ends on.
 */
@Composable
fun SentenceGrabHandles(
    span: Pair<Int, Int>?,
    layout: TextLayoutResult?,
    palette: ReaderPalette,
    onExtendBackward: () -> Unit,
    onExtendForward: () -> Unit
) {
    if (span == null || layout == null) return

    val textLength = layout.layoutInput.text.length
    if (textLength <= 0) return

    // getBoundingBox throws on an offset outside the text, and a span clipped
    // to this page can sit one character past the end when the page text is
    // trimmed at layout time.
    val firstBox = runCatching { layout.getBoundingBox(span.first.coerceIn(0, textLength - 1)) }
        .getOrNull() ?: return
    val lastBox = runCatching { layout.getBoundingBox((span.second - 1).coerceIn(0, textLength - 1)) }
        .getOrNull() ?: return

    val sizePx = with(LocalDensity.current) { GrabHandleSize.roundToPx() }
    val maxX = (layout.size.width - sizePx).coerceAtLeast(0)
    val maxY = (layout.size.height - sizePx).coerceAtLeast(0)

    // Above the first character, below the last, and never off the page: a
    // handle that cannot be reached is a sentence that cannot be extended.
    GrabHandle(
        x = firstBox.left.roundToInt().coerceIn(0, maxX),
        y = (firstBox.top.roundToInt() - sizePx).coerceIn(0, maxY),
        icon = Icons.Filled.KeyboardArrowUp,
        description = "Extend the highlight one sentence back",
        palette = palette,
        onClick = onExtendBackward
    )
    GrabHandle(
        x = (lastBox.right.roundToInt() - sizePx).coerceIn(0, maxX),
        y = lastBox.bottom.roundToInt().coerceIn(0, maxY),
        icon = Icons.Filled.KeyboardArrowDown,
        description = "Extend the highlight one sentence on",
        palette = palette,
        onClick = onExtendForward
    )
}

/**
 * One step arrow.
 *
 * Inverted against the palette — the page's text colour as the disc, the page's
 * background as the glyph — so it stays legible on paper, sepia, light, dark
 * and night without a colour of its own. It is the same trick the reader's top
 * bar uses to stay readable over any page.
 */
@Composable
private fun GrabHandle(
    x: Int,
    y: Int,
    icon: ImageVector,
    description: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .size(GrabHandleSize)
            .clip(CircleShape)
            .background(palette.text)
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = palette.background,
            modifier = Modifier.size(GrabHandleSize)
        )
    }
}
