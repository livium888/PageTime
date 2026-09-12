package com.pagetime.app.data.library

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** A PDF whose pages are pictures rather than text, so there is nothing to read. */
class ScannedPdfException(message: String) : Exception(message)

/**
 * Turns a text PDF into something PageTime can read as a book.
 *
 * A PDF page is a fixed sheet of paper, and this app's reader changes font,
 * size, line height and margins — settings that only mean anything for text
 * that can re-flow. So a PDF is not rendered here: its text is lifted out, page
 * by page, and [PdfToEpub] writes those pages back out as a book the EPUB
 * engine can lay out. From then on Readium owns it exactly as it owns a
 * downloaded .epub, which is what makes saved positions, highlights, chunk
 * reading and Explain Back work on a PDF without any of them knowing a PDF
 * exists — and what will let figures ride along with the text as `<img>`s.
 *
 * All of this happens on the phone. Nothing is uploaded and no service is
 * involved.
 *
 * The honest limits, which the reader is told about rather than left to
 * discover:
 *
 *  - A scanned PDF has no text to lift, so it is refused with a clear message
 *    ([ScannedPdfException]) instead of imported as an empty book. Reading
 *    scans needs OCR, which this app does not do.
 *  - Two-column pages come out interleaved: a PDF's text carries no column
 *    information this extractor uses.
 *  - Figures are not text and are not here: they are cut from the page by
 *    [PdfFigureExtractor], as pictures of the region they occupied. A formula
 *    written as text comes through as text, and one set as a drawing comes
 *    through as a picture of itself.
 *  - The page counter counts the book's pages after re-flow. Chapter entries
 *    are labelled with the printed page numbers, so a figure can still be
 *    cited as "page 42".
 *
 * The original document is kept beside the generated book: it is what makes
 * this re-runnable, and what figures are cut from as they are added.
 */
class PdfTextExtractor(private val context: Context) {

    /**
     * Every page's raw text, in reading order, for [PdfToEpub] to lay out.
     *
     * Raw, not tidied: the tidying pass needs the whole document at once (a
     * running head is only recognisable as one by comparing pages), so it lives
     * in [PdfTextCleaner] and is applied to this list rather than here.
     *
     * @throws ScannedPdfException when the document has essentially no text.
     */
    fun pages(source: File): List<String> {
        // Cheap, idempotent, and required before any PDFBox call: it hands the
        // library this app's AssetManager so it can reach its own resources
        // (font metrics, glyph names) that ship inside the AAR.
        PDFBoxResourceLoader.init(context)

        val pages = read(source)
        if (pages.isEmpty()) error("This PDF has no pages.")

        val characters = pages.sumOf { page -> page.count { !it.isWhitespace() } }
        if (characters < pages.size * MIN_CHARACTERS_PER_PAGE) {
            throw ScannedPdfException(
                "This PDF's pages are pictures, not text — usually a scan — so there " +
                    "is nothing to read out of it. Reading scans needs OCR, which " +
                    "PageTime does not do yet."
            )
        }
        return pages
    }

    /** Every page's raw text, in reading order, capped at a very long book. */
    private fun read(source: File): List<String> {
        // setupTempFileOnly(): PDFBox buffers a document's objects somewhere,
        // and an image-heavy PDF is exactly the kind that exhausts the heap if
        // that somewhere is memory — and exactly the kind a reader is most
        // likely to hand us. Spill it to a scratch file instead, in our own
        // cache directory rather than wherever java.io.tmpdir points.
        val scratch = File(context.cacheDir, "pdf-scratch").apply { mkdirs() }
        val document = try {
            PDDocument.load(
                source,
                MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)
            )
        } catch (error: InvalidPasswordException) {
            throw IllegalStateException(
                "This PDF is password-protected, so its text cannot be read."
            )
        }
        return document.use { doc ->
            // One page at a time, rather than the whole document in one call:
            // the cleaner needs to see page boundaries to find running heads,
            // and the cap keeps a pathological file from running for minutes.
            val pageCount = minOf(doc.numberOfPages, MAX_PAGES)
            (1..pageCount).map { page -> pageText(doc, page) }
        }
    }

    /**
     * One page as text, or empty when that page cannot be read.
     *
     * A page that fails is not a failed import: a single broken content stream
     * should cost the reader that page, not the book.
     */
    private fun pageText(document: PDDocument, page: Int): String = runCatching {
        val stripper = PDFTextStripper()
        // Sort by position rather than by paint order. Neither is "correct"
        // for a two-column page, but position order follows the page top to
        // bottom, which is closer to how a person reads it.
        stripper.sortByPosition = true
        stripper.startPage = page
        stripper.endPage = page
        stripper.getText(document)
    }.getOrDefault("")

    private companion object {
        /**
         * The floor for "this is a document, not a picture of one". A page of
         * real text runs to roughly two thousand characters; a page carrying
         * only a caption or a letterhead runs to tens; a scanned page to none.
         */
        const val MIN_CHARACTERS_PER_PAGE = 40

        /** A cap on work rather than a product limit: 2,000 pages is a long book. */
        const val MAX_PAGES = 2_000
    }
}
