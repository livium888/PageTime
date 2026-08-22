package com.pagetime.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.pagetime.app.data.AppContainer
import com.pagetime.app.data.AppHttp

class PageTimeApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Cover images go through the same resilient HTTP client as the catalog. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { AppHttp.newClient(callTimeoutSeconds = 60L) }
            .crossfade(true)
            .build()
}
