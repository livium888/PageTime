package com.pagetime.app.data

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.room.Room
import com.pagetime.app.blocker.BlockController
import com.pagetime.app.data.download.BookDownloader
import com.pagetime.app.data.gutenberg.GutenbergApi
import com.pagetime.app.data.library.EpubParser
import com.pagetime.app.data.local.AppDatabase
import com.pagetime.app.data.local.SettingsRepository
import com.pagetime.app.data.youtube.YouTubeSearchApi
import com.pagetime.app.data.learning.GeminiLearningClient
import com.pagetime.app.data.learning.LearningContextExtractor
import com.pagetime.app.data.embed.BookIndexer
import com.pagetime.app.data.embed.BookSearcher
import com.pagetime.app.data.learning.ChapterPromptGenerator
import com.pagetime.app.data.embed.CardEmbeddingIndexer
import com.pagetime.app.data.embed.EmbeddingModelStore
import com.pagetime.app.data.usage.ForegroundParser
import com.pagetime.app.data.usage.UsageReconciler
import com.pagetime.app.data.usage.UsageStatsReader
import com.pagetime.app.domain.BalanceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19
            )
            .build()

    private val bookDao = database.bookDao()
    private val blockedAppDao = database.blockedAppDao()
    private val usageEventDao = database.usageEventDao()
    private val learningGenerationDao = database.learningGenerationDao()
    private val conceptDao = database.conceptDao()
    private val conceptRelationshipDao = database.conceptRelationshipDao()
    private val aiUsageDao = database.aiUsageDao()
    private val explanationDao = database.explanationDao()

    val settingsRepository = SettingsRepository(appContext)
    val aiUsageRepository = AiUsageRepository(aiUsageDao)
    val readiumEngine = ReadiumEngine(appContext)
    val gutenbergApi = GutenbergApi()
    /** The catalogues Discover offers, as a list rather than a switch. */
    val bookCatalogs = com.pagetime.app.data.catalog.BookCatalogs(
        gutenberg = gutenbergApi,
    )
    val epubParser = EpubParser()
    val youtubeSearchApi = YouTubeSearchApi()

    val libraryRepository = LibraryRepository(
        bookDao = bookDao,
        downloader = BookDownloader(appContext),
        epubParser = epubParser,
        settingsRepository = settingsRepository,
        context = appContext,
        aiUsageRepository = aiUsageRepository
    )

    val blockedAppRepository = BlockedAppRepository(blockedAppDao)

    val usageRepository = UsageRepository(usageEventDao)

    val balanceManager = BalanceManager(settingsRepository, usageRepository)

    val geminiLearningClient = GeminiLearningClient(settingsRepository)
    val learningContextExtractor = LearningContextExtractor(appContext, epubParser)

    /** Optional on-device LLM: weights downloaded on demand, never bundled. */
    val lumenModelStore =
        LumenModelStore(
            directory = File(appContext.filesDir, "lumen-model"),
            downloader = OkHttpLumenModelDownloader(),
            remoteInfoFetcher = HfModelRemoteInfoFetcher::fetch,
            urlProvider = { settingsRepository.lumenModelUrl() ?: LumenModelStore.MODEL_URL },
        )
    val localLlmProvider = MediaPipeLlmProvider(appContext, lumenModelStore)

    /**
     * The retrieval model: separate weights, separate directory, separate
     * lifecycle from the language model. A reader can have either, both, or
     * neither, and deleting one must not disturb the other.
     */
    val embeddingModelStore =
        EmbeddingModelStore(
            directory = File(appContext.filesDir, "embedding-model"),
            downloader = OkHttpLumenModelDownloader(),
            source = { EmbeddingModelStore.DEFAULT_SOURCE },
        )

    val cardEmbeddingIndexer =
        CardEmbeddingIndexer(
            embeddingDao = database.cardEmbeddingDao(),
            store = embeddingModelStore,
        )

    /**
     * Book text as vectors, for finding a passage by what it means.
     *
     * Chapter access comes in as two functions rather than the extractor
     * itself: the indexer's job is chunk, embed, store, and it has no business
     * knowing what an EPUB is.
     */
    val bookIndexer =
        BookIndexer(
            dao = database.bookChunkEmbeddingDao(),
            store = embeddingModelStore,
            chapterCount = { book -> learningContextExtractor.chapterCount(book) },
            chapterText = { book, chapter ->
                learningContextExtractor.chapterText(book, chapter)
            },
        )

    /** The other half of the index: asking it a question. */
    val bookSearcher =
        BookSearcher(
            dao = database.bookChunkEmbeddingDao(),
            store = embeddingModelStore,
        )

    /**
     * Chapter flashcards: the vectors choose the passages, Gemini writes the
     * questions, and the rules decide which of them the reader is offered.
     */
    val chapterPromptGenerator =
        ChapterPromptGenerator(
            chunkDao = database.bookChunkEmbeddingDao(),
            cardDao = database.learningCardDao(),
            store = embeddingModelStore,
            gemini = geminiLearningClient,
            usage = aiUsageRepository,
        )

    val lumenRepository = LumenRepository(
        dao = database.lumenCardDao(),
        geminiClient = geminiLearningClient,
        aiUsageRepository = aiUsageRepository,
        bookDao = bookDao,
        settingsRepository = settingsRepository,
        localLlmProvider = localLlmProvider,
        debugLog = { message -> Log.d("LumenDraft", message) },
        modelStore = { lumenModelStore },
        captureDiagContext = { appContext },
        // Fire and forget, on the container's own scope rather than a screen's:
        // a card saved and then navigated away from still gets its vector, and
        // a save never waits on 22 MB of ONNX.
        onCardTextChanged = { card ->
            scope.launch { runCatching { cardEmbeddingIndexer.index(listOf(card)) } }
        },
    )

    val glossRepository = GlossRepository(
        geminiClient = geminiLearningClient,
        settingsRepository = settingsRepository,
        localLlmProvider = localLlmProvider,
        aiUsageRepository = aiUsageRepository,
    )

    val conceptMapRepository = ConceptMapRepository(
        database = database,
        conceptDao = conceptDao,
        relationshipDao = conceptRelationshipDao,
        bookDao = bookDao,
        generationDao = learningGenerationDao,
        contextExtractor = learningContextExtractor,
        geminiClient = geminiLearningClient,
        settingsRepository = settingsRepository,
        aiUsageRepository = aiUsageRepository
    )

    val explainBackRepository = ExplainBackRepository(
        conceptDao = conceptDao,
        explanationDao = explanationDao,
        geminiClient = geminiLearningClient,
        contextExtractor = learningContextExtractor,
        settingsRepository = settingsRepository,
        localLlmProvider = localLlmProvider,
        aiUsageRepository = aiUsageRepository
    )

    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    val blockController = BlockController(
        scope = scope,
        settingsRepository = settingsRepository,
        blockedAppRepository = blockedAppRepository,
        balanceManager = balanceManager,
        usageRepository = usageRepository,
        powerManager = powerManager,
        selfPackage = appContext.packageName
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
