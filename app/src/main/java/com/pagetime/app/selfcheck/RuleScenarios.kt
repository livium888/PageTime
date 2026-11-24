package com.pagetime.app.selfcheck

import com.pagetime.app.anki.AnkiNotes
import com.pagetime.app.blocker.BlockScreenText
import com.pagetime.app.data.keepreading.KeepReading
import com.pagetime.app.data.library.CoverRules
import com.pagetime.app.data.local.BookEntity
import com.pagetime.app.data.local.LearningCardEntity
import com.pagetime.app.data.talk.TalkItThrough
import com.pagetime.app.data.youtube.VideoOwnership
import com.pagetime.app.domain.EmergencyUnlock
import com.pagetime.app.domain.LockDay
import com.pagetime.app.selfcheck.SelfCheck.Scenario
import com.pagetime.app.selfcheck.SelfCheck.expect
import com.pagetime.app.ui.screens.flashcards.FlashcardFilter
import com.pagetime.app.ui.screens.flashcards.FlashcardListing
import com.pagetime.app.ui.screens.library.BookKind
import com.pagetime.app.ui.screens.library.LibraryShelves
import com.pagetime.app.ui.screens.reader.IdleLimit
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Scenarios for the app's own rules, with nothing outside the code: the things
 * we have already talked about and fixed, kept so they cannot quietly come back.
 * Pure, so they also run in the build's own unit tests.
 */
object RuleScenarios {

    private const val G = "Rules we have fixed"
    private const val T0 = 1_700_000_000_000L
    private const val HOUR = 3_600_000L

    private fun book(id: String, format: String = "epub", progress: Float = 0f) = BookEntity(
        id = id, title = id, author = "", format = format, localPath = "/x/$id", coverUrl = null,
        addedAt = 0, scrollProgress = progress,
    )

    private fun card(id: String, status: String = "kept", created: Long = 1_000L, dueAt: Long? = null) =
        LearningCardEntity(
            id = id, bookId = "b", chapterIndex = 0, chapterTitle = null, prompt = "What is $id?", answer = "a",
            explanation = null, sourceLocator = null, sourceFraction = null, fsrsCardJson = "{}",
            createdAt = created, updatedAt = created, status = status, dueAt = dueAt,
        )

    fun all(): List<Scenario> = listOf(
        Scenario("emergency.double_tap", G, "Emergency button: a double tap spends one unlock") {
            val until = T0 + EmergencyUnlock.DURATION_SECONDS * 1000
            expect(
                "a second tap on the open app is ALREADY_OPEN" to
                    (EmergencyUnlock.attempt(listOf(T0), T0 + 500, 0L, "com.bank", until, "com.bank") ==
                        EmergencyUnlock.Outcome.ALREADY_OPEN),
                "another app is a new use" to
                    (EmergencyUnlock.attempt(listOf(T0), T0 + 500, 0L, "com.bank", until, "com.maps") ==
                        EmergencyUnlock.Outcome.OPENED),
                "none left is refused" to
                    (EmergencyUnlock.attempt(listOf(T0, T0 - HOUR), T0 + 1000, 0L, null, 0L, "com.bank") ==
                        EmergencyUnlock.Outcome.REFUSED),
                "a hard lock is refused" to
                    (EmergencyUnlock.attempt(emptyList(), T0, T0 + HOUR, null, 0L, "com.bank") ==
                        EmergencyUnlock.Outcome.REFUSED),
            )
        },
        Scenario("emergency.window", G, "Emergency button: two a week, and the wait reads in days") {
            val two = EmergencyUnlock.recordUse(EmergencyUnlock.recordUse(emptyList(), T0), T0 + HOUR)
            val next = EmergencyUnlock.nextAvailableAt(two, T0 + 2 * HOUR)
            expect(
                "two uses leave none" to (EmergencyUnlock.usesLeft(two, T0 + 2 * HOUR) == 0),
                "the next one is a week after the oldest" to (next == T0 + EmergencyUnlock.WINDOW_MILLIS),
                "a week's wait reads as days" to (BlockScreenText.span(6 * 24 * 3600L + 4 * 3600L) == "6d 4h"),
            )
        },
        Scenario("videos.series", G, "Videos: lectures in a series are different videos") {
            val owned = listOf(VideoOwnership.OwnedBook("yt-AAAAAAAAAAA", "Calculus: Lecture 1", "Open University"))
            expect(
                "Lecture 2 is not Lecture 1" to
                    (VideoOwnership.find(owned, "BBBBBBBBBBB", "Calculus: Lecture 2", "Open University") == null),
                "the same title is found" to
                    (VideoOwnership.find(owned, "CCCCCCCCCCC", "Calculus - Lecture 1!", "Open University")?.id ==
                        "yt-AAAAAAAAAAA"),
                "the same video is found by id" to
                    (VideoOwnership.find(owned, "AAAAAAAAAAA")?.id == "yt-AAAAAAAAAAA"),
            )
        },
        Scenario("videos.standin", G, "Videos: several untitled transcripts are not copies of each other") {
            val owned = listOf(VideoOwnership.OwnedBook("yt-AAAAAAAAAAA", "YouTube Transcript", "YouTube"))
            expect(
                "an untitled video is not a copy" to
                    (VideoOwnership.find(owned, "BBBBBBBBBBB", "YouTube Transcript", "YouTube") == null),
                "a real title is not matched by an untitled book" to
                    (VideoOwnership.find(owned, "BBBBBBBBBBB", "A real title", "Some Channel") == null),
            )
        },
        Scenario("covers.epub", G, "Covers: an EPUB's own cover is found") {
            val opf = """<metadata><meta name="cover" content="c"/></metadata>
                <manifest><item id="c" href="img/front.jpg" media-type="image/jpeg"/></manifest>"""
            expect(
                "the cover is found" to (CoverRules.coverHref(opf) == "img/front.jpg"),
                "its path resolves inside the zip" to
                    (CoverRules.resolve("OEBPS/content.opf", "img/front.jpg") == "OEBPS/img/front.jpg"),
                "a thumbnail is built for a video id" to
                    (VideoOwnership.coverFor("yt-dQw4w9WgXcQ", null)?.contains("dQw4w9WgXcQ") == true),
                "an older transcript has no thumbnail" to (VideoOwnership.coverFor("yt-not-an-id-at-all", null) == null),
            )
        },
        Scenario("library.shelves", G, "Library: a shelf for each kind, finished at the bottom") {
            val shelves = LibraryShelves.shelves(
                listOf(book("done", progress = 1f), book("new"), book("yt-a", "txt"), book("paste-1", "txt"))
            )
            expect(
                "kinds are told apart" to
                    (LibraryShelves.kindOf(book("yt-a", "txt")) == BookKind.VIDEO &&
                        LibraryShelves.kindOf(book("paste-1", "txt")) == BookKind.PASTED &&
                        LibraryShelves.kindOf(book("p", "pdf")) == BookKind.PDF),
                "unfinished comes before finished" to (shelves.first().second.map { it.id } == listOf("new", "done")),
            )
        },
        Scenario("flashcards.listing", G, "Flashcards: search, and cards in Anki are never due here") {
            val sent = card("1", status = LearningCardEntity.STATUS_ANKI, dueAt = 1L)
            expect(
                "every typed word must match" to
                    (FlashcardListing.matchesQuery(card("acid"), "what acid") &&
                        !FlashcardListing.matchesQuery(card("acid"), "what sugar")),
                "a sent card is listed under In Anki" to (FlashcardListing.matches(sent, FlashcardFilter.ANKI, 10L)),
                "a sent card is not due" to (!FlashcardListing.matches(sent, FlashcardFilter.DUE, 10L)),
            )
        },
        Scenario("anki.notes", G, "Anki: what is sent, and what a card becomes") {
            val cards = listOf(
                card("old", created = 500L), card("new", created = 2_000L), card("sent", created = 3_000L),
                card("pending", status = "pending", created = 4_000L),
            )
            val cloze = card("c").copy(prompt = "Acid {{c1::keeps}} pectin firm.", cardType = "cloze")
            val fields = AnkiNotes.fieldsFor(cloze, "Book")
            expect(
                "only new kept cards are sent, once" to
                    (AnkiNotes.toSend(cards, 1_000L, setOf("sent")).map { it.id } == listOf("new")),
                "nothing is sent while the switch is off" to (AnkiNotes.toSend(cards, 0L, emptySet()).isEmpty()),
                "a cloze has the gap in front and the whole sentence behind" to
                    (!fields[0].contains("keeps") && fields[1] == "Acid keeps pectin firm."),
            )
        },
        Scenario("keepreading.reply", G, "Keep reading: the AI's reply is read, the prompt carries your highlights") {
            val reply = "1. **Deep Work** | Cal Newport | book | focus | https://example.com\n" +
                "2. REM explained | Kurzgesagt | YouTube video | clear |"
            val parsed = KeepReading.parseReply(reply).getOrNull()
            val prompt = KeepReading.buildPrompt(
                KeepReading.Finished("Why We Sleep", "Walker", "book", highlights = listOf("adenosine")),
                KeepReading.Direction.OPPOSITE, setOf(KeepReading.Kind.BOOK),
            )
            expect(
                "two suggestions are read" to (parsed?.size == 2),
                "the kinds are read" to (parsed?.map { it.kind } ==
                    listOf(KeepReading.Kind.BOOK, KeepReading.Kind.VIDEO)),
                "the prompt carries a highlight and the direction" to
                    (prompt.contains("adenosine") && prompt.contains("THE OPPOSITE VIEW")),
                "a clipboard reply is recognised, the prompt is not" to
                    (KeepReading.looksLikeReply(reply, prompt, null) && !KeepReading.looksLikeReply(prompt, prompt, null)),
                "Find it is a search" to
                    (KeepReading.searchUrl(parsed!![1]).startsWith("https://www.youtube.com/results")),
            )
        },
        Scenario("reading.idle", G, "Reading: a dense page gets a longer idle limit, never past five minutes") {
            val floor = 120_000L
            expect(
                "a thin page keeps the usual limit" to (IdleLimit.forWords(100, floor) == floor),
                "a dense page is given longer" to (IdleLimit.forWords(350, floor) > 280_000L),
                "there is a ceiling" to (IdleLimit.forWords(9_000, floor) == 300_000L),
            )
        },
        Scenario("talk.credit", G, "Talk it through: credit is the smallest of three numbers, the receipt must match") {
            val r = TalkItThrough.Receipt("talk-abc12", 4, TalkItThrough.Verdict.UNDERSTOOD, emptyList())
            val reply = "PAGETIME RECEIPT\ncode: talk-abc12\nexchanges: 4\nverdict: understood\nshaky: none"
            expect(
                "a normal talk earns six minutes" to (TalkItThrough.credit(612L, r).seconds == 360L),
                "three hours away is still six minutes" to (TalkItThrough.credit(3 * 3600L, r).seconds == 360L),
                "a receipt pasted at once earns nothing" to (TalkItThrough.credit(25L, r).seconds == 0L),
                "the receipt is read" to (TalkItThrough.parseReceipt(reply, "talk-abc12").getOrNull()?.exchanges == 4),
                "a receipt for another conversation is refused" to
                    (TalkItThrough.parseReceipt(reply, "talk-zzzzz").isFailure),
                "a longer stretch earns more questions" to
                    (TalkItThrough.questionsFor(1_000) == 1 && TalkItThrough.questionsFor(9_000) == 3),
            )
        },
        Scenario("prompts.style", G, "Prompts: questions ask for understanding, and the reply formats are unchanged") {
            val cards = com.pagetime.app.data.learning.ManualCardPrompt.build("Book", "Chapter", "A passage of some length.")
            val talk = TalkItThrough.buildPrompt("Book", "Chapter", "Text.", emptyList(), emptyList(), 1, "talk-abc12")
            expect(
                "the card prompt carries the style" to cards.contains("STYLE OF THE QUESTIONS"),
                "the card reply format is still last" to cards.trimEnd().endsWith("is discarded."),
                "the talk prompt carries both ways of asking" to (talk.contains("Feynman") && talk.contains("Franklin")),
                "the talk prompt still asks for the receipt" to talk.contains("PAGETIME RECEIPT"),
                "a roundtable prompt seats the reader at a Junto and keeps the receipt" to
                    TalkItThrough.buildPrompt(
                        "Book", "Chapter", "Text.", emptyList(), emptyList(), 1, "talk-abc12",
                        style = TalkItThrough.Style.ROUNDTABLE,
                    ).let { it.contains("Junto") && it.contains("I am a member of the club") && it.contains("PAGETIME RECEIPT") },
            )
        },
        Scenario("talk.offers", G, "Talk offers: listed at chapter ends, a card once and alone, paused after two declines") {
            val mode = com.pagetime.app.data.talk.TalkQueue.Mode.AT_CHAPTER_ENDS
            val q = com.pagetime.app.data.talk.TalkQueue
            expect(
                "a long chapter is listed" to q.shouldQueue(mode, 2_000),
                "a short one is not" to !q.shouldQueue(mode, 500),
                "nothing is listed when the reader wants no offers" to
                    !q.shouldQueue(com.pagetime.app.data.talk.TalkQueue.Mode.ONLY_ASK, 2_000),
                "a card appears when nothing else does" to q.shouldOfferNow(mode, 2_000, false, false, 0, 0L, 100L),
                "not on top of another prompt" to !q.shouldOfferNow(mode, 2_000, false, true, 0, 0L, 100L),
                "not twice in a session" to !q.shouldOfferNow(mode, 2_000, true, false, 0, 0L, 100L),
                "two declines in a row pause the day" to (q.afterDecline(1, 0L, 100L) == (0 to 100L)),
            )
        },
        Scenario("transcript.markdown", G, "Tidied transcripts: heading levels, tables and quotes are kept; the prompt asks for them") {
            val md = "# Talk\n\n## Part\n\n### Topic\n\nSome **key** words.\n\n> quoted\n\n" +
                "| Option | Cost |\n|---|---|\n| Rent | 900 |"
            val c = com.pagetime.app.data.library.MarkdownText.convert(md)
            val kinds = com.pagetime.app.data.library.MarkdownText.Kind.HEADING
            val prompt = com.pagetime.app.data.TranscriptFormatPrompt.build("Talk", "one two three")
            expect(
                "three heading levels are kept" to
                    (c.spans.filter { it.kind == kinds }.map { it.level } == listOf(1, 2, 3)),
                "no marks are left on screen" to (!c.text.contains('#') && !c.text.contains('|') && !c.text.contains("**")),
                "a table row becomes a titled block" to c.text.contains("Rent\nCost: 900"),
                "sizes shrink by level and stop at body size" to
                    (com.pagetime.app.ui.screens.reader.headingStyle(1, 1.3f).fontSize.value >
                        com.pagetime.app.ui.screens.reader.headingStyle(3, 1.3f).fontSize.value),
                "the prompt asks for levels, tables and quotes" to
                    (prompt.contains("\"### \"") && prompt.contains("Tables") && prompt.contains("Blockquotes")),
            )
        },
        Scenario("blocked.removal", G, "Blocked apps: a locked app says why, and when it will let go") {
            val d = com.pagetime.app.blocker.AppRulesDraft
            expect(
                "10 minutes of reading left is named" to
                    (d.whyRemovalWaits(false, 0L, 600L)?.contains("10 min") == true),
                "a hard lock is named before the reading" to
                    (d.whyRemovalWaits(false, 30 * 60_000L, 600L)?.startsWith("Hard lock") == true),
                "nothing is said when removing is allowed" to (d.whyRemovalWaits(true, 0L, 0L) == null),
            )
        },
        Scenario("lock.day", G, "Reading day: it starts at your reset hour, not midnight") {
            val zone = ZoneId.systemDefault()
            val reset = LockDay.resetHour
            val today = LocalDate.now()
            val boundary = LocalDateTime.of(today, LocalTime.of(reset, 0)).atZone(zone).toInstant().toEpochMilli()
            expect(
                "a minute before the reset is still yesterday" to
                    (LockDay.of(boundary - 60_000L, zone) == LockDay.of(boundary + 60_000L, zone) - 1),
            )
        },
    )
}
