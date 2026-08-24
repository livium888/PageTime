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
    /** "serif", "sans", or "mono" */
    val fontFamily: String = "serif",
    /** "light", "sepia", "dark", or "night" */
    val theme: String = "light",
    val marginDp: Float = 20f,
    /** 0.15..1.0 overrides the window brightness; null means use the system setting. */
    val brightness: Float? = null
)

data class PendingReaderSource(val locatorJson: String?, val fraction: Float?)

class SettingsRepository(private val context: Context) {

    private val securePreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private object Keys {
        val BALANCE = longPreferencesKey("browse_balance_seconds")
        val RATIO = doublePreferencesKey("ratio")
        val TOTAL_READING = longPreferencesKey("total_reading_seconds")

        val FONT_SIZE = floatPreferencesKey("reader_font_size")
        val LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val THEME = stringPreferencesKey("reader_theme")
        val MARGIN = floatPreferencesKey("reader_margin")
        val BRIGHTNESS = floatPreferencesKey("reader_brightness")

        /** Id of the book whose position was saved most recently — drives "continue reading". */
        val LAST_READ_BOOK = stringPreferencesKey("last_read_book_id")

        /** Wall-clock time of the last UsageStats reconciliation sweep (0 = never). */
        val LAST_USAGE_RECONCILE = longPreferencesKey("last_usage_reconcile_at")
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

    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        ReaderSettings(
            fontSizeSp = p[Keys.FONT_SIZE] ?: 18f,
            lineHeight = p[Keys.LINE_HEIGHT] ?: 1.5f,
            fontFamily = p[Keys.FONT_FAMILY] ?: "serif",
            theme = p[Keys.THEME] ?: "light",
            marginDp = p[Keys.MARGIN] ?: 20f,
            brightness = p[Keys.BRIGHTNESS]?.coerceIn(0.15f, 1f)
        )
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
        context.dataStore.edit {
            it[Keys.FONT_SIZE] = value.fontSizeSp.coerceIn(12f, 32f)
            it[Keys.LINE_HEIGHT] = value.lineHeight.coerceIn(1.0f, 2.2f)
            it[Keys.FONT_FAMILY] = value.fontFamily
            it[Keys.THEME] = value.theme
            it[Keys.MARGIN] = value.marginDp.coerceIn(8f, 48f)
            if (value.brightness == null) {
                it.remove(Keys.BRIGHTNESS)
            } else {
                it[Keys.BRIGHTNESS] = value.brightness.coerceIn(0.15f, 1f)
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
}
