package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.pagetime.app.data.local.ReaderSettings

/**
 * A sheet of warm, dim air laid over the page.
 *
 * WHY A VEIL RATHER THAN A BRIGHTNESS SETTING
 *
 * The reader already lowers the window's backlight, and that is the right
 * first move. It runs out at Android's minimum, which in a dark room is still
 * brighter than a lit page — and no application can go below it, because it is
 * the display driver's floor rather than a policy.
 *
 * What an app can do is draw over its own window. Black at low alpha takes the
 * page below that floor; amber at low alpha shifts a 6500K daylight-white page
 * toward the colour paper takes under a lamp. Both are the same one-line
 * mechanism, and together they are most of what software can do to stop a
 * screen reading as a light source.
 *
 * WHAT IT IS NOT
 *
 * It is not a blue-light filter making a health claim, and it is not a
 * system-wide overlay: it covers this window only and disappears with the
 * reader, so nothing it does can outlive the book or reach another app.
 *
 * WHY IT CANNOT SWALLOW A TAP
 *
 * It draws and nothing else. Compose delivers touches to nodes that ask for
 * them, and this one never does, so page turns and the chrome toggle pass
 * straight through. A veil that ate the page-turn gesture would be a far worse
 * bug than the glare it was added to fix.
 */
@Composable
fun PaperVeil(settings: ReaderSettings, modifier: Modifier = Modifier) {
    val warmth = settings.warmth
    val dim = settings.nightDim
    if (warmth <= 0f && dim <= 0f) return

    // Amber first, then black over it. Two passes rather than one blended
    // colour because they are independent controls: warming a bright page and
    // darkening a warm one are different requests, and the reader may want
    // either on its own.
    //
    // drawBehind, not drawWithContent — this Box holds nothing. Everything it
    // covers is beneath it in the parent's stack, so the rectangles ARE the
    // whole of what it draws.
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                if (warmth > 0f) {
                    drawRect(Color(0xFFFF9500).copy(alpha = warmth * MAX_WARMTH_ALPHA))
                }
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
            }
    )
}

/**
 * The strongest the amber ever gets.
 *
 * Full opacity would be a solid orange rectangle. A third is about as far as
 * the page can warm before the text starts losing contrast against it, which
 * is the opposite of the point.
 */
private const val MAX_WARMTH_ALPHA = 0.33f
