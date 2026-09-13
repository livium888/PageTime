package com.pagetime.app.ui.screens.reader

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A sentence grabbed in an EPUB.
 *
 * [text] is the whole block, kept so a step can be worked out and re-applied
 * without another round trip into the page, and so the arrows keep working when
 * the block is off screen. [key] and [head] are how the block is found again.
 */
data class EpubGrab(
    val key: String,
    val head: String,
    val text: String,
    val start: Int,
    val end: Int
)

/** The same size as the plain-text reader's arrows, for the same reason. */
private val EpubChipSize = 34.dp

/**
 * The two sentence steps for an EPUB, laid over the page the way a phone lays
 * its own selection handles there.
 *
 * The position comes from Readium — the selection's own bounding rect in view
 * coordinates — so the arrows follow the words rather than a guess about where
 * the text ended up. When there is no rect to be had (a resource that reports
 * none, a selection that has just been replaced), they fall back to sitting
 * together above the bottom of the screen rather than vanishing: a control that
 * exists but is in the wrong place is far easier to act on than one that is not
 * there.
 */
@Composable
fun EpubSentenceGrabChips(
    rect: RectF?,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sizePx = with(density) { EpubChipSize.roundToPx() }
        val maxX = (with(density) { maxWidth.roundToPx() } - sizePx).coerceAtLeast(0)
        val maxY = (with(density) { maxHeight.roundToPx() } - sizePx).coerceAtLeast(0)

        val backX: Int
        val backY: Int
        val forwardX: Int
        val forwardY: Int
        if (rect != null) {
            // Above the first character, below the last — the ends of the span,
            // not the corners of its box, so a two-line selection still reads as
            // "start here, end there".
            backX = rect.left.roundToInt().coerceIn(0, maxX)
            backY = (rect.top.roundToInt() - sizePx).coerceIn(0, maxY)
            forwardX = (rect.right.roundToInt() - sizePx).coerceIn(0, maxX)
            forwardY = rect.bottom.roundToInt().coerceIn(0, maxY)
        } else {
            val rowY = (maxY - sizePx * 2).coerceAtLeast(0)
            backX = (maxX / 2 - sizePx).coerceAtLeast(0)
            backY = rowY
            forwardX = (maxX / 2 + sizePx / 4).coerceAtMost(maxX)
            forwardY = rowY
        }

        StepChip(
            x = backX,
            y = backY,
            icon = Icons.Filled.KeyboardArrowUp,
            description = "Extend the highlight one sentence back",
            onClick = onStepBack
        )
        StepChip(
            x = forwardX,
            y = forwardY,
            icon = Icons.Filled.KeyboardArrowDown,
            description = "Extend the highlight one sentence on",
            onClick = onStepForward
        )
    }
}

/**
 * One step arrow.
 *
 * Material's inverse pair rather than the reader's palette: this sits over a
 * Readium page whose colours come from the publication's own theme, and
 * `inverseSurface`/`inverseOnSurface` is the one combination the app can be
 * sure stays legible against both a light and a dark page.
 */
@Composable
private fun StepChip(
    x: Int,
    y: Int,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .size(EpubChipSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.inverseSurface)
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(EpubChipSize)
        )
    }
}
