package com.pagetime.app.data

import android.content.Context
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser

/**
 * Shared Readium objects. Readium is the open-source EPUB engine that renders,
 * paginates and tracks position inside books — replacing the hand-rolled WebView
 * reader with the same toolkit real reading apps are built on.
 *
 * We only support EPUB, so [EpubParser] is wired directly as the publication parser
 * (Readium 3.0.0 has no DefaultPublicationParser yet — that arrived in later versions).
 */
class ReadiumEngine(context: Context) {

    private val httpClient = DefaultHttpClient()

    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)

    val publicationOpener = PublicationOpener(
        publicationParser = EpubParser()
    )
}
