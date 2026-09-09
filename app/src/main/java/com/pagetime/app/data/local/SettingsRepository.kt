package com.pagetime.app.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.pagetime.app.data.LumenCapture
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pagetime.app.data.LlmProviderKind
import com.pagetime.app.data.learning.GenerationMode
import com.pagetime.app.blocker.BlockEnforcementPolicy
import com.pagetime.app.domain.EmergencyUnlock
import com.pagetime.app.domain.GateState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val browseBalanceSeconds: Long = 0,
    /** Browse seconds earned per 1 second of reading. */
    val ratio: Double = 1.0,
    val totalReadingSeconds: Long = 0,
    /** Wall-clock time (epoch millis) until the temporary "block paused" grace ends (0 = none). */
    val quickDisableUntil: Long = 0,
    /** Wall-clock time (epoch millis) until the non-cancellable hard lock ends (0 = none). */
    val hardLockUntil: Long = 0,
    /** The one app an emergency unlock is currently covering, if any. */
    val emergencyPackage: String? = null,
    /** When that unlock expires (epoch millis); 0 = none. */
    val emergencyUntil: Long = 0,
    /** When the recent emergency unlocks were spent, newest first. */
    val emergencyUses: List<Long> = emptyList(),
    /**
     * Whether blocked apps are governed by the access gate rather than the
     * browse balance.
     *
     * Off by default, including for readers upgrading into it. The gate is a
     * much sharper instrument than the ratio it replaces — two hours or
     * nothing — and switching someone into it without their say-so would lock
     * them out of their phone on the strength of an app update.
     */
    val gateEnabled: Boolean = false,
    /** Reading banked toward the next session. */
    val readingCreditSeconds: Long = 0,
    /** App time bought and not yet used, in seconds. */
    val sessionSecondsRemaining: Long = 0,
    /** When the cooling-off finishes and the gate switches off; 0 = not winding down. */
    val gateDisableAt: Long = 0,
    /** Reading needed to buy one session. */
    val sessionCostSeconds: Long = GateState.DEFAULT_SESSION_COST_SECONDS,
    /** How long a session lasts once opened. */
    val sessionLengthSeconds: Long = GateState.DEFAULT_SESSION_LENGTH_SECONDS,
    /** Whether the slip box shows newcomer help / confirmations before card actions. */
    val helpEnabled: Boolean = true,
    /** Provider used for optional AI-assisted learning features. */
    val llmProvider: LlmProviderKind = LlmProviderKind.GEMINI
)

/** User-tunable reading comfort settings, applied to both plain-text and EPUB books. */
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineHeight: Float = 1.5f,
    /** "serif", "sans", "literata", or "mono" */
    val fontFamily: String = "serif",
    /** "paper", "light", "sepia", "dark", or "night" */
    val theme: String = "light",
    val marginDp: Float = 20f,
    /** "justify" or "left" — how both plain-text and EPUB pages align body copy. */
    val alignment: String = "justify",
    /** "off", "subtle", or "active" EPUB concept markers. */
    val conceptHints: String = "subtle",
    /** 0.15..1.0 overrides the window brightness; null means use the system setting. */
    val brightness: Float? = null,
    /**
     * How much amber sits over the page, 0..1.
     *
     * Paper indoors is lit by something warm; screens ship at about 6500K,
     * which is daylight. A little amber is most of what separates "a page
     * under a lamp" from "a monitor".
     */
    val warmth: Float = 0f,
    /**
     * Extra dimming beyond the system minimum, 0..1.
     *
     * Android's lowest backlight is still too bright to read against in a dark
     * room, and no app can lower the backlight past it. What an app CAN do is
     * lay a black veil over its own window, which is what this is. It is the
     * single most effective thing available for making a screen stop reading
     * as a light source at night.
     */
    val nightDim: Float = 0f,
    /**
     * Volume down turns forward, volume up turns back.
     *
     * Off by default: the volume keys belong to the system until the reader is
     * told otherwise, and someone who never opens the appearance sheet should
     * never discover their volume buttons doing something else.
     */
    val volumeKeysTurnPages: Boolean = false,
) {
    companion object {
        /**
         * The densest the night veil may ever be.
         *
         * It is drawn OVER the page, so a value near 1 renders a black
         * rectangle with the text invisible beneath it — and the slider that
         * would undo it is behind that same rectangle. One constant, used by
         * both the clamp and the slider's range, so the two cannot drift.
         */
        const val MAX_NIGHT_DIM = 0.8f
    }
}

internal fun ReaderSettings.normalized(): ReaderSettings = copy(
    fontSizeSp = fontSizeSp.coerceIn(12f, 32f),
    lineHeight = lineHeight.coerceIn(1.0f, 2.2f),
    fontFamily = fontFamily.takeIf { it in setOf("serif", "sans", "literata", "mono") } ?: "serif",
    theme = theme.takeIf { it in setOf("paper", "light", "sepia", "dark", "night") } ?: "light",
    marginDp = marginDp.coerceIn(8f, 48f),
    warmth = warmth.coerceIn(0f, 1f),
    // Capped well short of 1: a veil dense enough to hide the text would look
    // exactly like a broken screen, and there would be no way to find the
    // control to undo it.
    nightDim = nightDim.coerceIn(0f, ReaderSettings.MAX_NIGHT_DIM),
    alignment = if (alignment == "justify") "justify" else "left",
    conceptHints = conceptHints.takeIf { it in setOf("off", "subtle", "active") } ?: "subtle",
    brightness = brightness?.coerceIn(0.15f, 1f)
)

data class PendingReaderSource(val locatorJson: String?, val fraction: Float?)

data class LearningCheckpoint(val locatorJson: String?, val textOffset: Int?, val fraction: Float?)

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
        val QUICK_DISABLE_UNTIL = longPreferencesKey("quick_disable_until")
        val HARD_LOCK_UNTIL = longPreferencesKey("hard_lock_until")
        val EMERGENCY_PACKAGE = stringPreferencesKey("emergency_unlock_package")
        val EMERGENCY_UNTIL = longPreferencesKey("emergency_unlock_until")
        val EMERGENCY_USES = stringPreferencesKey("emergency_unlock_uses")
        val GATE_ENABLED = booleanPreferencesKey("access_gate_enabled")
        val READING_CREDIT = longPreferencesKey("access_gate_reading_credit_seconds")
        val SESSION_REMAINING = longPreferencesKey("access_gate_session_seconds_remaining")
        val GATE_DISABLE_AT = longPreferencesKey("access_gate_disable_at")
        val SESSION_COST = longPreferencesKey("access_gate_session_cost_seconds")
        val SESSION_LENGTH = longPreferencesKey("access_gate_session_length_seconds")
        val METHOD_HELP_ENABLED = booleanPreferencesKey("method_help_enabled")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LUMEN_PROMPT = stringPreferencesKey("lumen_prompt_template")
        val LUMEN_MODEL_URL = stringPreferencesKey("lumen_model_url")
        val LUMEN_CLOUD_RESCUE = booleanPreferencesKey("lumen_cloud_rescue")
        val LUMEN_CAPTURE_CHARS = intPreferencesKey("lumen_capture_chars")

        val REVIEW_REMINDERS = booleanPreferencesKey("review_reminders_enabled")
        val REVIEW_REMINDERS_SNOOZED_UNTIL = longPreferencesKey("review_reminders_snoozed_until")
        val REVIEW_REMINDER_LAST_SENT = longPreferencesKey("review_reminder_last_sent")
        val REVIEW_REMINDER_STREAK = intPreferencesKey("review_reminder_streak")


        val READER_WARMTH = floatPreferencesKey("reader_warmth")
        val READER_NIGHT_DIM = floatPreferencesKey("reader_night_dim")
        val READER_VOLUME_KEYS = booleanPreferencesKey("reader_volume_keys_turn_pages")

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
        val CHECKPOINT_LOCATOR = stringPreferencesKey("learning_checkpoint_locator")
        val CHECKPOINT_OFFSET = intPreferencesKey("learning_checkpoint_offset")
        val CHECKPOINT_FRACTION = floatPreferencesKey("learning_checkpoint_fraction")

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

    suspend fun learningCheckpoint(): LearningCheckpoint? {
        val values = context.dataStore.data.first()
        val locator = values[Keys.CHECKPOINT_LOCATOR]
        val offset = values[Keys.CHECKPOINT_OFFSET]
        val fraction = values[Keys.CHECKPOINT_FRACTION]
        return if (locator != null || offset != null || fraction != null) {
            LearningCheckpoint(locator, offset, fraction)
        } else null
    }

    suspend fun saveLearningCheckpoint(checkpoint: LearningCheckpoint) {
        context.dataStore.edit {
            it.remove(Keys.CHECKPOINT_LOCATOR)
            it.remove(Keys.CHECKPOINT_OFFSET)
            it.remove(Keys.CHECKPOINT_FRACTION)
            checkpoint.locatorJson?.let { value -> it[Keys.CHECKPOINT_LOCATOR] = value }
            checkpoint.textOffset?.let { value -> it[Keys.CHECKPOINT_OFFSET] = value.coerceAtLeast(0) }
            checkpoint.fraction?.let { value -> it[Keys.CHECKPOINT_FRACTION] = value.coerceIn(0f, 1f) }
        }
    }

    suspend fun clearLearningCheckpoint() {
        context.dataStore.edit {
            it.remove(Keys.CHECKPOINT_LOCATOR)
            it.remove(Keys.CHECKPOINT_OFFSET)
            it.remove(Keys.CHECKPOINT_FRACTION)
        }
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
            totalReadingSeconds = p[Keys.TOTAL_READING] ?: 0L,
            quickDisableUntil = p[Keys.QUICK_DISABLE_UNTIL] ?: 0L,
            hardLockUntil = p[Keys.HARD_LOCK_UNTIL] ?: 0L,
            emergencyPackage = p[Keys.EMERGENCY_PACKAGE],
            emergencyUntil = p[Keys.EMERGENCY_UNTIL] ?: 0L,
            emergencyUses = EmergencyUnlock.decode(p[Keys.EMERGENCY_USES]),
            gateEnabled = p[Keys.GATE_ENABLED] ?: false,
            readingCreditSeconds = p[Keys.READING_CREDIT] ?: 0L,
            sessionSecondsRemaining = p[Keys.SESSION_REMAINING] ?: 0L,
            gateDisableAt = p[Keys.GATE_DISABLE_AT] ?: 0L,
            sessionCostSeconds = p[Keys.SESSION_COST] ?: GateState.DEFAULT_SESSION_COST_SECONDS,
            sessionLengthSeconds = p[Keys.SESSION_LENGTH] ?: GateState.DEFAULT_SESSION_LENGTH_SECONDS,
            helpEnabled = p[Keys.METHOD_HELP_ENABLED] ?: true,
            llmProvider = LlmProviderKind.fromKey(p[Keys.LLM_PROVIDER])
        )
    }

    /**
     * The reader's own capture prompt, or null while the built-in one is in
     * use. Stored only when it differs from the default, so an app update that
     * improves the built-in prompt reaches everyone who never tailored theirs.
     */
    suspend fun lumenPromptTemplate(): String? =
        context.dataStore.data.first()[Keys.LUMEN_PROMPT]?.takeIf { it.isNotBlank() }

    /**
     * Where the offline model is downloaded from, or null for the built-in one.
     *
     * Settable because the one fact that cannot be checked from a build server
     * is whether a given URL serves the file it claims to, and pinning a
     * guess into the app would make every correction cost a release.
     */
    suspend fun lumenModelUrl(): String? =
        context.dataStore.data.first()[Keys.LUMEN_MODEL_URL]?.takeIf { it.isNotBlank() }

    suspend fun setLumenModelUrl(value: String?) {
        context.dataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(Keys.LUMEN_MODEL_URL)
            else prefs[Keys.LUMEN_MODEL_URL] = value.trim()
        }
    }

    /**
     * Whether a capture that the on-device model could not deliver may be
     * retried against Gemini.
     *
     * Only reached after the offline model has actually failed — missing,
     * unloadable, or having returned something unusable. A card the model
     * produced is never silently replaced by a cloud one, however thin it is;
     * that is the reader's tap on "Rewrite with Gemini", because it spends
     * their quota and they should be the one spending it.
     *
     * Defaults to on, and only ever fires when a key is configured — which is
     * itself a deliberate act. A reader who chose offline for privacy rather
     * than for cost can turn it off and the passage never leaves the phone.
     */
    suspend fun lumenCloudRescue(): Boolean =
        context.dataStore.data.first()[Keys.LUMEN_CLOUD_RESCUE] ?: true

    suspend fun setLumenCloudRescue(value: Boolean) {
        context.dataStore.edit { it[Keys.LUMEN_CLOUD_RESCUE] = value }
    }

    /**
     * Whether the app may tell the reader when a sitting is worth having.
     *
     * Defaults to OFF. Notifications are the one feature where a wrong default
     * is not a preference the reader can shrug at — an app that starts
     * interrupting someone who never asked it to is an app they uninstall, and
     * asking first costs one tap.
     */
    suspend fun reviewReminders(): Boolean =
        context.dataStore.data.first()[Keys.REVIEW_REMINDERS] ?: false

    suspend fun setReviewReminders(value: Boolean) {
        context.dataStore.edit { it[Keys.REVIEW_REMINDERS] = value }
    }

    /** Reminders stay quiet until this instant. Orbit offers the same escape. */
    suspend fun remindersSnoozedUntil(): Long =
        context.dataStore.data.first()[Keys.REVIEW_REMINDERS_SNOOZED_UNTIL] ?: 0L

    suspend fun snoozeReminders(untilMillis: Long) {
        context.dataStore.edit { it[Keys.REVIEW_REMINDERS_SNOOZED_UNTIL] = untilMillis }
    }

    suspend fun lastReminderAt(): Long =
        context.dataStore.data.first()[Keys.REVIEW_REMINDER_LAST_SENT] ?: 0L

    /**
     * How many reminders have gone unanswered in a row.
     *
     * Drives the backoff ladder, and reset to zero the moment the reader
     * actually reviews — the ladder is about being ignored, not about elapsed
     * time.
     */
    suspend fun unansweredReminders(): Int =
        context.dataStore.data.first()[Keys.REVIEW_REMINDER_STREAK] ?: 0

    suspend fun recordReminderSent(atMillis: Long) {
        context.dataStore.edit {
            it[Keys.REVIEW_REMINDER_LAST_SENT] = atMillis
            it[Keys.REVIEW_REMINDER_STREAK] = (it[Keys.REVIEW_REMINDER_STREAK] ?: 0) + 1
        }
    }

    suspend fun clearReminderStreak() {
        context.dataStore.edit { it[Keys.REVIEW_REMINDER_STREAK] = 0 }
    }

    /**
     * How much text a capture hands the model, in characters.
     *
     * A setting rather than a constant because it is the one lever on card
     * quality that has never been measured. The failure the reader keeps
     * seeing is not bad writing — it is bad CHOOSING: a page holds four or
     * five ideas, the prompt asks for "the one that matters most", and a 1B
     * model reliably takes the most obvious event rather than the argument.
     *
     * Shrink the passage to one paragraph and there is nothing left to choose
     * between. The model only has to say the idea in front of it, which is the
     * half it can already do.
     *
     * Whether that actually works is unknown, so the number is exposed instead
     * of guessed. Three prompt rewrites were spent on this problem; none of
     * them tried giving the model less to read.
     */
    suspend fun lumenCaptureChars(): Int =
        context.dataStore.data.first()[Keys.LUMEN_CAPTURE_CHARS]
            ?: LumenCapture.PASSAGE_TARGET_CHARS

    suspend fun setLumenCaptureChars(value: Int) {
        context.dataStore.edit { it[Keys.LUMEN_CAPTURE_CHARS] = value }
    }

    suspend fun setLumenPromptTemplate(value: String?) {
        context.dataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(Keys.LUMEN_PROMPT) else prefs[Keys.LUMEN_PROMPT] = value
        }
    }

    /** Whether the slip box should explain actions before running them. */
    suspend fun setHelpEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.METHOD_HELP_ENABLED] = value }
    }

    suspend fun setLlmProvider(provider: LlmProviderKind) {
        context.dataStore.edit { it[Keys.LLM_PROVIDER] = provider.key }
    }

    suspend fun llmProvider(): LlmProviderKind =
        context.dataStore.data.first()[Keys.LLM_PROVIDER]
            ?.let(LlmProviderKind::fromKey)
            ?: LlmProviderKind.GEMINI

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
            brightness = p[Keys.BRIGHTNESS],
            warmth = p[Keys.READER_WARMTH] ?: 0f,
            nightDim = p[Keys.READER_NIGHT_DIM] ?: 0f,
            volumeKeysTurnPages = p[Keys.READER_VOLUME_KEYS] ?: false,
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

    /** Wall-clock time (epoch millis) until the temporary quick-disable grace ends (0 = none). */
    suspend fun quickDisableUntil(): Long =
        context.dataStore.data.first()[Keys.QUICK_DISABLE_UNTIL] ?: 0L

    /** Wall-clock time (epoch millis) until the non-cancellable hard lock ends (0 = none). */
    suspend fun hardLockUntil(): Long =
        context.dataStore.data.first()[Keys.HARD_LOCK_UNTIL] ?: 0L

    /**
     * Clears any legacy quick-disable grace.
     *
     * The buttons that set one were deleted; this remains because an install
     * upgrading mid-grace still carries a stored expiry. There is deliberately
     * no setter any more — a method that grants a bypass is a loaded gun left
     * on the table for whoever writes the next screen.
     */
    suspend fun clearQuickDisableUntil() {
        context.dataStore.edit { it.remove(Keys.QUICK_DISABLE_UNTIL) }
    }

    /**
     * Starts or extends a hard lock.
     *
     * NEVER SHORTENS ONE. Setting a thirty-minute lock while a three-hour lock
     * runs used to cut it to thirty minutes — the screen disables the buttons
     * so it could not be reached from there, but "a rule the UI enforces" is
     * how the settings sliders came to be a way out, and this is the same
     * shape. A lock that can be shortened is not a lock.
     *
     * There is no clearHardLockUntil. It existed, nothing called it, and a
     * public method that cancels a lock advertised as uncancellable is worse
     * than dead code: it is a hole waiting for a caller.
     */
    suspend fun setHardLockUntil(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        context.dataStore.edit { p ->
            val current = p[Keys.HARD_LOCK_UNTIL] ?: 0L
            p[Keys.HARD_LOCK_UNTIL] =
                BlockEnforcementPolicy.hardLockAfterSetting(current, epochMillis, nowMillis)
        }
    }

    /**
     * Spends one emergency unlock on [packageName]. Returns whether it opened.
     *
     * The whole decision happens inside a single DataStore edit: two taps on
     * the block screen must not both read two uses left and both spend one.
     *
     * Scoped to one package by construction — there is no call here that opens
     * everything, because the value of the hatch is entirely in how small it
     * is.
     */
    suspend fun startEmergencyUnlock(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (packageName.isBlank()) return false
        var opened = false
        context.dataStore.edit { p ->
            val hardLockUntil = p[Keys.HARD_LOCK_UNTIL] ?: 0L
            val uses = EmergencyUnlock.decode(p[Keys.EMERGENCY_USES])
            if (!EmergencyUnlock.canUnlock(uses, nowMillis, hardLockUntil)) return@edit
            p[Keys.EMERGENCY_PACKAGE] = packageName
            p[Keys.EMERGENCY_UNTIL] = nowMillis + EmergencyUnlock.DURATION_SECONDS * 1000
            p[Keys.EMERGENCY_USES] = EmergencyUnlock.encode(
                EmergencyUnlock.recordUse(uses, nowMillis)
            )
            opened = true
        }
        return opened
    }

    suspend fun gateEnabled(): Boolean =
        context.dataStore.data.first()[Keys.GATE_ENABLED] ?: false

    /**
     * Switches the gate on immediately, or starts it winding down.
     *
     * Asymmetric on purpose. Turning it ON takes effect at once and clears any
     * pending wind-down, because more restriction never needs protecting from
     * the reader. Turning it OFF only sets the date: a boundary that can be
     * removed at the moment you want to cross it was never a boundary, and the
     * moment you want to cross it is exactly when you would reach for this
     * switch.
     */
    suspend fun setGateSwitchedOn(on: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        context.dataStore.edit { p ->
            if (on) {
                p[Keys.GATE_ENABLED] = true
                p.remove(Keys.GATE_DISABLE_AT)
            } else if (p[Keys.GATE_ENABLED] == true) {
                // Already winding down: leave the original date alone, so
                // tapping the switch again cannot restart the clock at a
                // shorter distance than it already was.
                if ((p[Keys.GATE_DISABLE_AT] ?: 0L) <= 0L) {
                    p[Keys.GATE_DISABLE_AT] = nowMillis + GateState.COOLING_OFF_MILLIS
                }
            } else {
                p[Keys.GATE_ENABLED] = false
                p.remove(Keys.GATE_DISABLE_AT)
            }
        }
    }

    /**
     * Clears a finished wind-down, once the gate has actually switched off.
     *
     * The rule itself needs no help — [GateState.enabled] is a function of the
     * clock — but leaving the stored flag on forever would mean the settings
     * screen had to explain a switch that says on and behaves as off.
     */
    suspend fun settleGateWindDown() {
        context.dataStore.edit { p ->
            p[Keys.GATE_ENABLED] = false
            p.remove(Keys.GATE_DISABLE_AT)
            p.remove(Keys.SESSION_REMAINING)
        }
    }

    /**
     * Banks reading toward the next session, capped.
     *
     * Reading past the cap still counts as reading — the ledger records it and
     * the book advances — it simply stops buying. Without the cap a heavy
     * weekend funds a week of not reading, which is the currency returning in
     * a larger denomination.
     */
    suspend fun addReadingCredit(delta: Long) {
        if (delta <= 0) return
        context.dataStore.edit { p ->
            val cost = p[Keys.SESSION_COST] ?: GateState.DEFAULT_SESSION_COST_SECONDS
            val current = (p[Keys.READING_CREDIT] ?: 0L).coerceAtLeast(0L)
            p[Keys.READING_CREDIT] = (current + delta).coerceAtMost(GateState.maxCreditFor(cost))
        }
    }

    /**
     * Converts one session's worth of reading credit into app time, or reports
     * that it could not be afforded.
     *
     * Read-modify-write inside one DataStore edit so two taps cannot both see
     * the same credit and both buy with it. The caller serializes as well;
     * this is the part that has to be right regardless.
     */
    suspend fun startSessionIfAffordable(): Boolean {
        var started = false
        context.dataStore.edit { p ->
            val cost = p[Keys.SESSION_COST] ?: GateState.DEFAULT_SESSION_COST_SECONDS
            val length = p[Keys.SESSION_LENGTH] ?: GateState.DEFAULT_SESSION_LENGTH_SECONDS
            val credit = (p[Keys.READING_CREDIT] ?: 0L).coerceAtLeast(0L)
            val remaining = (p[Keys.SESSION_REMAINING] ?: 0L).coerceAtLeast(0L)
            val ceiling = GateState.maxSessionSecondsFor(length)
            if (credit >= cost && remaining < ceiling) {
                p[Keys.READING_CREDIT] = credit - cost
                p[Keys.SESSION_REMAINING] = (remaining + length).coerceAtMost(ceiling)
                started = true
            }
        }
        return started
    }

    /**
     * Burns one second of app time. Returns what is left.
     *
     * Called once a second by the block controller's spend ticker while a
     * blocked app is genuinely in front — never while the screen is off, and
     * never while the reader is somewhere else. That is the whole difference
     * between this and a countdown: time the reader is not spending is time
     * they still have.
     */
    suspend fun spendSessionSecond(): Long {
        var left = 0L
        context.dataStore.edit { p ->
            val remaining = (p[Keys.SESSION_REMAINING] ?: 0L).coerceAtLeast(0L)
            left = (remaining - 1).coerceAtLeast(0L)
            p[Keys.SESSION_REMAINING] = left
        }
        return left
    }

    /**
     * The gate as the stored preferences currently describe it.
     *
     * Built from the same type the rest of the app reads, rather than
     * re-deriving "is the gate on" from the raw keys — the wind-down alone
     * makes that a two-part question, and a second copy of the answer is a
     * second place for it to be wrong.
     */
    private fun gateFrom(p: Preferences, nowMillis: Long) = GateState(
        switchedOn = p[Keys.GATE_ENABLED] ?: false,
        creditSeconds = p[Keys.READING_CREDIT] ?: 0L,
        sessionSecondsRemaining = p[Keys.SESSION_REMAINING] ?: 0L,
        disableAtMillis = p[Keys.GATE_DISABLE_AT] ?: 0L,
        nowMillis = nowMillis,
        sessionCostSeconds = p[Keys.SESSION_COST] ?: GateState.DEFAULT_SESSION_COST_SECONDS,
        sessionLengthSeconds = p[Keys.SESSION_LENGTH] ?: GateState.DEFAULT_SESSION_LENGTH_SECONDS,
    )

    /**
     * Sets the price of a session.
     *
     * RAISING IT IS ALWAYS ALLOWED. LOWERING IT COSTS A SESSION.
     *
     * A slider that drops the price to fifteen minutes is a bigger hole than
     * unblocking a single app — it dissolves the gate entirely, from the
     * settings screen, while the reader is locked out and most motivated to
     * reach for it. So a loosening change waits for app time in hand, exactly
     * as removing an app does, and the escape ends up costing what the front
     * door costs.
     *
     * Enforced here and not only by disabling the slider. A rule that lives in
     * a Composable is a rule that any other caller walks straight past.
     */
    suspend fun setSessionCostSeconds(seconds: Long, nowMillis: Long = System.currentTimeMillis()) {
        val clamped = seconds.coerceIn(
            GateState.MIN_SESSION_COST_SECONDS,
            GateState.MAX_SESSION_COST_SECONDS,
        )
        context.dataStore.edit { p ->
            val current = p[Keys.SESSION_COST] ?: GateState.DEFAULT_SESSION_COST_SECONDS
            if (GateState.loosensCost(current, clamped) && !gateFrom(p, nowMillis).canLoosenTheRules) {
                return@edit
            }
            p[Keys.SESSION_COST] = clamped
            // Banked credit is denominated in sessions, so a cost change has
            // to re-cap it or an old balance could buy more sessions than the
            // new setting allows.
            val credit = (p[Keys.READING_CREDIT] ?: 0L).coerceAtLeast(0L)
            p[Keys.READING_CREDIT] = credit.coerceAtMost(GateState.maxCreditFor(clamped))
        }
    }

    /** Shortening a session is always allowed; lengthening it waits, as above. */
    suspend fun setSessionLengthSeconds(seconds: Long, nowMillis: Long = System.currentTimeMillis()) {
        val clamped = seconds.coerceIn(
            GateState.MIN_SESSION_LENGTH_SECONDS,
            GateState.MAX_SESSION_LENGTH_SECONDS,
        )
        context.dataStore.edit { p ->
            val current = p[Keys.SESSION_LENGTH] ?: GateState.DEFAULT_SESSION_LENGTH_SECONDS
            if (GateState.loosensLength(current, clamped) && !gateFrom(p, nowMillis).canLoosenTheRules) {
                return@edit
            }
            p[Keys.SESSION_LENGTH] = clamped
            // Unspent app time is denominated in sessions too, so shortening
            // one has to re-cap what is already banked.
            val remaining = (p[Keys.SESSION_REMAINING] ?: 0L).coerceAtLeast(0L)
            p[Keys.SESSION_REMAINING] =
                remaining.coerceAtMost(GateState.maxSessionSecondsFor(clamped))
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
            it[Keys.READER_WARMTH] = normalized.warmth
            it[Keys.READER_NIGHT_DIM] = normalized.nightDim
            it[Keys.READER_VOLUME_KEYS] = normalized.volumeKeysTurnPages
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
