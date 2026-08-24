package com.pagetime.app.data

import android.content.Context
import android.os.PowerManager
import androidx.room.Room
import com.pagetime.app.blocker.BlockController
import com.pagetime.app.data.download.BookDownloader
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.internetarchive.InternetArchiveApi
import com.pagetime.app.data.library.EpubParser
import com.pagetime.app.data.openlibrary.OpenLibraryApi
import com.pagetime.app.data.standardebooks.StandardEbooksApi
import com.pagetime.app.data.local.AppDatabase
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.LearningContextExtractor
import com.pagetime.app.data.usage.ForegroundParser
import com.pagetime.app.data.usage.UsageReconciler
import com.pagetime.app.data.usage.UsageStatsReader
import com.pagetime.app.domain.BalanceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/** Simple manual DI container, owned by the Application. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * App-lifetime scope for critical background writes (reading position, earned
     * seconds). ViewModel scopes are cancelled the instant a screen is left, which
     * silently dropped those writes — anything that MUST survive navigation goes here.
     *
     * limitedParallelism(1) makes writes SERIAL: position saves are launched from
     * several places (checkpoint, chapter change, exit) and on a multi-threaded
     * dispatcher a stale save could land AFTER a newer one and clobber it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )

    val database: AppDatabase =
        Room.databaseBuilder(appContext, AppDatabase::class.java, "pagetime.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()

    private val bookDao = database.bookDao()
    private val blockedAppDao = database.blockedAppDao()
    private val usageEventDao = database.usageEventDao()
    private val learningCardDao = database.learningCardDao()
    private val learningReviewLogDao = database.learningReviewLogDao()
    private val learningGenerationDao = database.learningGenerationDao()

    val settingsRepository = SettingsRepository(appContext)
    val readiumEngine = ReadiumEngine(appContext)
    val gutenbergApi = GutenbergApi()
    val internetArchiveApi = InternetArchiveApi()
    val openLibraryApi = OpenLibraryApi()
    val standardEbooksApi = StandardEbooksApi()
    val epubParser = EpubParser()

    val libraryRepository = LibraryRepository(
        bookDao = bookDao,
        downloader = BookDownloader(appContext),
        gutenbergApi = gutenbergApi,
        internetArchiveApi = internetArchiveApi,
        openLibraryApi = openLibraryApi,
        standardEbooksApi = standardEbooksApi,
        epubParser = epubParser,
        settingsRepository = settingsRepository,
        context = appContext
    )

    val blockedAppRepository = BlockedAppRepository(blockedAppDao)

    val usageRepository = UsageRepository(usageEventDao)

    val balanceManager = BalanceManager(settingsRepository, usageRepository)

    val geminiLearningClient = GeminiLearningClient(settingsRepository)
    val learningContextExtractor = LearningContextExtractor(appContext, epubParser)

    val learningRepository = LearningRepository(
        database = database,
        cardDao = learningCardDao,
        reviewLogDao = learningReviewLogDao,
        generationDao = learningGenerationDao,
        bookDao = bookDao,
        settingsRepository = settingsRepository,
        geminiClient = geminiLearningClient,
        contextExtractor = learningContextExtractor
    )

    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    val blockController = BlockController(
        scope = scope,
        settingsRepository = settingsRepository,
        blockedAppRepository = blockedAppRepository,
        balanceManager = balanceManager,
        usageRepository = usageRepository,
        powerManager = powerManager
    )

    /** UsageStats audit: charges blocked-app time even if our service was dead. */
    val usageStatsReader = UsageStatsReader(appContext)
    val usageReconciler = UsageReconciler(
        scope = scope,
        settingsRepository = settingsRepository,
        blockedAppRepository = blockedAppRepository,
        usageRepository = usageRepository,
        balanceManager = balanceManager,
        blockController = blockController,
        reader = usageStatsReader,
        parser = ForegroundParser()
    )

    init {
        blockController.start()
        usageReconciler.start()
    }
}
