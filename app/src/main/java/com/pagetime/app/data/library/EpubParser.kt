package com.pagetime.app.data.library

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubChapter(val title: String, val filePath: String, val anchor: String? = null)

data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>
)

/**
 * Minimal EPUB reader-side parser: locates the OPF, builds the reading order from the
 * spine, grabs chapter labels from the NCX when available, and extracts the whole
 * archive so chapters, images, and stylesheets can be rendered by a WebView via file://.
 */
class EpubParser {

    fun parse(epubFile: File, extractDir: File): EpubBook {
        if (!epubFile.exists()) error("EPUB file not found: ${epubFile.absolutePath}")
        if (epubFile.length() < 100) error("EPUB file is too small or corrupted")

        ZipFile(epubFile).use { zip ->
            // 1. Locate the OPF via META-INF/container.xml
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: error("Not a valid EPUB (missing container.xml)")
            val container = parse(zip.getInputStream(containerEntry))
            val opfPath = container.documentElement.elementsByLocalName("rootfile")
                .firstOrNull()?.getAttribute("full-path")
                ?.takeIf { it.isNotBlank() }
                ?: error("No OPF path in container.xml")
            val opfDir = File(opfPath).parent?.takeUnless { it.isBlank() } ?: ""

            // 2. Parse the OPF for metadata, manifest, and spine
            val opfEntry = zip.getEntry(opfPath) ?: error("OPF not found: $opfPath")
            val opf = parse(zip.getInputStream(opfEntry))
            val metadata = opf.documentElement.firstByLocalName("metadata")
            val title = metadata?.firstByLocalName("title")?.textContent?.trim().orEmpty()
                .ifBlank { epubFile.nameWithoutExtension }
            val author = metadata?.firstByLocalName("creator")?.textContent?.trim().orEmpty()

            // Store both href and EPUB3 properties (e.g. "nav", "cover-image")
            // so we can filter out non-content spine items.
            data class ManifestEntry(val href: String, val properties: String)
            val manifest = mutableMapOf<String, ManifestEntry>()
            opf.documentElement.firstByLocalName("manifest")
                ?.elementsByLocalName("item")
                ?.forEach { item ->
                    val id = item.getAttribute("id")
                    val href = item.getAttribute("href")
                    if (id.isNotBlank() && href.isNotBlank()) {
                        manifest[id] = ManifestEntry(
                            href = href,
                            properties = item.getAttribute("properties")
                        )
                    }
                }

            val spineElement = opf.documentElement.firstByLocalName("spine")
            val spine = spineElement
                ?.elementsByLocalName("itemref")
                ?.mapNotNull { ref ->
                    val idref = ref.getAttribute("idref")
                    val entry = manifest[idref] ?: return@mapNotNull null
                    // EPUB3: skip navigation and cover-image items — they aren't
                    // reading content and showing them blanks the reader.
                    val props = entry.properties
                    if (props.contains("nav") || props.contains("cover-image")) {
                        return@mapNotNull null
                    }
                    entry.href
                }
                ?: emptyList()
            if (spine.isEmpty()) error("EPUB has no spine (no reading content found)")

            // 3. Optional chapter labels from the NCX (EPUB2) or nav (EPUB3) TOC.
            val tocLabels = mutableMapOf<String, String>()

            // EPUB2: NCX table of contents referenced by the spine "toc" attribute.
            val ncxHref = spineElement?.getAttribute("toc")?.let { manifest[it]?.href }
            if (ncxHref != null) {
                val ncxPath = resolve(opfDir, ncxHref)
                val ncxEntry = zip.getEntry(ncxPath) ?: zip.getEntry(ncxHref)
                if (ncxEntry != null) {
                    val ncx = parse(zip.getInputStream(ncxEntry))
                    val ncxDir = File(ncxPath).parent?.takeUnless { it.isBlank() } ?: ""
                    ncx.documentElement.elementsByLocalName("navPoint").forEach { np ->
                        val label = np.firstByLocalName("navLabel")
                            ?.firstByLocalName("text")?.textContent?.trim()
                        val rawSrc = np.firstByLocalName("content")
                            ?.getAttribute("src")
                        if (!label.isNullOrBlank() && !rawSrc.isNullOrBlank()) {
                            val srcPath = rawSrc.substringBefore('#')
                            val anchor = rawSrc.substringAfter('#', "").ifBlank { null }
                            val resolved = resolve(ncxDir, srcPath)
                            val key = if (anchor != null) "$resolved#$anchor" else resolved
                            tocLabels[key] = label
                        }
                    }
                }
            }

            // EPUB3: nav document is the manifest item with properties="nav".
            //    It uses <nav epub:type="toc"> with nested <li><a href="..."> lists.
            if (tocLabels.isEmpty()) {
                val navEntry = manifest.entries.firstOrNull { it.value.properties.contains("nav") }
                if (navEntry != null) {
                    val navHref = navEntry.value.href
                    val navPath = resolve(opfDir, navHref)
                    val navZipEntry = zip.getEntry(navPath) ?: zip.getEntry(navHref)
                    if (navZipEntry != null) {
                        val nav = parse(zip.getInputStream(navZipEntry))
                        val navDir = File(navPath).parent?.takeUnless { it.isBlank() } ?: ""
                        // Collect all anchor links in the nav document's TOC list.
                        nav.documentElement.elementsByLocalName("a").forEach { a ->
                            val rawSrc = a.getAttribute("href")?.takeIf { it.isNotBlank() } ?: return@forEach
                            val label = a.textContent?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                            val srcPath = rawSrc.substringBefore('#')
                            val anchor = rawSrc.substringAfter('#', "").ifBlank { null }
                            if (srcPath.isNotBlank()) {
                                val resolved = resolve(navDir, srcPath)
                                val key = if (anchor != null) "$resolved#$anchor" else resolved
                                // Don't overwrite a label from a deeper nesting with a
                                // shallower one; first occurrence wins (top of the TOC).
                                tocLabels.putIfAbsent(key, label)
                            }
                        }
                    }
                }
            }

            // 4. Build the reading order (resolved against the OPF directory).
            //    Preserve the fragment anchor so single-file EPUBs (where every spine
            //    item points at the same HTML file with a different #id) can scroll to
            //    the right section instead of always showing the top of the file.
            val chapters = spine.mapIndexed { index, href ->
                val pathPart = href.substringBefore('#')
                val anchor = href.substringAfter('#', "").ifBlank { null }
                val path = resolve(opfDir, pathPart)
                val lookupKey = if (anchor != null) "$path#$anchor" else path
                val label = tocLabels[lookupKey] ?: tocLabels[path] ?: "Chapter ${index + 1}"
                EpubChapter(label, path, anchor)
            }

            // 5. Extract everything for rendering (chapters, images, stylesheets)
            extractDir.deleteRecursively()
            extractDir.mkdirs()
            val rootPrefix = extractDir.canonicalFile.path + File.separator
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val out = File(extractDir, entry.name).canonicalFile
                if (!out.path.startsWith(rootPrefix)) continue // guard against path traversal
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }

            return EpubBook(title, author, chapters)
        }
    }

    private fun resolve(baseDir: String, href: String): String =
        if (baseDir.isBlank()) href else "$baseDir/$href"

    private fun parse(input: java.io.InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(input)
    }

    private fun DocumentBuilderFactory.setFeatureSafely(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: Exception) {
            // Not all parsers support every feature; ignore.
        }
    }

    private fun Element.children(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? Element)?.let(out::add)
        }
        return out
    }

    private fun Element.elementsByLocalName(name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            for (c in e.children()) {
                if (c.localName == name || c.nodeName == name) out.add(c)
                walk(c)
            }
        }
        walk(this)
        return out
    }

    private fun Element.firstByLocalName(name: String): Element? =
        elementsByLocalName(name).firstOrNull()
}
