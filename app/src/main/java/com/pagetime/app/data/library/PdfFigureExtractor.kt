package com.pagetime.app.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * An image cut out of a printed PDF page.
 *
 * Not a copy of an embedded file: a PDF page is a canvas of paint instructions,
 * and most of what a reader would call a figure — a chart, a circuit diagram, a
 * table, a map — was never stored as an image at all. It only exists as the
 * finished picture, so that is what is taken: the region of the page is
 * re-rendered on its own, at a higher resolution than the screen needs, and
 * written out as a file.
 */
class PdfFigure(
    /** Encoded image bytes: PNG for line art, JPEG when a photograph needs the room. */
    val bytes: ByteArray,
    /** "png" or "jpg" — the file extension, and so the media type. */
    val extension: String,
    /** The document page this was cut from, 1-based, for the caption. */
    val pageNumber: Int,
)

/**
 * Finds the figures on each page of a PDF and cuts them out.
 *
 * The idea is borrowed from k2pdfopt (the engine behind KOReader's reflow, and
 * open source): rather than understand the document, rasterize the page and
 * look at it. Text is where the text is — which this class knows exactly,
 * because PDFBox reports every line's box — and everything else that is drawn
 * is a figure. That one rule catches photographs, vector diagrams and tables
 * alike, without a table parser, without guessing which embedded image is
 * decoration, and without re-interpreting a single paint instruction.
 *
 * Two consequences worth stating plainly:
 *
 *  - A figure comes back as a picture of the page region, not as data, so it is
 *    as legible as the render. It is rendered at [CROP_WIDTH_PX] wide, several
 *    times what the reading column needs, so a diagram stays crisp.
 *  - A dense table is mostly text, so it may read as prose rather than as a
 *    figure. Nothing here can recover a table's cell structure; showing the
 *    printed grid is the honest alternative, and it is what a sparse table gets.
 *
 * Everything is best-effort: this runs on a device that may be short of memory,
 * on a file that may be strange, and a book with no figures is still a book. Any
 * failure returns no figures rather than failing the import.
 */
class PdfFigureExtractor(private val context: Context) {

    /**
     * The figures on each page, by page index, in top-to-bottom order.
     *
     * @param pageCount how many pages [source] has, from the text pass. The
     *   returned list always has this many entries so callers can index it by
     *   page without checking.
     */
    fun figures(source: File, pageCount: Int): List<List<PdfFigure>> {
        val none = List(pageCount.coerceAtLeast(0)) { emptyList<PdfFigure>() }
        if (pageCount <= 0 || pageCount > MAX_PAGES) return none
        // Cheap, idempotent, and required before any PDFBox call.
        PDFBoxResourceLoader.init(context)
        return runCatching { scan(source, pageCount) }.getOrDefault(none)
    }

    private fun scan(source: File, pageCount: Int): List<List<PdfFigure>> {
        // Where the text is on every page, in one pass over the document.
        val textBoxes = textBoxes(source, pageCount)
        val result = MutableList(pageCount) { emptyList<PdfFigure>() }
        var emitted = 0
        var bytes = 0L

        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pages = minOf(pageCount, renderer.pageCount, MAX_PAGES)
                for (index in 0 until pages) {
                    if (emitted >= MAX_FIGURES || bytes >= MAX_TOTAL_BYTES) break
                    val found = runCatching {
                        pageFigures(renderer, index, textBoxes[index].orEmpty())
                    }.getOrDefault(emptyList())

                    val kept = mutableListOf<PdfFigure>()
                    for (figure in found) {
                        if (emitted >= MAX_FIGURES) break
                        if (bytes + figure.bytes.size > MAX_TOTAL_BYTES) break
                        kept.add(figure)
                        emitted++
                        bytes += figure.bytes.size
                    }
                    result[index] = kept
                }
            }
        }
        return result
    }

    private fun pageFigures(
        renderer: PdfRenderer,
        index: Int,
        textBoxes: List<RectF>,
    ): List<PdfFigure> = renderer.openPage(index).use { page ->
        val width = page.width.toFloat()
        val height = page.height.toFloat()
        if (width < 1f || height < 1f) return@use emptyList()

        // The cheap gate first, because rendering is the expensive half of this
        // class: a page that is mostly text has no room for a figure, and most
        // pages of most books stop here without being rendered at all.
        if (textShare(textBoxes, width * height) >= DENSE_TEXT_SHARE) return@use emptyList()

        figureRects(page, width, height, textBoxes)
            .mapNotNull { rect -> crop(page, rect, index + 1) }
    }

    /** The regions of one page that are drawing rather than text, in points. */
    private fun figureRects(
        page: PdfRenderer.Page,
        pageWidth: Float,
        pageHeight: Float,
        textBoxes: List<RectF>,
    ): List<RectF> {
        val scale = DETECT_WIDTH / pageWidth
        val pixelWidth = DETECT_WIDTH.roundToInt()
        val pixelHeight = (pageHeight * scale).roundToInt().coerceIn(1, MAX_DETECT_HEIGHT)
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        return try {
            // White, not transparent: a page paints its background only if it
            // has one, and a transparent bitmap carries no paper to measure.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            regions(bitmap, pageWidth, pageHeight, textBoxes)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The figure regions of a rendered page.
     *
     * Three steps: which cells of a coarse grid carry ink, which of those cells
     * touch each other, and which of the resulting blocks are not text. The
     * middle step is what turns marks on a page into objects; the last is the
     * whole judgement, and it is deliberately simple — a block is a figure when
     * the page's own text boxes do not account for it.
     */
    private fun regions(
        bitmap: Bitmap,
        pageWidth: Float,
        pageHeight: Float,
        textBoxes: List<RectF>,
    ): List<RectF> {
        val cols = bitmap.width / CELL
        val rows = bitmap.height / CELL
        if (cols < MIN_CELLS || rows < MIN_CELLS) return emptyList()

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // A cell counts as ink when two of its four sampled pixels are darker
        // than paper. One would be lint, anti-aliasing, or the faint edge of a
        // rule; two catches a drawn line and still ignores paper texture.
        val ink = BooleanArray(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var hits = 0
                for (dy in 0 until 2) {
                    for (dx in 0 until 2) {
                        val x = col * CELL + dx * HALF_CELL
                        val y = row * CELL + dy * HALF_CELL
                        if (isInk(pixels[y * bitmap.width + x])) hits++
                    }
                }
                if (hits >= 2) ink[row * cols + col] = true
            }
        }

        val scaleX = pageWidth / cols
        val scaleY = pageHeight / rows
        val candidates = mutableListOf<RectF>()
        explore(ink, cols, rows) { left, top, right, bottom ->
            val rect = RectF(
                left * scaleX,
                top * scaleY,
                (right + 1) * scaleX,
                (bottom + 1) * scaleY,
            )
            val wide = rect.width()
            val tall = rect.height()
            val isBlock = wide >= MIN_WIDTH_PT && tall >= MIN_HEIGHT_PT && wide * tall >= MIN_AREA_PT
            // A block the size of the page is its border, its background, or a
            // page-wide rule, none of which is a figure.
            val isPage = wide > pageWidth * FULL_PAGE_SHARE && tall > pageHeight * FULL_PAGE_SHARE
            if (isBlock && !isPage && overlapShare(rect, textBoxes) < TEXT_SHARE) {
                candidates.add(rect)
            }
        }
        return merge(candidates, pageHeight)
    }

    /**
     * Walks every connected block of ink cells, 8-connected so a diagonal still
     * joins, reporting each one's cell bounds. Iterative on purpose: a
     * recursive flood fill on a page-sized grid is a stack overflow waiting for
     * a photograph of paper.
     */
    private inline fun explore(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
        onRegion: (left: Int, top: Int, right: Int, bottom: Int) -> Unit,
    ) {
        val seen = BooleanArray(ink.size)
        val queue = IntArray(ink.size)
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            seen[start] = true
            var head = 0
            var tail = 0
            queue[tail++] = start
            var left = start % cols
            var right = left
            var top = start / cols
            var bottom = top
            while (head < tail) {
                val cell = queue[head++]
                val cx = cell % cols
                val cy = cell / cols
                if (cx < left) left = cx
                if (cx > right) right = cx
                if (cy < top) top = cy
                if (cy > bottom) bottom = cy
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue
                        val next = ny * cols + nx
                        if (!ink[next] || seen[next]) continue
                        seen[next] = true
                        queue[tail++] = next
                    }
                }
            }
            onRegion(left, top, right, bottom)
        }
    }

    /**
     * Joins blocks that belong to one figure — a chart's axis and its plot, a
     * diagram split by its own white space — without letting a column of
     * unrelated figures become a single block down the page.
     */
    private fun merge(rects: List<RectF>, pageHeight: Float): List<RectF> {
        val merged = mutableListOf<RectF>()
        for (rect in rects.sortedBy { it.top }) {
            val above = merged.lastOrNull()
            if (above != null && joins(above, rect, pageHeight)) {
                above.union(rect)
            } else {
                merged.add(RectF(rect))
            }
        }
        return merged
    }

    private fun joins(above: RectF, below: RectF, pageHeight: Float): Boolean {
        if (below.top - above.bottom > JOIN_GAP_PT) return false
        val overlap = minOf(above.right, below.right) - maxOf(above.left, below.left)
        val narrower = minOf(above.width(), below.width())
        if (narrower <= 0f || overlap < narrower * JOIN_SHARE) return false
        return below.bottom - above.top <= pageHeight * MAX_FIGURE_PAGE_HEIGHT
    }

    /** How much of [rect] the page's text accounts for, 0..1. */
    private fun overlapShare(rect: RectF, textBoxes: List<RectF>): Float {
        val area = rect.width() * rect.height()
        if (area <= 0f) return 1f
        var covered = 0f
        for (box in textBoxes) {
            val width = minOf(rect.right, box.right) - maxOf(rect.left, box.left)
            val height = minOf(rect.bottom, box.bottom) - maxOf(rect.top, box.top)
            if (width > 0f && height > 0f) covered += width * height
        }
        return (covered / area).coerceIn(0f, 1f)
    }

    /** How much of a page of [area] the text boxes cover, 0..1. */
    private fun textShare(textBoxes: List<RectF>, area: Float): Float {
        if (area <= 0f) return 0f
        var covered = 0f
        for (box in textBoxes) {
            covered += box.width().coerceAtLeast(0f) * box.height().coerceAtLeast(0f)
        }
        return (covered / area).coerceIn(0f, 1f)
    }

    /**
     * Renders one region of the page on its own, at reading resolution.
     *
     * The transform is the whole trick: it maps the page's coordinate space
     * onto a bitmap the size of the region, translated so the region's top-left
     * corner lands at the bitmap's origin. Nothing is understood about the
     * figure — it is simply photographed from the page at high density, which
     * is why a vector diagram comes back as sharp lines rather than a blurry
     * grab of a page screenshot.
     */
    private fun crop(page: PdfRenderer.Page, rect: RectF, pageNumber: Int): PdfFigure? {
        val width = rect.width()
        val height = rect.height()
        if (width <= 0f || height <= 0f) return null

        var scale = CROP_WIDTH_PX / width
        var pixelWidth = (width * scale).roundToInt().coerceAtLeast(1)
        var pixelHeight = (height * scale).roundToInt().coerceAtLeast(1)
        if (pixelHeight > MAX_CROP_HEIGHT_PX) {
            // A tall figure — a full-page plate, a long table — is capped, and
            // the width follows so the picture is not squashed.
            scale *= MAX_CROP_HEIGHT_PX.toFloat() / pixelHeight
            pixelWidth = (width * scale).roundToInt().coerceAtLeast(1)
            pixelHeight = MAX_CROP_HEIGHT_PX
        }

        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        val encoded = try {
            bitmap.eraseColor(Color.WHITE)
            page.render(
                bitmap,
                null,
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-rect.left * scale, -rect.top * scale)
                },
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            compress(bitmap)
        } finally {
            bitmap.recycle()
        }
        return PdfFigure(bytes = encoded.first, extension = encoded.second, pageNumber = pageNumber)
    }

    /**
     * PNG, unless the figure turns out to be a photograph.
     *
     * Line art and text need PNG's lossless edges; a photograph does not, and a
     * book of plates at PNG sizes is a book that does not fit on a phone. So a
     * picture that costs more than [JPEG_OVER_PNG_BYTES] is encoded both ways
     * and the smaller one wins, which picks correctly without having to decide
     * what kind of picture it is.
     */
    private fun compress(bitmap: Bitmap): Pair<ByteArray, String> {
        val png = ByteArrayOutputStream()
            .also { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            .toByteArray()
        if (png.size <= JPEG_OVER_PNG_BYTES) return png to "png"

        val jpeg = ByteArrayOutputStream()
            .also { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            .toByteArray()
        return if (jpeg.isNotEmpty() && jpeg.size < png.size) jpeg to "jpg" else png to "png"
    }

    private fun isInk(pixel: Int): Boolean {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red + green + blue) / 3 < INK_LEVEL
    }

    /**
     * Every page's text line boxes, in points, in top-left coordinates — the
     * same coordinates the renderer draws in, so a figure's rectangle and the
     * text's rectangles can be compared directly.
     */
    private fun textBoxes(source: File, maxPages: Int): Map<Int, List<RectF>> {
        val scratch = File(context.cacheDir, "pdf-scratch").apply { mkdirs() }
        val document = PDDocument.load(
            source,
            MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)
        )
        return document.use { doc ->
            val stripper = LineBoxStripper()
            stripper.sortByPosition = true
            stripper.startPage = 1
            stripper.endPage = minOf(doc.numberOfPages, maxPages)
            runCatching { stripper.getText(doc) }
            stripper.boxes
        }
    }

    /**
     * A stripper that keeps every line's box instead of its text.
     *
     * This is the only thing that separates a figure from a paragraph, and it
     * is exact: the library already knows where each line of text sits, so
     * "drawn but not written" needs no threshold, no heuristic and no model.
     */
    private class LineBoxStripper : PDFTextStripper() {
        private val byPage = HashMap<Int, MutableList<RectF>>()

        val boxes: Map<Int, List<RectF>> get() = byPage

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (textPositions.isEmpty()) return
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (position in textPositions) {
                if (position.x < left) left = position.x
                if (position.x + position.width > right) right = position.x + position.width
                if (position.y < top) top = position.y
                if (position.y + position.height > bottom) bottom = position.y + position.height
            }
            if (right <= left || bottom <= top) return
            byPage.getOrPut(currentPageNo) { mutableListOf() }
                .add(RectF(left, top, right, bottom))
        }
    }

    private companion object {
        /** Detection render width, in pixels. Small on purpose: this runs per page. */
        const val DETECT_WIDTH = 720f

        const val MAX_DETECT_HEIGHT = 1_400

        /** Detection cell size, in pixels at [DETECT_WIDTH]. */
        const val CELL = 3

        const val HALF_CELL = CELL / 2

        const val MIN_CELLS = 8

        /** Average channel value below which a pixel counts as printed. */
        const val INK_LEVEL = 235

        /** Too small to be a figure — a bullet, an accent, a stray mark. */
        const val MIN_WIDTH_PT = 42f

        const val MIN_HEIGHT_PT = 26f

        const val MIN_AREA_PT = 2_000f

        /** A block this covered by text is prose, a list, or a rule, not a figure. */
        const val TEXT_SHARE = 0.45f

        /** A page this full of text has no room left for a figure. */
        const val DENSE_TEXT_SHARE = 0.5f

        /** Across both axes, this much of the page is its frame or background. */
        const val FULL_PAGE_SHARE = 0.94f

        /** Two blocks this close are one figure. */
        const val JOIN_GAP_PT = 10f

        const val JOIN_SHARE = 0.35f

        /** But never one figure taller than this share of the page. */
        const val MAX_FIGURE_PAGE_HEIGHT = 0.6f

        /** Wide enough that a diagram is crisp when the reader's column is ~400px. */
        const val CROP_WIDTH_PX = 1_200f

        const val MAX_CROP_HEIGHT_PX = 2_600

        /** Above this, a figure is re-encoded as JPEG and the smaller one wins. */
        const val JPEG_OVER_PNG_BYTES = 160_000

        const val JPEG_QUALITY = 80

        /** A book keeps its most prominent figures, not all of them. */
        const val MAX_FIGURES = 300

        /** And its figures stay within a size a phone can hold. */
        const val MAX_TOTAL_BYTES = 60L * 1024 * 1024

        /** A cap on work rather than a product limit. */
        const val MAX_PAGES = 900
    }
}
