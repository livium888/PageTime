package com.pagetime.app.ui.screens.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * THE AUTHOR IS ON THE SPINE WHEN IT FITS
 *
 * The title was the only lettering, which made a shelf of untitled coloured
 * bars. A writer's name goes at the foot, and only when there is room for it
 * beside the title — see ShelfDecor.spineAuthor. When there is not, the title
 * keeps the whole spine, because a cramped spine is worse than a spare one.
 *
 * THE RIBBON IS THE ONLY THING THAT MEANS ANYTHING
 *
 * A bookmark hangs from the head of the spine, as long as how far in the
 * reader is. It is the one saturated colour in an otherwise muted case, so
 * "the book I am reading" is findable at a glance instead of by reading.
 *
 * A GHOST IS A BOOK YOU DO NOT HAVE
 *
 * Outlined instead of filled. It puts the availability state on the shelf
 * without a word of text — two solid books and twenty ghosts says exactly what
 * an author shelf holds, at a glance.
 *
 * NOTHING HERE IS ASSERTED BY A TEST
 *
 * The numbers behind it are checked in BookSpinesTest and ShelfDecorTest —
 * stable, in range, varied. Whether the result looks like a book is not a
 * thing a test can see, and this was written without ever rendering it.
 */
@Composable
fun BookSpine(
    title: String,
    author: String,
    owned: Boolean,
    progress: Float,
    shelfHeight: Dp,
    shelfCase: ShelfCase,
    onClick: () -> Unit,
) {
    val look = remember(title, author, owned) { BookSpines.lookFor(title, author, owned) }
    val base = remember(look) { Color.hsl(look.hue, look.saturation, look.lightness) }
    val height = shelfHeight * look.heightFraction
    val bands = remember(look) { BookSpines.bandPositions(look) }
    val ribbon = remember(progress) { ShelfDecor.ribbonFraction(progress) }
    val spineAuthor = remember(title, author, height) {
        ShelfDecor.spineAuthor(height.value.toInt(), title, author)
    }
    val ink = if (look.ghost) shelfCase.ghostStroke else Color.White.copy(alpha = 0.92f)

    Box(
        modifier = Modifier
            .width(look.widthDp.dp)
            .height(height)
            .clickable(onClick = onClick)
            .drawBehind {
                val corner = CornerRadius(2.dp.toPx())
                if (look.ghost) {
                    // Barely there: present enough to count, plainly not owned.
                    drawRoundRect(color = shelfCase.ghostFill, cornerRadius = corner)
                    drawRoundRect(
                        color = shelfCase.ghostStroke,
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
                    // The cloth turn-in at the head and the foot: two short
                    // rounded caps, which is what a binding actually looks
                    // like where it is folded over the boards.
                    val cap = 2.5.dp.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.16f),
                        topLeft = Offset.Zero,
                        size = Size(size.width, cap),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.16f),
                        topLeft = Offset(0f, size.height - cap),
                        size = Size(size.width, cap),
                        cornerRadius = corner,
                    )
                    bands.forEach { at ->
                        drawRect(
                            color = Color.White.copy(alpha = 0.24f),
                            topLeft = Offset(0f, size.height * at),
                            size = Size(size.width, 1.5.dp.toPx()),
                        )
                    }
                    // Standing in its own shadow: the foot of a book on a
                    // shelf is always the darkest part of it.
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                shelfCase.contactShadow.copy(alpha = 0.40f),
                            ),
                            startY = size.height - size.height * 0.16f,
                            endY = size.height,
                        ),
                    )
                    drawRibbon(ribbon, shelfCase)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (spineAuthor == null) {
            SpineTitle(title = title, ink = ink, spineHeight = height)
        } else {
            // Laid out along the spine's long axis and then turned onto it. The
            // author is the first child, and a quarter turn puts the first
            // child at the foot — so the writer reads first, up the spine.
            Row(
                modifier = Modifier
                    .requiredWidth(height - 18.dp)
                    .rotate(-90f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = spineAuthor,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = ink.copy(alpha = 0.66f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SpineTitle(
    title: String,
    ink: Color,
    spineHeight: Dp,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = ink,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        // requiredWidth ignores the parent's constraints, so the text is laid
        // out along the spine's LONG axis before being turned onto it. Without
        // that it would be measured against the spine's width and every title
        // would come out as one letter and an ellipsis.
        modifier = Modifier
            .requiredWidth(spineHeight - 16.dp)
            .rotate(-90f),
    )
}

/**
 * The bookmark, drawn from the head of the spine downwards.
 *
 * It hangs over the right edge rather than down the middle, because the title
 * is on the middle and a ribbon through it would be unreadable. The notched
 * tail is what stops it reading as a painted stripe.
 */
private fun DrawScope.drawRibbon(
    fraction: Float,
    shelfCase: ShelfCase,
) {
    if (fraction <= 0f) return

    val ribbonWidth = 3.5.dp.toPx()
    val inset = 3.dp.toPx()
    val left = size.width - inset - ribbonWidth
    if (left <= 0f) return

    val length = size.height * fraction
    val notch = 4.dp.toPx()

    // The ribbon is thick enough to throw a shadow onto the spine beside it.
    val shade = 2.5.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent),
            startX = left - shade,
            endX = left,
        ),
        topLeft = Offset(left - shade, 0f),
        size = Size(shade, length),
    )

    val path = Path().apply {
        moveTo(left, 0f)
        lineTo(left + ribbonWidth, 0f)
        lineTo(left + ribbonWidth, length - notch)
        lineTo(left + ribbonWidth / 2f, length)
        lineTo(left, length - notch)
        close()
    }
    drawPath(path, color = shelfCase.ribbon)

    // One lit edge, so the ribbon is a strip of cloth and not a fill.
    drawRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(left + 0.6.dp.toPx(), 0f),
        size = Size(0.8.dp.toPx(), length - notch),
    )
}
