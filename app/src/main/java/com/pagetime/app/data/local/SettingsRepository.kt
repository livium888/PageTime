package com.pagetime.app.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pagetime.app.data.learning.GenerationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val browseBalanceSeconds: Long = 0,
    /** Browse seconds earned per 1 second of reading. */
    val ratio: Double = 1.0,
    val totalReadingSeconds: Long = 0
)

/** User-tunable reading comfort settings, applied to both plain-text and EPUB books. */
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineHeight: Float = 1.5f,
    /** "serif", "sans", "literata", or "mono" */
    val fontFamily: String = "serif",
    /** "light", "sepia", "dark", or "night" */
    val theme: String = "light",
    val marginDp: Float = 20f,
    /** "justify" or "left" — how both plain-text and EPUB pages align body copy. */
    val alignment: String = "justify",
    /** "off", "subtle", or "active" EPUB concept markers. */
    val conceptHints: String = "subtle",
    /** 0.15..1.0 overrides the window brightness; null means use the system setting. */
    val brightness: Float? = null
)

private fun ReaderSettings.normalized(): ReaderSettings = copy(
    fontSizeSp = fontSizeSp.coerceIn(12f, 32f),
    lineHeight = lineHeight.coerceIn(1.0f, 2.2f),
    fontFamily = fontFamily.takeIf { it in setOf("serif", "sans", "literata", "mono") } ?: "serif",
    theme = theme.takeIf { it in setOf("light", "sepia", "dark", "night") } ?: "light",
    marginDp = marginDp.coerceIn(8f, 48f),
    alignment = if (alignment == "justify") "justify" else "left",
    conceptHints = conceptHints.takeIf { it in setOf("off", "subtle", "active") } ?: "subtle",
    brightness = brightness?.coerceIn(0.15f, 1f)
)

data class PendingReaderSource(val locatorJson: String?, val fraction: Float?)

data class MapMoment(
    val bookId: String,
    val chapterIndex: Int,
    val conceptCount: Int,
    val relationshipCount: Int,
    val featuredConcept: String?,
    val featuredRelationship: String?,
    val createdAt: Long
)

class SettingsRepository(private val context: Context) {

    private val securePreferences by lazy {
        runCatching { createSecurePreferences() }
            .getOrElse {
                // Android can invalidate the keystore after restore or a security
                // update. Recover only the encrypted AI-preferences file; never
                // touch Room, books, progress, or learning history.
                context.deleteSharedPreferences(SECURE_PREFERENCES_NAME)
                createSecurePreferences()
            }
    }

    private fun createSecurePreferences(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private object Keys {
        val BALANCE = longPreferencesKey("browse_balance_seconds")
        val RATIO = doublePreferencesKey("ratio")
        val TOTAL_READING = longPreferencesKey("total_reading_seconds")
        val AI_ANALYSIS_LEVEL = stringPreferencesKey("ai_analysis_level")
        val GENERATION_MODE = stringPreferencesKey("generation_mode")

        val FONT_SIZE = floatPreferencesKey("reader_font_size")
        val LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val THEME = stringPreferencesKey("reader_theme")
        val MARGIN = floatPreferencesKey("reader_margin")
        val ALIGNMENT = stringPreferencesKey("reader_alignment")
        val CONCEPT_HINTS = stringPreferencesKey("reader_concept_hints")
        val BRIGHTNESS = floatPreferencesKey("reader_brightness")

        /** Id of the book whose position was saved most recently — drives "continue reading". */
        val LAST_READ_BOOK = stringPreferencesKey("last_read_book_id")

        /** Wall-clock time of the last UsageStats reconciliation sweep (0 = never). */
        val LAST_USAGE_RECONCILE = longPreferencesKey("last_usage_reconcile_at")

        val MAP_MOMENT_BOOK = stringPreferencesKey("map_moment_book")
        val MAP_MOMENT_CHAPTER = intPreferencesKey("map_moment_chapter")
        val MAP_MOMENT_CONCEPTS = intPreferencesKey("map_moment_concepts")
        val MAP_MOMENT_RELATIONSHIPS = intPreferencesKey("map_moment_relationships")
        val MAP_MOMENT_FEATURED_CONCEPT = stringPreferencesKey("map_moment_featured_concept")
        val MAP_MOMENT_FEATURED_RELATIONSHIP = stringPreferencesKey("map_moment_featured_relationship")
        val MAP_MOMENT_CREATED_AT = longPreferencesKey("map_moment_created_at")
    }

    private companion object {
        const val SECURE_PREFERENCES_NAME = "secure_settings"
    }

    private object SecureKeys {
        const val GEMINI_API_KEY = "gemini_api_key"
        const val GEMINI_MODEL = "gemini_model"
    }

    fun geminiApiKey(): String? = securePreferences
        .getString(SecureKeys.GEMINI_API_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun setGeminiApiKey(value: String) {
        securePreferences.edit().putString(SecureKeys.GEMINI_API_KEY, value.trim()).apply()
    }

    fun clearGeminiApiKey() {
        securePreferences.edit().remove(SecureKeys.GEMINI_API_KEY).apply()
    }

    fun geminiModel(): String = securePreferences
        .getString(SecureKeys.GEMINI_MODEL, "gemini-2.5-flash")
        ?.removePrefix("models/")
        ?.takeIf { it.isNotBlank() }
        ?: "gemini-2.5-flash"

    fun setGeminiModel(value: String) {
        securePreferences.edit()
            .putString(SecureKeys.GEMINI_MODEL, value.removePrefix("models/").trim())
            .apply()
    }

    /** The book to resume on re-entry; null until the user has read something. */
    suspend fun lastReadBookId(): String? {
        val id = context.dataStore.data.first()[Keys.LAST_READ_BOOK]
        return id?.takeIf { it.isNotBlank() }
    }

    suspend fun setLastReadBookId(id: String) {
        context.dataStore.edit { it[Keys.LAST_READ_BOOK] = id }
    }

    val lastMapMoment: Flow<MapMoment?> = context.dataStore.data.map { p ->
        val bookId = p[Keys.MAP_MOMENT_BOOK] ?: return@map null
        MapMoment(
            bookId = bookId,
            chapterIndex = p[Keys.MAP_MOMENT_CHAPTER] ?: 0,
            conceptCount = p[Keys.MAP_MOMENT_CONCEPTS] ?: 0,
            relationshipCount = p[Keys.MAP_MOMENT_RELATIONSHIPS] ?: 0,
            featuredConcept = p[Keys.MAP_MOMENT_FEATURED_CONCEPT],
            featuredRelationship = p[Keys.MAP_MOMENT_FEATURED_RELATIONSHIP],
            createdAt = p[Keys.MAP_MOMENT_CREATED_AT] ?: 0L
        )
    }

    suspend fun saveMapMoment(moment: MapMoment) {
        context.dataStore.edit {
            it[Keys.MAP_MOMENT_BOOK] = moment.bookId
            it[Keys.MAP_MOMENT_CHAPTER] = moment.chapterIndex
            it[Keys.MAP_MOMENT_CONCEPTS] = moment.conceptCount
            it[Keys.MAP_MOMENT_RELATIONSHIPS] = moment.relationshipCount
            it.remove(Keys.MAP_MOMENT_FEATURED_CONCEPT)
            it.remove(Keys.MAP_MOMENT_FEATURED_RELATIONSHIP)
            moment.featuredConcept?.let { value -> it[Keys.MAP_MOMENT_FEATURED_CONCEPT] = value }
            moment.featuredRelationship?.let { value -> it[Keys.MAP_MOMENT_FEATURED_RELATIONSHIP] = value }
            it[Keys.MAP_MOMENT_CREATED_AT] = moment.createdAt
        }
    }

    /** Exact reading position of a book as a Readium Locator JSON string. */
    suspend fun savedLocator(bookId: String): String? {
        val json = context.dataStore.data.first()[locatorKey(bookId)]
        return json?.takeIf { it.isNotBlank() }
    }

    suspend fun saveLocator(bookId: String, json: String) {
        context.dataStore.edit { it[locatorKey(bookId)] = json }
    }

    suspend fun savedTextOffset(bookId: String): Int? =
        context.dataStore.data.first()[textOffsetKey(bookId)]

    suspend fun saveTextOffset(bookId: String, offset: Int) {
        context.dataStore.edit { it[textOffsetKey(bookId)] = offset.coerceAtLeast(0) }
    }

    private fun textOffsetKey(bookId: String) =
        intPreferencesKey("text_offset_$bookId")

    private fun locatorKey(bookId: String) =
        stringPreferencesKey("locator_$bookId")

    private fun bookmarkLocatorKey(bookId: String) =
        stringPreferencesKey("bookmark_locator_$bookId")

    private fun bookmarkScrollKey(bookId: String) =
        floatPreferencesKey("bookmark_scroll_$bookId")

    private fun pendingLocatorKey(bookId: String) =
        stringPreferencesKey("pending_reader_locator_$bookId")

    private fun pendingFractionKey(bookId: String) =
        floatPreferencesKey("pending_reader_fraction_$bookId")

    suspend fun setPendingReaderSource(bookId: String, source: PendingReaderSource) {
        context.dataStore.edit {
            it.remove(pendingLocatorKey(bookId))
            it.remove(pendingFractionKey(bookId))
            source.locatorJson?.let { value -> it[pendingLocatorKey(bookId)] = value }
            source.fraction?.let { value -> it[pendingFractionKey(bookId)] = value.coerceIn(0f, 1f) }
        }
    }

    suspend fun consumePendingReaderSource(bookId: String): PendingReaderSource? {
        var source: PendingReaderSource? = null
        context.dataStore.edit { preferences ->
            val locator = preferences[pendingLocatorKey(bookId)]
            val fraction = preferences[pendingFractionKey(bookId)]
            if (locator != null || fraction != null) {
                source = PendingReaderSource(locator, fraction)
            }
            preferences.remove(pendingLocatorKey(bookId))
            preferences.remove(pendingFractionKey(bookId))
        }
        return source
    }

    suspend fun savedBookmarkLocator(bookId: String): String? =
        context.dataStore.data.first()[bookmarkLocatorKey(bookId)]?.takeIf { it.isNotBlank() }

    suspend fun saveBookmarkLocator(bookId: String, json: String) {
        context.dataStore.edit { it[bookmarkLocatorKey(bookId)] = json }
    }

    suspend fun savedBookmarkScroll(bookId: String): Float? =
        context.dataStore.data.first()[bookmarkScrollKey(bookId)]

    suspend fun saveBookmarkScroll(bookId: String, fraction: Float) {
        context.dataStore.edit { it[bookmarkScrollKey(bookId)] = fraction.coerceIn(0f, 1f) }
    }

    suspend fun clearBookmark(bookId: String) {
        context.dataStore.edit {
            it.remove(bookmarkLocatorKey(bookId))
            it.remove(bookmarkScrollKey(bookId))
        }
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            browseBalanceSeconds = p[Keys.BALANCE] ?: 0L,
            ratio = p[Keys.RATIO] ?: 1.0,
            totalReadingSeconds = p[Keys.TOTAL_READING] ?: 0L
        )
    }

    val aiSettings: Flow<AiSettings> = context.dataStore.data.map { p ->
        AiSettings(
            analysisLevel = AiAnalysisLevel.fromKey(p[Keys.AI_ANALYSIS_LEVEL]),
            generationMode = GenerationMode.fromKey(p[Keys.GENERATION_MODE])
        )
    }

    suspend fun generationMode(): GenerationMode =
        context.dataStore.data.first()[Keys.GENERATION_MODE]
            ?.let(GenerationMode::fromKey)
            ?: GenerationMode.GEMINI_FIRST

    suspend fun setGenerationMode(mode: GenerationMode) {
        context.dataStore.edit { it[Keys.GENERATION_MODE] = mode.key }
    }

    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        ReaderSettings(
            fontSizeSp = p[Keys.FONT_SIZE] ?: 18f,
            lineHeight = p[Keys.LINE_HEIGHT] ?: 1.5f,
            fontFamily = p[Keys.FONT_FAMILY] ?: "serif",
            theme = p[Keys.THEME] ?: "light",
            marginDp = p[Keys.MARGIN] ?: 20f,
            alignment = p[Keys.ALIGNMENT] ?: "justify",
            conceptHints = p[Keys.CONCEPT_HINTS] ?: "subtle",
            brightness = p[Keys.BRIGHTNESS]
        ).normalized()
    }

    suspend fun browseBalanceSeconds(): Long =
        context.dataStore.data.first()[Keys.BALANCE] ?: 0L

    suspend fun ratio(): Double =
        context.dataStore.data.first()[Keys.RATIO] ?: 1.0

    suspend fun setBrowseBalanceSeconds(value: Long) {
        context.dataStore.edit { it[Keys.BALANCE] = value.coerceAtLeast(0L) }
    }

    suspend fun addBrowseBalanceSeconds(delta: Long) {
        context.dataStore.edit { p ->
            p[Keys.BALANCE] = ((p[Keys.BALANCE] ?: 0L) + delta).coerceAtLeast(0L)
        }
    }

    suspend fun addTotalReadingSeconds(delta: Long) {
        context.dataStore.edit { p ->
            p[Keys.TOTAL_READING] = (p[Keys.TOTAL_READING] ?: 0L) + delta
        }
    }

    suspend fun setRatio(value: Double) {
        context.dataStore.edit { it[Keys.RATIO] = value.coerceIn(0.1, 10.0) }
    }

    suspend fun setAiAnalysisLevel(level: AiAnalysisLevel) {
        context.dataStore.edit { it[Keys.AI_ANALYSIS_LEVEL] = level.key }
    }

    /** Wall-clock time of the last UsageStats reconciliation sweep, or null on first run. */
    suspend fun lastUsageReconcileAt(): Long? =
        context.dataStore.data.first()[Keys.LAST_USAGE_RECONCILE]

    val lastUsageReconcileAt: Flow<Long?> = context.dataStore.data.map { p ->
        p[Keys.LAST_USAGE_RECONCILE]
    }

    suspend fun setLastUsageReconcileAt(value: Long) {
        context.dataStore.edit { it[Keys.LAST_USAGE_RECONCILE] = value }
    }

    suspend fun setReaderSettings(value: ReaderSettings) {
        val normalized = value.normalized()
        context.dataStore.edit {
            it[Keys.FONT_SIZE] = normalized.fontSizeSp
            it[Keys.LINE_HEIGHT] = normalized.lineHeight
            it[Keys.FONT_FAMILY] = normalized.fontFamily
            it[Keys.THEME] = normalized.theme
            it[Keys.MARGIN] = normalized.marginDp
            it[Keys.ALIGNMENT] = normalized.alignment
            it[Keys.CONCEPT_HINTS] = normalized.conceptHints
            if (normalized.brightness == null) {
                it.remove(Keys.BRIGHTNESS)
            } else {
                it[Keys.BRIGHTNESS] = normalized.brightness!!
            }
        }
    }

    suspend fun setFontSize(value: Float) {
        setReaderSettings(readerSettings.first().copy(fontSizeSp = value))
    }

    suspend fun setLineHeight(value: Float) {
        setReaderSettings(readerSettings.first().copy(lineHeight = value))
    }

    suspend fun setFontFamily(value: String) {
        setReaderSettings(readerSettings.first().copy(fontFamily = value))
    }

    suspend fun setTheme(value: String) {
        setReaderSettings(readerSettings.first().copy(theme = value))
    }

    suspend fun setMargin(value: Float) {
        setReaderSettings(readerSettings.first().copy(marginDp = value))
    }

    /** Saves a reader brightness override (null restores the system setting). */
    suspend fun setReaderBrightness(value: Float?) {
        context.dataStore.edit { p ->
            if (value == null) {
                p.remove(Keys.BRIGHTNESS)
            } else {
                p[Keys.BRIGHTNESS] = value.coerceIn(0.15f, 1f)
            }
        }
    }
}
