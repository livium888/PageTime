package com.pagetime.app.data

import android.content.Context
import androidx.room.Room
import com.pagetime.app.blocker.BlockController
import com.pagetime.app.data.download.BookDownloader
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.library.EpubParser
import com.pagetime.app.data.openlibrary.OpenLibraryApi
import com.pagetime.app.data.standardebooks.StandardEbooksApi
import com.pagetime.app.data.local.AppDatabase
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.domain.BalanceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual DI container, owned by the Application. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * App-lifetime scope for critical background writes (reading position, earned
     * seconds). ViewModel scopes are cancelled the instant a screen is left, which
     * silently dropped those writes — anything that MUST survive navigation goes here.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase =
        Room.databaseBuilder(appContext, AppDatabase::class.java, "pagetime.db").build()

    private val bookDao = database.bookDao()
    private val blockedAppDao = database.blockedAppDao()

    val settingsRepository = SettingsRepository(appContext)
    val gutenbergApi = GutenbergApi()
    val openLibraryApi = OpenLibraryApi()
    val standardEbooksApi = StandardEbooksApi()
    val epubParser = EpubParser()

    val libraryRepository = LibraryRepository(
        bookDao = bookDao,
        downloader = BookDownloader(appContext),
        gutenbergApi = gutenbergApi,
        openLibraryApi = openLibraryApi,
        standardEbooksApi = standardEbooksApi,
        epubParser = epubParser,
        context = appContext
    )

    val blockedAppRepository = BlockedAppRepository(blockedAppDao)

    val balanceManager = BalanceManager(settingsRepository)

    val blockController = BlockController(scope, settingsRepository, blockedAppRepository)

    init {
        blockController.start()
    }
}
