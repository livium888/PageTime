# PageTime

Read to unlock. PageTime lets you download free books from **Standard Ebooks**,
**Project Gutenberg**, and **Open Library**, read them in-app, and bank "browse minutes"
that you can spend in apps you'd otherwise waste time in (Chrome, Instagram,
Facebook, …). While your balance is empty, opening a blocked app bounces you
straight back to the reader.

This is a native **Kotlin + Jetpack Compose** Android app.

## How it works

1. **Discover, download, or import** — search three free ebook sources (Standard
   Ebooks, Gutenberg via Gutendex, and Open Library + Internet Archive), or use
   **Library → +** to import an EPUB or plain-text file from your phone. Imported
   files are copied into PageTime's private storage and remain available offline.
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

## Automatic comprehension cards

PageTime can use Gemini to create source-grounded comprehension cards and concept-map relationships automatically. The reader tracks progress locally and only starts an automatic analysis checkpoint after the configured amount of active reading and meaningful forward progress. The default **Light** setting targets roughly five checkpoints per hour; **Balanced**, **Frequent**, and **Intensive** settings let the reader request analysis more often. Each checkpoint can make one bounded card request and one bounded concept-map request, using the current window plus limited preceding context rather than the whole book. Gemini returns at most three high-value cards (cloze, multiple choice, or concise Q&A) and meaningful concept relationships. Source context stays hidden until after the reader answers and can then be expanded or opened at the original location.

Open **Settings → Gemini cards** in the app to enter the key manually. It is stored in Android encrypted preferences and is never shown again after saving. The app calls Gemini's `models.list` endpoint, follows pagination, filters to models that support `generateContent`, and shows those models in the picker. The selected model is saved locally and used for future automatic generation. **Settings → AI usage & statistics** shows today/all-time request counts, success and failure counts, estimated input tokens, cards, concepts, and relationships. Only request metadata is stored; book text and API keys are not stored in the usage table.

For GitHub Actions/private builds, `GEMINI_API_KEY` can still be supplied as a repository secret and is used only as a build-time fallback. Without a Gemini key, PageTime still creates lightweight offline recall cards and local concept candidates without using the API; Gemini is optional and adds richer topic-based results.

For a public release, move the Gemini request behind a small authenticated server because any API key packaged in an Android APK can be extracted. The manually entered key is encrypted at rest, but the app still sends it directly to Google's API from the device.

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

Then pick which apps to block in **Settings → Manage blocked apps**. To add a personal book, open **Library** and tap **+** (or **Import from phone** when the library is empty), then choose an EPUB or plain-text file. Create a Gemini API key from Google AI Studio and add it under **Settings → Gemini cards** if you want automatic comprehension cards.

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
