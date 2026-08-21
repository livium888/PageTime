package com.pagetime.app

import android.app.Application
import com.pagetime.app.data.AppContainer

class PageTimeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
