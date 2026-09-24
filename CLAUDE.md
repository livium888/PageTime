# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

PageTime is a native Kotlin + Jetpack Compose Android app (single `:app` module, package `com.pagetime.app`): read free or imported books to bank "browse minutes", which are spent while blocked apps are open. README.md is the user-facing feature documentation and is updated alongside features.

## Commands

JDK 17, Android SDK platform 34 (`compileSdk 34`, `minSdk 26`, `targetSdk 34`).

```bash
./gradlew assembleDebug                      # debug APK
./gradlew testDebugUnitTest                  # all JVM unit tests
./gradlew testDebugUnitTest --tests "com.pagetime.app.data.review.FsrsSchedulingTest"   # one class
./gradlew testDebugUnitTest --tests "*FsrsSchedulingTest.someTestName"                   # one test
./gradlew detekt                             # only UnusedImports + WildcardImport (config/detekt/detekt.yml)
./gradlew ktlintCheck                        # or ktlintFormat
./gradlew assembleRelease                    # signed only when KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD are set
```

`GEMINI_API_KEY` (Gradle property or env var) is an optional build-time fallback; the app normally uses a key entered in Settings.

CI (`.github/workflows/`): `compile-check.yml` runs `assembleDebug` + `testDebugUnitTest` on every `claude/**` push; `build-apk.yml` builds the signed release APK on `main` only. The sandbox proxy blocks Hugging Face and other model hosts. To learn what a model URL actually serves, edit the candidates in `probe-model-urls.yml` and push; the push is the request.

## Architecture

**Wiring.** `PageTimeApp` owns `data/AppContainer.kt`, a manual DI container (no Hilt/Koin) that builds the database, repositories, HTTP client and AI providers. It also owns an app-lifetime, single-threaded `scope`. Writes that must survive leaving a screen (reading position, earned seconds) go through that scope, not a ViewModel scope: ViewModel scopes are cancelled on navigation and dropped those writes, and serial execution stops a stale position save from landing after a newer one. Navigation is one `NavHost` with string routes in `ui/PageTimeAppUi.kt`.

**Persistence.** Room `data/local/AppDatabase.kt` (version 23, `exportSchema = false`) with hand-written `Migration` objects registered in `AppContainer`. There is no destructive fallback. Any schema change needs a version bump plus a migration, and must never drop reader data; unused tables are kept rather than dropped. Settings live in DataStore (`SettingsRepository`); the Gemini key lives in encrypted preferences.

**Read-to-unlock loop.**
- `domain/BalanceManager` converts reading time into browsing seconds; see also `AccessGate` and `EmergencyUnlock`.
- `blocker/AppBlockerService` is an AccessibilityService that watches the foreground app and shows `TimeUpOverlay` when the balance is empty. The decision logic lives in `BlockEnforcementPolicy` and `ForegroundEventPolicy`.
- `data/usage/UsageReconciler` replays UsageStats on launch to charge blocked-app time the live ticker missed (for example, while the app was killed).

**Reading.**
- EPUBs go through Readium (`data/ReadiumEngine.kt`, `ui/screens/reader/ReaderScreen.kt`).
- Plain text, YouTube transcripts and legacy PDFs use the app's own paged `TextReaderHost` / `TextPageLayout`.
- A PDF is converted to an EPUB on import (`data/library/PdfToEpub` with `PdfTextExtractor`, `PdfTextCleaner` and `PdfFigureExtractor`), so every reader feature works on it. The original file is kept, and the library can also open it in the native `PdfReaderScreen` (route `pdf-reader/{bookId}`).
- Book sources are in `data/catalog` (OPDS, Standard Ebooks), `data/gutenberg`, `data/youtube` and `data/shelf` (Open Library authors).

**Learning.**
- Explain Back and chapter flashcards are in `data/learning`. Gemini writes the content (`GeminiLearningClient`); each chapter is analysed once and cached by chapter.
- Scheduling is FSRS (`data/review`, the `fsrs` library) and is always decided on the device.
- Lumen (slip-box cards, `data/Lumen*`) drafts through `LlmProvider`: Gemini, or on-device MediaPipe (`MediaPipeLlmProvider`, a Qwen 0.5B `.task` file downloaded at runtime by `LumenModelStore`).
  - The on-device model must be loaded once and reused. Loading it per request caused native OOM crashes that leave no Java stack trace; see `tmp/app-investigation-notes.txt`.
- `data/embed` runs an ONNX sentence-embedding model (also downloaded, never bundled) for card and book search.

## Conventions

- Decisions are pulled out of Compose and Android classes into plain Kotlin, named `*Policy`, `*Rules`, `*Text`, `*Codec` and similar, and tested on the JVM with JUnit4, coroutines-test and Turbine. There is no Robolectric: Android framework classes are not usable in tests (real `org.json` is added for that reason). New logic belongs in such a file so it can be tested.
- Comments explain *why*, often at length, including the bug or trade-off behind a choice. Match that when editing.
- Commit messages follow `feat(scope): …`, `fix(scope): …`, `test(scope): …`.
- Deliberate build choices, explained in `app/build.gradle.kts`: `arm64-v8a` only, minification off, and `versionCode` = minutes since epoch so every build installs over the last.
