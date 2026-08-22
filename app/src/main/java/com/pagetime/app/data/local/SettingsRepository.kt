package com.pagetime.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val marginDp: Float = 20f
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BALANCE = longPreferencesKey("browse_balance_seconds")
        val RATIO = doublePreferencesKey("ratio")
        val TOTAL_READING = longPreferencesKey("total_reading_seconds")

        val FONT_SIZE = floatPreferencesKey("reader_font_size")
        val LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val THEME = stringPreferencesKey("reader_theme")
        val MARGIN = floatPreferencesKey("reader_margin")

        /** Id of the book whose position was saved most recently — drives "continue reading". */
        val LAST_READ_BOOK = stringPreferencesKey("last_read_book_id")
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

    private fun locatorKey(bookId: String) =
        stringPreferencesKey("locator_$bookId")

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
            marginDp = p[Keys.MARGIN] ?: 20f
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

    suspend fun setFontSize(value: Float) {
        context.dataStore.edit { it[Keys.FONT_SIZE] = value.coerceIn(12f, 32f) }
    }

    suspend fun setLineHeight(value: Float) {
        context.dataStore.edit { it[Keys.LINE_HEIGHT] = value.coerceIn(1.0f, 2.2f) }
    }

    suspend fun setFontFamily(value: String) {
        context.dataStore.edit { it[Keys.FONT_FAMILY] = value }
    }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[Keys.THEME] = value }
    }

    suspend fun setMargin(value: Float) {
        context.dataStore.edit { it[Keys.MARGIN] = value.coerceIn(8f, 48f) }
    }
}
