package com.pagetime.app.selfcheck

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.pagetime.app.PageTimeApp
import com.pagetime.app.anki.AnkiExporter
import com.pagetime.app.data.AppContainer
import com.pagetime.app.data.AppHttp
import com.pagetime.app.data.local.AppDatabase
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.youtube.YouTubeSearchApi
import com.pagetime.app.domain.LockDay
import com.pagetime.app.selfcheck.SelfCheck.Scenario
import com.pagetime.app.selfcheck.SelfCheck.expect
import com.pagetime.app.selfcheck.SelfCheck.fail
import com.pagetime.app.selfcheck.SelfCheck.info
import com.pagetime.app.selfcheck.SelfCheck.pass
import com.pagetime.app.selfcheck.SelfCheck.skip
import com.pagetime.app.ui.screens.settings.hasUsageAccessPermission
import com.pagetime.app.ui.screens.settings.isAccessibilityServiceEnabled
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Scenarios that need the phone itself: what the app has been allowed to do,
 * whether its storage works, and whether the places it depends on answer.
 *
 * Read-only against everything of the reader's: storage runs on a throwaway
 * database and file, AnkiDroid is only asked what it has, and the network is
 * only read.
 */
object DeviceScenarios {

    private const val PERMS = "This phone: permissions"
    private const val STORE = "This phone: storage"
    private const val ANKI = "This phone: AnkiDroid"
    private const val NET = "This phone: network"
    private const val STATE = "This phone: what the app holds"

    fun all(context: Context, container: AppContainer): List<Scenario> =
        permissions(context) + anki(context) + storage(context) + network() + state(context, container)

    private fun permissions(context: Context) = listOf(
        Scenario("perm.overlay", PERMS, "Block screen: allowed to show over other apps") {
            if (android.provider.Settings.canDrawOverlays(context)) pass()
            else fail("Not allowed. Without it the block screen cannot appear.")
        },
        Scenario("perm.accessibility", PERMS, "Blocker: the accessibility service is on") {
            if (isAccessibilityServiceEnabled(context)) pass()
            else fail("Off. Without it no app is blocked.")
        },
        Scenario("perm.usage", PERMS, "Usage access: allowed") {
            if (hasUsageAccessPermission(context)) pass()
            else fail("Not allowed. Time spent in other apps cannot be read.")
        },
        Scenario("perm.notifications", PERMS, "Notifications: allowed") {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) pass()
            else fail("Off. Review reminders cannot appear.")
        },
    )

    private fun anki(context: Context) = listOf(
        Scenario("anki.installed", ANKI, "AnkiDroid is installed") {
            if (AnkiExporter.isInstalled(context)) pass() else skip("Not installed, so the Anki checks are skipped.")
        },
        Scenario("anki.permission", ANKI, "PageTime may use AnkiDroid") {
            when {
                !AnkiExporter.isInstalled(context) -> skip("AnkiDroid is not installed.")
                AnkiExporter.hasPermission(context) -> pass()
                else -> fail("Not granted yet. Switch on “Send new cards to Anki”, or Review Anki cards, to be asked.")
            }
        },
        Scenario("anki.decks", ANKI, "AnkiDroid answers, and its decks can be read") {
            when {
                !AnkiExporter.isInstalled(context) -> skip("AnkiDroid is not installed.")
                !AnkiExporter.hasPermission(context) -> skip("No permission yet.")
                else -> withContext(Dispatchers.IO) {
                    val uri = Uri.parse("content://com.ichi2.anki.flashcards/decks")
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    if (cursor == null) fail("AnkiDroid did not answer. Open it once, then run this again.")
                    else cursor.use { c ->
                        val name = c.getColumnIndex("deck_name")
                        val names = buildList { while (name >= 0 && c.moveToNext()) add(c.getString(name)) }
                        pass(
                            "${names.size} decks" +
                                if (hasPageTimeDeck(names)) ", including PageTime" else ", no PageTime deck yet"
                        )
                    }
                }
            }
        },
    )

    private fun hasPageTimeDeck(names: List<String>) = names.contains(com.pagetime.app.anki.AnkiNotes.DECK_NAME)

    private fun storage(context: Context) = listOf(
        Scenario("store.room", STORE, "Database: cards, due queries and Anki status behave") {
            withContext(Dispatchers.IO) {
                // In memory, so nothing of the reader's is touched.
                val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
                try {
                    val cards = db.learningCardDao()
                    val past = 1_000L
                    fun card(id: String, status: String) = LearningCardEntity(
                        id = id, bookId = "b", chapterIndex = 0, chapterTitle = null, prompt = id, answer = "a",
                        explanation = null, sourceLocator = null, sourceFraction = null, fsrsCardJson = "{}",
                        createdAt = past, updatedAt = past, status = status, dueAt = past, generatedByAi = true,
                    )
                    db.bookDao().upsert(
                        BookEntity("b", "Test", "", "txt", "/none", null, 0)
                    )
                    cards.insertAll(
                        listOf(
                            card("kept", LearningCardEntity.STATUS_KEPT),
                            card("anki", LearningCardEntity.STATUS_ANKI),
                            card("pending", LearningCardEntity.STATUS_PENDING),
                        )
                    )
                    val due = cards.dueCards(System.currentTimeMillis(), 10).map { it.id }
                    cards.deleteUnkeptForChapter("b", 0)
                    val left = cards.observeLive().first().map { it.id }.sorted()
                    expect(
                        "only the kept card is due (got $due)" to (due == listOf("kept")),
                        "regenerating a chapter keeps kept and Anki cards (left $left)" to (left == listOf("anki", "kept")),
                    )
                } finally {
                    db.close()
                }
            }
        },
        Scenario("store.datastore", STORE, "Settings storage: a value is written and read back") {
            withContext(Dispatchers.IO) {
                val scope = CoroutineScope(Dispatchers.IO + Job())
                val file = File(context.cacheDir, "selfcheck.preferences_pb")
                try {
                    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                    val key = stringPreferencesKey("probe")
                    store.edit { it[key] = "ok" }
                    val read = store.data.first()[key]
                    expect("the value came back (got $read)" to (read == "ok"))
                } finally {
                    scope.cancel()
                    runCatching { file.delete() }
                }
            }
        },
    )

    private fun network() = listOf(
        Scenario("net.oembed", NET, "YouTube: a video's title can be looked up") {
            withContext(Dispatchers.IO) {
                val client = AppHttp.newClient(callTimeoutSeconds = 15L)
                val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ&format=json"
                client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    val title = if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()).optString("title") else ""
                    if (title.isNotBlank()) pass("“${title.take(40)}”")
                    else fail("Answered ${r.code} with no title. Adding several videos at once may then save them untitled.")
                }
            }
        },
        Scenario("net.youtube_search", NET, "YouTube: search returns videos") {
            val (results, _) = YouTubeSearchApi().search("lecture history")
            if (results.isNotEmpty()) pass("${results.size} results") else fail("No results.")
        },
        Scenario("net.openlibrary", NET, "Open Library: a book search answers") {
            withContext(Dispatchers.IO) {
                val client = AppHttp.newClient(callTimeoutSeconds = 15L)
                val url = "https://openlibrary.org/search.json?q=dune&limit=1&fields=title"
                client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    val docs = if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()).optJSONArray("docs") else null
                    if (docs != null && docs.length() > 0) pass() else fail("Answered ${r.code} with no books.")
                }
            }
        },
    )

    private fun state(context: Context, container: AppContainer) = listOf(
        Scenario("state.counts", STATE, "Library, cards and reading day") {
            withContext(Dispatchers.IO) {
                val books = container.database.bookDao().getAll()
                val cards = container.database.learningCardDao().observeLive().first()
                info(
                    "${books.size} books (${books.count { it.id.startsWith("yt-") }} videos), " +
                        "${cards.size} cards (${cards.count { it.status == LearningCardEntity.STATUS_ANKI }} in Anki), " +
                        "day resets at ${LockDay.resetLabel()}"
                )
            }
        },
        Scenario("state.crash", STATE, "Crash log") {
            val latest = PageTimeApp.crashDirOf(context)
                .listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
            if (latest == null || latest.length() == 0L) info("None.")
            else info("The latest is ${latest.name}; it is added to the copied report.")
        },
    )
}
