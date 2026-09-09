package com.pagetime.app.ui.screens.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * One book, drawn as a spine.
 *
 * WHAT MAKES A RECTANGLE READ AS A BOOK
 *
 * Roughly in order of how much each buys: the horizontal bands near the ends,
 * which real bindings nearly always have and which are two lines of code; the
 * varying widths, without which a shelf reads as a bar chart; and the light
 * edge and shadow, which are the difference between a rectangle and an object.
 *
 * A GHOST IS A BOOK YOU DO NOT HAVE
 *
 * Outlined instead of filled. It puts the availability state on the shelf
 * without a word of text — two solid books and twenty ghosts says exactly what
 * an author shelf holds, at a glance.
 *
 * NOTHING HERE IS ASSERTED BY A TEST
 *
 * The numbers behind it are checked in BookSpinesTest — stable, in range,
 * varied. Whether the result looks like a book is not a thing a test can see,
 * and this was written without ever rendering it.
 */
@Composable
fun BookSpine(
    title: String,
    author: String,
    owned: Boolean,
    progress: Float,
    shelfHeight: Dp,
    onClick: () -> Unit,
) {
    val look = remember(title, author, owned) { BookSpines.lookFor(title, author, owned) }
    val base = remember(look) { Color.hsl(look.hue, look.saturation, look.lightness) }
    val height = shelfHeight * look.heightFraction
    val bands = remember(look) { BookSpines.bandPositions(look) }
    val ghostInk = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .width(look.widthDp.dp)
            .height(height)
            .clickable(onClick = onClick)
            .drawBehind {
                val corner = CornerRadius(2.dp.toPx())
                if (look.ghost) {
                    // Barely there: present enough to count, plainly not owned.
                    drawRoundRect(color = ghostInk.copy(alpha = 0.07f), cornerRadius = corner)
                    drawRoundRect(
                        color = ghostInk.copy(alpha = 0.45f),
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                } else {
                    drawRoundRect(color = base, cornerRadius = corner)
                    // Light catches one edge and shadow gathers on the other,
                    // which is the whole of the illusion of thickness.
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                            startX = 0f,
                            endX = size.width * 0.4f,
                        ),
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                            startX = size.width * 0.55f,
                            endX = size.width,
                        ),
                    )
                    bands.forEach { at ->
                        drawRect(
                            color = Color.White.copy(alpha = 0.24f),
                            topLeft = Offset(0f, size.height * at),
                            size = Size(size.width, 1.5.dp.toPx()),
                        )
                    }
                    // How far in, drawn as a band rising from the foot rather
                    // than a bar beside it: a book is part-read, not part-full.
                    if (progress > 0f) {
                        val fill = (size.height * progress.coerceIn(0f, 1f))
                        drawRect(
                            color = Color.White.copy(alpha = 0.10f),
                            topLeft = Offset(0f, size.height - fill),
                            size = Size(size.width, fill),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = if (look.ghost) ghostInk else Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // requiredWidth ignores the parent's constraints, so the text is
            // laid out along the spine's LONG axis before being turned onto
            // it. Without that it would be measured against the spine's width
            // and every title would come out as one letter and an ellipsis.
            modifier = Modifier
                .requiredWidth(height - 16.dp)
                .rotate(-90f),
        )
    }
}
