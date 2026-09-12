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

/** A rendered figure cut from the page it belongs to. */
class PdfFigure(
    val bytes: ByteArray,
    val extension: String,
    val pageNumber: Int,
)

/**
 * Finds non-text regions in a born-digital PDF and renders them as EPUB images.
 *
 * PDF files usually do not have a "figure" object. A diagram or table can be
 * many separate drawing commands, while a watermark can look like one large
 * drawing. This detector is deliberately conservative: it removes PDFBox's
 * text boxes first, ignores light marks, and only keeps a compact, connected
 * region with enough dark ink to plausibly be a figure. Missing a marginal
 * illustration is preferable to inserting the page's background artwork as a
 * giant, repeated figure.
 */
class PdfFigureExtractor(private val context: Context) {

    fun figures(source: File, pageCount: Int): List<List<PdfFigure>> {
        val empty = List(pageCount.coerceAtLeast(0)) { emptyList<PdfFigure>() }
        if (pageCount <= 0 || pageCount > MAX_PAGES) return empty
        PDFBoxResourceLoader.init(context)
        return runCatching { scan(source, pageCount) }.getOrDefault(empty)
    }

    private fun scan(source: File, pageCount: Int): List<List<PdfFigure>> {
        val boxesByPage = textBoxes(source, pageCount)
        val output = MutableList(pageCount) { emptyList<PdfFigure>() }
        var count = 0
        var totalBytes = 0L

        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pages = minOf(pageCount, renderer.pageCount, MAX_PAGES)
                for (index in 0 until pages) {
                    if (count >= MAX_FIGURES || totalBytes >= MAX_TOTAL_BYTES) break
                    val found = runCatching {
                        pageFigures(renderer, index, boxesByPage[index + 1].orEmpty())
                    }.getOrDefault(emptyList())
                    val accepted = found.filter { figure ->
                        if (count >= MAX_FIGURES || totalBytes + figure.bytes.size > MAX_TOTAL_BYTES) {
                            false
                        } else {
                            count++
                            totalBytes += figure.bytes.size
                            true
                        }
                    }
                    output[index] = accepted
                }
            }
        }
        return output
    }

    private fun pageFigures(
        renderer: PdfRenderer,
        index: Int,
        textBoxes: List<RectF>,
    ): List<PdfFigure> = renderer.openPage(index).use { page ->
        val width = page.width.toFloat()
        val height = page.height.toFloat()
        if (width <= 0f || height <= 0f) return@use emptyList()

        // A page with text covering most of its area is very unlikely to have a
        // large separate figure. This also avoids rendering ordinary book pages.
        if (textAreaShare(textBoxes, width * height) >= MAX_TEXT_AREA_SHARE) {
            return@use emptyList()
        }
        detectRects(page, width, height, textBoxes)
            .mapNotNull { crop(page, it, index + 1) }
    }

    private fun detectRects(
        page: PdfRenderer.Page,
        pageWidth: Float,
        pageHeight: Float,
        textBoxes: List<RectF>,
    ): List<RectF> {
        val scale = DETECTION_WIDTH / pageWidth
        val bitmapWidth = DETECTION_WIDTH.roundToInt()
        val bitmapHeight = (pageHeight * scale).roundToInt().coerceIn(1, MAX_DETECTION_HEIGHT)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val columns = bitmap.width / CELL_SIZE
            val rows = bitmap.height / CELL_SIZE
            if (columns < 1 || rows < 1) return emptyList()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val maskedText = textMask(columns, rows, pageWidth, pageHeight, textBoxes)
            val ink = BooleanArray(columns * rows)

            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val cell = row * columns + column
                    if (maskedText[cell]) continue
                    var dark = 0
                    for (dy in 0 until SAMPLE_SIDE) {
                        for (dx in 0 until SAMPLE_SIDE) {
                            val x = (column * CELL_SIZE + dx * SAMPLE_STEP)
                                .coerceAtMost(bitmap.width - 1)
                            val y = (row * CELL_SIZE + dy * SAMPLE_STEP)
                                .coerceAtMost(bitmap.height - 1)
                            if (isDark(pixels[y * bitmap.width + x])) dark++
                        }
                    }
                    if (dark >= MIN_DARK_SAMPLES) ink[cell] = true
                }
            }

            val xScale = pageWidth / columns
            val yScale = pageHeight / rows
            val regions = mutableListOf<RectF>()
            explore(ink, columns, rows) { cellLeft, cellTop, cellRight, cellBottom, cells ->
                val raw = RectF(
                    cellLeft * xScale,
                    cellTop * yScale,
                    (cellRight + 1) * xScale,
                    (cellBottom + 1) * yScale,
                )
                val cellArea = (cellRight - cellLeft + 1).toLong() * (cellBottom - cellTop + 1).toLong()
                val density = if (cellArea == 0L) 0f else cells.toFloat() / cellArea
                val candidate = RectF(raw).apply {
                    inset(-PADDING_PT, -PADDING_PT)
                    left = left.coerceAtLeast(0f)
                    top = top.coerceAtLeast(0f)
                    right = right.coerceAtMost(pageWidth)
                    bottom = bottom.coerceAtMost(pageHeight)
                }
                val isLargeEnough = candidate.width() >= MIN_WIDTH_PT &&
                    candidate.height() >= MIN_HEIGHT_PT &&
                    candidate.width() * candidate.height() >= MIN_AREA_PT
                val isDenseEnough = cells >= MIN_DARK_CELLS && density >= MIN_DENSITY
                val isNotPageArtwork = candidate.width() < pageWidth * MAX_REGION_WIDTH &&
                    candidate.height() < pageHeight * MAX_REGION_HEIGHT
                val leavesTextAlone = overlapShare(candidate, textBoxes) < MAX_TEXT_OVERLAP
                if (isLargeEnough && isDenseEnough && isNotPageArtwork && leavesTextAlone) {
                    regions.add(candidate)
                }
            }
            mergeNearby(regions)
        } finally {
            bitmap.recycle()
        }
    }

    private inline fun explore(
        ink: BooleanArray,
        columns: Int,
        rows: Int,
        onRegion: (left: Int, top: Int, right: Int, bottom: Int, cells: Int) -> Unit,
    ) {
        val seen = BooleanArray(ink.size)
        val queue = IntArray(ink.size)
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            var head = 0
            var tail = 1
            var cells = 0
            var left = start % columns
            var right = left
            var top = start / columns
            var bottom = top
            seen[start] = true
            queue[0] = start
            while (head < tail) {
                val current = queue[head++]
                val x = current % columns
                val y = current / columns
                cells++
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nextX = x + dx
                        val nextY = y + dy
                        if (nextX !in 0 until columns || nextY !in 0 until rows) continue
                        val next = nextY * columns + nextX
                        if (!ink[next] || seen[next]) continue
                        seen[next] = true
                        queue[tail++] = next
                    }
                }
            }
            onRegion(left, top, right, bottom, cells)
        }
    }

    private fun mergeNearby(rects: List<RectF>): List<RectF> {
        val result = mutableListOf<RectF>()
        for (rect in rects.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })) {
            val previous = result.lastOrNull()
            if (previous != null && rect.top - previous.bottom <= MERGE_GAP_PT &&
                horizontalOverlap(previous, rect) >= minOf(previous.width(), rect.width()) * MERGE_OVERLAP) {
                previous.union(rect)
            } else {
                result.add(RectF(rect))
            }
        }
        return result
    }

    private fun crop(page: PdfRenderer.Page, rect: RectF, pageNumber: Int): PdfFigure? {
        val scale = (CROP_WIDTH_PX / rect.width()).coerceAtMost(MAX_CROP_SCALE)
        val width = (rect.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (rect.height() * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        return encoded?.let { PdfFigure(it.first, it.second, pageNumber) }
    }

    private fun compress(bitmap: Bitmap): Pair<ByteArray, String>? {
        val png = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) return null
        val pngBytes = png.toByteArray()
        if (pngBytes.size <= JPEG_THRESHOLD) return pngBytes to "png"
        val jpg = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, jpg)) return pngBytes to "png"
        val jpgBytes = jpg.toByteArray()
        return if (jpgBytes.isNotEmpty() && jpgBytes.size < pngBytes.size) jpgBytes to "jpg" else pngBytes to "png"
    }

    private fun textMask(
        columns: Int,
        rows: Int,
        pageWidth: Float,
        pageHeight: Float,
        textBoxes: List<RectF>,
    ): BooleanArray {
        val result = BooleanArray(columns * rows)
        val cellWidth = pageWidth / columns
        val cellHeight = pageHeight / rows
        for (box in textBoxes) {
            val left = ((box.left - TEXT_PADDING_PT) / cellWidth).toInt().coerceIn(0, columns - 1)
            val right = ((box.right + TEXT_PADDING_PT) / cellWidth).toInt().coerceIn(0, columns - 1)
            val top = ((box.top - TEXT_PADDING_PT) / cellHeight).toInt().coerceIn(0, rows - 1)
            val bottom = ((box.bottom + TEXT_PADDING_PT) / cellHeight).toInt().coerceIn(0, rows - 1)
            for (row in top..bottom) {
                for (column in left..right) result[row * columns + column] = true
            }
        }
        return result
    }

    private fun isDark(pixel: Int): Boolean {
        val average = (((pixel shr 16) and 0xff) + ((pixel shr 8) and 0xff) + (pixel and 0xff)) / 3
        return average < INK_LEVEL
    }

    private fun textBoxes(source: File, maxPages: Int): Map<Int, List<RectF>> {
        val scratch = File(context.cacheDir, "pdf-scratch").apply { mkdirs() }
        val document = PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch))
        return document.use { pdf ->
            val stripper = LineBoxStripper()
            stripper.sortByPosition = true
            stripper.startPage = 1
            stripper.endPage = minOf(pdf.numberOfPages, maxPages)
            runCatching { stripper.getText(pdf) }
            stripper.boxes
        }
    }

    private class LineBoxStripper : PDFTextStripper() {
        private val byPage = HashMap<Int, MutableList<RectF>>()
        val boxes: Map<Int, List<RectF>> get() = byPage

        override fun writeString(text: String, positions: List<TextPosition>) {
            if (positions.isEmpty()) return
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            positions.forEach { position ->
                left = minOf(left, position.xDirAdj)
                top = minOf(top, position.yDirAdj)
                right = maxOf(right, position.xDirAdj + position.widthDirAdj)
                bottom = maxOf(bottom, position.yDirAdj + position.heightDir)
            }
            if (left < right && top < bottom) {
                byPage.getOrPut(currentPageNo) { mutableListOf() }.add(RectF(left, top, right, bottom))
            }
        }
    }

    private fun textAreaShare(boxes: List<RectF>, pageArea: Float): Float =
        if (pageArea <= 0f) 0f else (boxes.sumOf { it.width().toDouble().coerceAtLeast(0.0) * it.height().toDouble().coerceAtLeast(0.0) } / pageArea).toFloat().coerceIn(0f, 1f)

    private fun overlapShare(rect: RectF, boxes: List<RectF>): Float {
        val area = rect.width() * rect.height()
        if (area <= 0f) return 1f
        val covered = boxes.sumOf { box ->
            val width = (minOf(rect.right, box.right) - maxOf(rect.left, box.left)).coerceAtLeast(0f)
            val height = (minOf(rect.bottom, box.bottom) - maxOf(rect.top, box.top)).coerceAtLeast(0f)
            (width * height).toDouble()
        }
        return (covered / area).toFloat().coerceIn(0f, 1f)
    }

    private fun horizontalOverlap(a: RectF, b: RectF): Float =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)

    private companion object {
        const val MAX_PAGES = 900
        const val MAX_FIGURES = 300
        const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
        const val DETECTION_WIDTH = 720f
        const val MAX_DETECTION_HEIGHT = 1_400
        const val CELL_SIZE = 3
        const val SAMPLE_SIDE = 2
        const val SAMPLE_STEP = 1
        const val INK_LEVEL = 180
        const val MIN_DARK_SAMPLES = 2
        const val MIN_DARK_CELLS = 18
        const val MIN_DENSITY = 0.015f
        const val MIN_WIDTH_PT = 42f
        const val MIN_HEIGHT_PT = 26f
        const val MIN_AREA_PT = 2_000f
        const val PADDING_PT = 5f
        const val TEXT_PADDING_PT = 4f
        const val MAX_TEXT_AREA_SHARE = 0.5f
        const val MAX_TEXT_OVERLAP = 0.45f
        const val MAX_REGION_WIDTH = 0.94f
        const val MAX_REGION_HEIGHT = 0.60f
        const val MERGE_GAP_PT = 10f
        const val MERGE_OVERLAP = 0.35f
        const val CROP_WIDTH_PX = 1_200f
        const val MAX_CROP_SCALE = 3.5f
        const val JPEG_THRESHOLD = 160_000
    }
}
