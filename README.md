# PageTime

Read to unlock. PageTime lets you download free books from **Standard Ebooks**,
**Project Gutenberg**, **Open Library**, and **YouTube transcripts**, read them
in-app, and bank "browse minutes" that you can spend in apps you'd otherwise
waste time in (Chrome, Instagram, Facebook, …). While your balance is empty,
opening a blocked app bounces you straight back to the reader.

This is a native **Kotlin + Jetpack Compose** Android app.

## How it works

1. **Discover, download, or import** — search four free ebook sources (Standard
   Ebooks, Gutenberg via Gutendex, Open Library + Internet Archive, and YouTube
   transcripts), or use **Library → +** to import an EPUB or plain-text file from
   your phone. YouTube transcripts are fetched directly in-app from any video URL
   or share — no API key needed. Imported files are copied into PageTime's
   private storage and remain available offline.
2. **Read** — an immersive in-app reader powered by Readium for EPUB pagination
   and exact locators. Plain-text books use stable, swipeable pages with saved
   positions and book-style typography. Reader settings include serif/sans/mono fonts,
   sepia and night themes, spacing, margins, and a per-reader brightness override.
   A timer banks browsing time while the reader is open.
3. **Enforce** — an `AccessibilityService` watches the foreground app. When a
   blocked app opens with a zero balance, PageTime shows a full-screen
   "time is up" overlay and offers to reopen the reader. With a positive balance,
   the balance is spent one second at a time while you're in the blocked app.

The reading rate is configurable (default: 1 minute reading = 1 minute browsing).

## Explain Back learning

PageTime can use Gemini to evaluate source-grounded explanations and build concept-map relationships automatically. The reader tracks progress locally and only starts an automatic analysis checkpoint after the configured amount of active reading and meaningful forward progress. The default **Light** setting targets roughly five checkpoints per hour; **Balanced**, **Frequent**, and **Intensive** settings let the reader request analysis more often. Checkpoints use the current window plus limited preceding context rather than the whole book. **Each chapter is analyzed once and the result is cached**: card and concept-map generation is keyed to the chapter (not to reading progress), so the first checkpoint in a chapter sends its text to Gemini, every later checkpoint in that chapter is served from the local database, and a 30-chapter book costs roughly 30 requests in total — not one per checkpoint. The default is **AI-assisted**, where Gemini returns 3–5 high-quality multiple-choice questions per chapter (each with plausible domain-specific distractors) plus meaningful concept-map relationships. The on-device generator (MCQ-only, Wozniak's rules) is the fallback when Gemini is unavailable. Cards are pre-generated on chapter transitions so the first checkpoint in a new chapter is instant. **On-device first** mode inverts that: everything is built locally and Gemini is only contacted when the local pass produces nothing. Either way, Gemini only decides *content* — scheduling, deduplication, ordering, and when cards appear all stay on the device. Source context stays hidden until after the reader answers and can then be expanded or opened at the original location.

Open **Settings → Explain Back with Gemini** in the app to enter the key manually. It is stored in Android encrypted preferences and is never shown again after saving. The app calls Gemini's `models.list` endpoint, follows pagination, filters to models that support `generateContent`, and shows those models in the picker. The selected model is saved locally and used for Explain Back evaluations. **Settings → AI usage & statistics** shows today/all-time request counts, success and failure counts, estimated input tokens, cards, concepts, and relationships. Only request metadata is stored; book text and API keys are not stored in the usage table.

For GitHub Actions/private builds, `GEMINI_API_KEY` can still be supplied as a repository secret and is used only as a build-time fallback. Without a Gemini key, PageTime remains usable for reading and local concept maps; Gemini is optional and adds explanation feedback.

For a public release, move the Gemini request behind a small authenticated server because any API key packaged in an Android APK can be extracted. The manually entered key is encrypted at rest, but the app still sends it directly to Google's API from the device.

## Offline AI (no cloud key)

Lumen card capture — the AI draft shown when you capture a card while reading — can run without any API key. **Settings → AI provider** picks where those requests go:

- **Offline model** — runs on this device, never sends book text anywhere. Works when a model is installed; without one, capture falls back to the plain on-device draft.
- **Gemini** (default) — uses the configured Google Gemini API key, matching earlier behavior.
- **Ask every time** — prefers Gemini when a key is configured, otherwise uses the local model.

The offline model is **Qwen 2.5 0.5B Instruct (q8)**, an open Apache-2.0 model served by the litert-community Hugging Face org and executed on-device through Google's MediaPipe `tasks-genai` runtime. It is a single ~521 MB `.task` file downloaded once over Wi-Fi from **Settings → Offline model** and never bundled into the APK, so the app itself stays small until you opt in. When Settings opens, the app compares the installed file's size and ETag against a cheap HEAD request to the model host; if the model changed, **Settings → Offline model** shows *Update available* with a one-tap update — never automatic. Updates download to a temporary file and only replace the installed model after the size check passes, so a failed update leaves the working model untouched. Both providers share the same capture prompt and output contract, so switching providers does not change card quality expectations. Capture is always best-effort: if the selected provider fails or is unconfigured, the card is still drafted from the raw passage so reading is never blocked.

## Incremental reading (chunks)

Read a book in chunks instead of one relentless pass. From the reader's
**Options** menu, **Start chunk here** marks where you paused; **Close chunk**
asks how it went (Again / Hard / Good — never Easy, for the same reason as the
reading chair) and schedules the passage to come back on the same FSRS calendar
as flashcards. Due chunks surface in the **Review** sitting too, between chapter
cards and slip box notes, and one tap hands them to the reader. When the balance
is empty and a blocked app opens, the "time is up" screen's **Read now** opens the
reader on the next due chunk instead of the last book — the read-to-unlock loop
and the re-read loop are the same loop. **Suspend chunk** pauses without judging. The **Reading queue**
(Library home) shows everything: the chunk in hand first, due re-reads next,
then the rest by priority (1–5, adjustable in the queue). Opening a chunk
jumps the reader straight to its start.

## Text highlights

Mark passages while reading, in both formats:

- **EPUB** — select text as usual, then **Save highlight** from the selection
  menu (the same menu that already offers *Capture this*). The Readium Locator
  is stored, so a highlight covers the whole selected range, which inside one
  chapter can span several rendered pages, and it is re-drawn on every page
  turn via Readium's decoration API.
- **Plain text** — the paged reader has no drag selection, so highlighting is
  anchored instead: **Options → Start highlight here** marks the current page,
  turn forward any number of pages, then **End highlight here**. The span is
  stored as whole-book character offsets, so it survives re-layout at any font
  size, and every page it touches renders with a green background.

Highlights are stored per book and deleted with it; each one keeps the
highlighted text itself, so a highlight can be turned into a Lumen card later
without re-opening the book.

## Requirements

- Android Studio (latest stable) or JDK 17 + the Android SDK.
- `compileSdk 34`, `minSdk 26` (Android 8.0+).

## Build

Open the project in Android Studio and press Run, or from the command line:

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`.

## Setup (permissions)

PageTime needs three special permissions, all configured from
**Settings → Permissions & setup** in the app:

1. **Accessibility service** — enables the blocker to detect when a blocked app
   opens. Android shows this under *Settings → Accessibility*.
2. **Display over other apps** — lets PageTime show the "time is up" screen over
   a blocked app.
3. **Usage access** — Android keeps recording which apps you open even when
   PageTime itself is dead (force-stopped, crashed, swiped away). On every
   launch PageTime reconciles this audit trail against its balance ledger and
   retroactively charges any blocked-app time the live ticker missed.

Then pick which apps to block in **Settings → Manage blocked apps**. To add a personal book, open **Library** and tap **+** (or **Import from phone** when the library is empty), then choose an EPUB or plain-text file. Create a Gemini API key from Google AI Studio and add it under **Settings → Explain Back with Gemini** if you want explanation feedback, or switch **Settings → AI provider** to *Offline model* to draft Lumen cards entirely on-device without a key.

## Honest limitations

- Android does not let a third-party app *prevent* another app from launching.
  The enforcement here is "detect and immediately redirect", which works well in
  practice but is not an OS-level lock. A determined user can always disable the
  accessibility service.
- "Reading time" is counted while the reader is open and the screen is on; it
  does not yet verify physical presence. (See roadmap.)

## Project structure

```
app/src/main/java/com/pagetime/app/
├── MainActivity.kt / PageTimeApp.kt     # entry point + DI container owner
├── blocker/                             # AccessibilityService, overlay, controller
├── data/
│   ├── local/                           # Room (books, blocked apps) + DataStore settings
│   ├── gutenberg/                       # Gutendex client + models
│   ├── standardebooks/                 # Standard Ebooks Atom feed client
│   ├── openlibrary/                    # Open Library + Internet Archive client
│   ├── youtube/                         # YouTube transcript fetcher + search API
│   ├── download/                        # file downloader
│   ├── library/                         # EPUB parser/extractor
│   └── AppContainer.kt, *Repository.kt  # manual DI + repositories
├── domain/BalanceManager.kt             # reading → browsing conversion
└── ui/                                  # Compose theme, nav, and screens
```

## Roadmap ideas

- EPUB search, highlights, and richer annotation tools.
- A "browse minute" schedule (daily cap, different ratios per app).
- Strict mode / emergency unlock for unavoidable app use.
- Open-book cover grid and download progress.
- Idle detection so the timer pauses when you're not actually reading.
