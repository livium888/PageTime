# 0003 — No built-in AI: the reader's own AI app does the writing

**Status:** accepted
**Builds on:** 0002 (manual flashcards via your LLM)

## Decision

PageTime does not call any AI service and ships no language model. Every feature
that needs a model prepares a prompt, the reader takes it to their own AI app
(copy or share) and brings the reply back (paste or share). The reply is read
tolerantly and **checked before it is stored**.

Removed: the Gemini client and its key, model picker and usage screens; the
on-device language model (MediaPipe, a ~520 MB download); the Gemini key baked
into the APK at build time; the cloud-rescue and provider settings.

Kept: the small on-device search model (ONNX), which finds related notes and
writes nothing.

## Why

- **The reader already pays for a better model than we can embed**, and chooses
  it. Whatever they use gets better without a release.
- **Nothing the reader reads leaves the phone unless they send it.** The offline
  model existed to make that true; this makes it true without a half-gigabyte
  download, a native runtime that could abort the process, or a tombstone
  mechanism to survive that.
- **A key in an APK can be extracted by anyone holding the file.** There is no
  key now. Phones that had one entered have it erased on first launch.
- **The round trip is the cost, so it is made cheap**: one shared dialog, shared
  replies, a pending explanation that survives the app being closed.

## What every feature keeps from the old pipeline

The checks. A model asked for a supporting quote produces a plausible paraphrase
of one, so a card or concept whose quote is not in the text that was sent is
dropped, never stored. Replies that would overwrite something (a tidied
transcript) are refused when they are much shorter than the original.

## Consequences

- No feature works without the reader doing a step by hand. That is the trade.
- The old tables (`ai_usage_events`, `learning_generations`, `chapter_passages`)
  remain in the database, unused, so no migration was needed. They can be dropped
  in a later schema change.
- `LlmTokenBudget` stays as a bound on how large a capture prompt may be.
- On first launch the app deletes `files/lumen-model` and the stored Gemini key.

## Rejected

- **Keep Gemini behind a toggle.** Two paths per feature, one of them sending
  text off the phone with a key in the app.
- **Keep the offline model as an option.** Its output was the weakest in the app
  and it cost a runtime, a download and a crash workaround to provide.
