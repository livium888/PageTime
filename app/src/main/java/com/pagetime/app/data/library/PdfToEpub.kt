package com.pagetime.app.data.library

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a PDF's pages out as an EPUB, figures and all.
 *
 * This is the whole of the "read a PDF as a book" idea. Rather than teaching the
 * text reader about images, fonts and page layout, a PDF is turned into the one
 * format this app already renders properly — and Readium does the rest: text at
 * the reader's font size, a figure at the column's width, rotation, selection,
 * highlights, positions. Nothing new has to be written for any of that, and the
 * same machinery that makes a downloaded .epub look right makes a converted PDF
 * look right.
 *
 * **One document per page, deliberately.** It costs a few hundred small files,
 * and it buys three things: chapter entries labelled with the printed page
 * number (so "see page 42" is a real place), a paragraph that never straddles a
 * page boundary in the reader's own pagination, and — the important one here —
 * a figure that belongs unambiguously to the page it was cut from. A figure is
 * placed at the end of its own page's text, so it is never seen before the page
 * it belongs to and never after the next one.
 *
 * **Figures are real files.** Each one is written into `images/` and referenced
 * with an `<img>`, which is exactly how an illustrated EPUB carries its plates —
 * the publisher has the illustration, links it from the markup, and the reader
 * scales it to the column. Readium needs to know nothing about PDFs to show one.
 *
 * The output is a plain EPUB 3: a stored (uncompressed) `mimetype` first, then
 * `META-INF/container.xml`, the OPF, an EPUB 3 nav document, a stylesheet, and
 * one XHTML document per page. That is also exactly the shape [EpubParser]
 * expects, which matters because learning features read the generated book back
 * out of the ZIP.
 *
 * Everything here is deterministic and offline: same pages in, same book out.
 *
 * One detail that is easy to get wrong: every document below ends in
 * `trimIndent().trimStart()`, not `trimIndent()` alone. The declaration has to
 * be the very first thing in an XML document, and the interpolated parts (the
 * manifest, a page's paragraphs) are indented less than the template around
 * them, so the common indent that `trimIndent` removes is theirs, not the
 * template's — leaving spaces in front of `<?xml`, which is not a document at
 * all. `trimStart()` takes them back off the one line that matters.
 */
class PdfToEpub {

    /**
     * Writes [pages] to [destination] as an EPUB titled [title].
     *
     * @param source the document the pages came from. Not read; it is the book's
     *   identity for the generated identifier, so re-converting the same file
     *   produces the same book rather than a new one each time.
     * @param figures the page's figures, by page index, from [PdfFigureExtractor].
     *   Optional: a PDF with no figures in it converts to a normal text book.
     */
    fun convert(
        source: File,
        destination: File,
        title: String,
        author: String,
        pages: List<String>,
        figures: List<List<PdfFigure>> = emptyList(),
    ) {
        require(pages.isNotEmpty()) { "This PDF has no pages." }
        val bookTitle = title.trim().ifBlank { "Imported PDF" }
        val bookAuthor = author.trim()
        // Page by page, so each document gets its own paragraphs — and so a
        // figure has one page to belong to.
        val paragraphs = PdfTextCleaner.cleanPerPage(pages)

        val images = mutableListOf<BookImage>()
        val pageImages = pages.indices.map { index ->
            figures.getOrNull(index).orEmpty().mapIndexed { position, figure ->
                val name = "page-${pageName(index)}-${position + 1}"
                val extension = figure.extension
                BookImage(
                    id = "image-$name",
                    path = "images/$name.$extension",
                    mediaType = if (extension == "jpg") "image/jpeg" else "image/png",
                    bytes = figure.bytes,
                    caption = "Figure — page ${figure.pageNumber}",
                ).also { image -> images.add(image) }
            }
        }
        val documents = pages.indices.map { index -> "text/page-${pageName(index)}.xhtml" }

        val entries = buildList<Pair<String, ByteArray>> {
            add("META-INF/container.xml" to bytes(containerXml()))
            add("OEBPS/content.opf" to bytes(opf(bookTitle, bookAuthor, source, documents, images)))
            add("OEBPS/nav.xhtml" to bytes(navXml(bookTitle, documents)))
            add("OEBPS/style.css" to bytes(STYLE))
            documents.forEachIndexed { index, path ->
                add("OEBPS/$path" to bytes(pageXhtml(index, paragraphs[index], pageImages[index])))
            }
            images.forEach { image -> add("OEBPS/${image.path}" to image.bytes) }
        }

        destination.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            // The mimetype has to be the first entry and stored uncompressed, or
            // the file is not an EPUB: readers sniff these 20 bytes to tell an
            // EPUB from any other ZIP. Which also means it cannot be written
            // through compression, so its size and CRC are computed up front.
            val mimetype = bytes(EPUB_MIMETYPE)
            zip.putNextEntry(
                ZipEntry(MIMETYPE_PATH).apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                }
            )
            zip.write(mimetype)
            zip.closeEntry()

            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private fun pageName(index: Int): String = (index + 1).toString().padStart(4, '0')

    private fun containerXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$OPF_PATH" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent().trimStart()

    private fun opf(
        title: String,
        author: String,
        source: File,
        documents: List<String>,
        images: List<BookImage>,
    ): String {
        val manifest = StringBuilder()
        manifest.append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        manifest.append('\n')
        manifest.append("""    <item id="css" href="style.css" media-type="text/css"/>""")
        manifest.append('\n')
        documents.forEachIndexed { index, path ->
            val id = "page-${pageName(index)}"
            manifest.append("""    <item id="$id" href="$path" media-type="application/xhtml+xml"/>""")
            manifest.append('\n')
        }
        images.forEach { image ->
            manifest.append("""    <item id="${image.id}" href="${image.path}" media-type="${image.mediaType}"/>""")
            manifest.append('\n')
        }

        val spine = documents.indices.joinToString("\n") { index ->
            """    <itemref idref="page-${pageName(index)}"/>"""
        }

        val creator = if (author.isBlank()) "" else
            "\n    <dc:creator>${escape(author)}</dc:creator>"

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">${identifier(source)}</dc:identifier>
                <dc:title>${escape(title)}</dc:title>
                <dc:language>${language()}</dc:language>$creator
                <meta property="dcterms:modified">${modified()}</meta>
              </metadata>
              <manifest>
            $manifest  </manifest>
              <spine>
            $spine
              </spine>
            </package>
        """.trimIndent().trimStart()
    }

    private fun navXml(title: String, documents: List<String>): String {
        val items = documents.indices.joinToString("\n") { index ->
            """      <li><a href="${documents[index]}">Page ${index + 1}</a></li>"""
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head>
            <meta charset="utf-8"/>
            <title>${escape(title)}</title>
            </head>
            <body>
            <nav epub:type="toc" id="toc">
              <h1>Pages</h1>
              <ol>
            $items
              </ol>
            </nav>
            </body>
            </html>
        """.trimIndent().trimStart()
    }

    /**
     * One page as an XHTML document: its text, then the figures cut from it.
     *
     * A page that is a full-page figure has no text at all, and still gets a
     * document — an empty spine entry is a broken book.
     */
    private fun pageXhtml(pageIndex: Int, text: String, images: List<BookImage>): String {
        val paragraphs = text.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "<p>${escape(it)}</p>" }

        val figures = images.joinToString("\n") { image ->
            """
            <figure>
            <img src="../${image.path}" alt="${escape(image.caption)}"/>
            <figcaption>${escape(image.caption)}</figcaption>
            </figure>
            """.trimIndent()
        }

        val body = listOf(paragraphs, figures)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { """<p class="blank">&#160;</p>""" }

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
            <meta charset="utf-8"/>
            <title>Page ${pageIndex + 1}</title>
            <link rel="stylesheet" type="text/css" href="../style.css"/>
            </head>
            <body>
            $body
            </body>
            </html>
        """.trimIndent().trimStart()
    }

    private fun identifier(source: File): String {
        // Derived from the document's name, so converting the same file twice
        // yields the same identifier instead of a fresh random one.
        val name = source.name.let { it.ifBlank { "document" } }
        return "urn:uuid:${UUID.nameUUIDFromBytes(name.toByteArray())}"
    }

    private fun modified(): String = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())

    private fun language(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    /** Text for XML, in both a text node and an attribute value. */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** One figure, as a file in the archive and an entry in the manifest. */
    private class BookImage(
        val id: String,
        val path: String,
        val mediaType: String,
        val bytes: ByteArray,
        val caption: String,
    )

    private companion object {
        const val EPUB_MIMETYPE = "application/epub+zip"
        const val MIMETYPE_PATH = "mimetype"
        const val OPF_PATH = "OEBPS/content.opf"

        /**
         * Enough to read a converted book in the app's own typography, and the
         * image rules do the work that matters: a figure arrives at the
         * column's width and shrinks with it, never wider, so a diagram or a
         * table is read by scrolling rather than by pinching.
         */
        const val STYLE = """
            body { margin: 0; padding: 0; text-align: left; }
            p { margin: 0 0 0.7em 0; line-height: 1.5; text-indent: 0; }
            p.blank { margin: 0; }
            figure { margin: 1.4em 0; text-align: center; }
            img { max-width: 100%; height: auto; }
            figcaption { font-size: 0.85em; opacity: 0.7; margin-top: 0.5em; }
        """
    }
}
