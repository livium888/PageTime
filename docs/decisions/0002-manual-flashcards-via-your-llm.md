# 0002: Make flashcards by copying a stretch of text to your own LLM

**Status:** accepted, 2026-11-08. Owner decision.

## Scope
While reading, copy everything from a start marker to the end of the page you are on, together with a fixed card-writing prompt, into the LLM app of your choice; paste the reply back and review the cards before they enter the deck. EPUB and plain-text books. PDFs opened in the native PDF reader are out of scope for now.

## Context
The Gemini pipeline (topic selection, embeddings, per-chapter batching, quote checks) works but is heavy: it needs an API key, an index, and several moving parts, and the owner found it clunky and its questions only as good as its passage picking. A frontier chat model reading text the reader chose will write better cards, and *choosing* what is worth remembering is the learner's job: the card-writing rules in `ChapterPromptText` already say prioritising "cannot be handed to a model".

## What is reused, and what is new
Reused as is: `ChapterPromptText` (the card-writing rules and the verbatim-quote contract), `ChapterPromptRules`/`PromptSifter` (rejects invented quotes, lists, give-aways, duplicates), `ChunkCard` (builds a kept, due-now, non-AI card), the FSRS review queue.
New: a per-book **start marker**; **span extraction** between two reading positions (across chapters); a **chat-reply parser**; a **sheet** that walks through copy, paste and review.

## Decisions
1. **The marker persists until cards are made from it.** It is set the first time a book is opened with none, and moves to the end of the span when at least one card is imported. This is a deliberate refinement of "record where I started each time I open the book": resetting on every open would silently drop yesterday's reading when no cards were made, while persisting never loses text. The sheet shows what the span covers and how many words it has, and "Start from this page" lets the reader shorten it.
2. **The start marker is separate from pagemarks and from the global learning checkpoint.** Pagemarks schedule re-reads; the checkpoint is one app-wide marker for Explain Back. Overloading either would couple unrelated rules.
3. **Boundaries snap to paragraphs.** EPUB positions map to text approximately (the code documents that a reading fraction lands "near the right paragraph rather than on it"), so the span starts at the start of the paragraph containing the marker and ends at the end of the paragraph containing the current page. Plain text has exact character offsets and uses them. The sheet previews the first and last words so the reader can see what will be copied.
4. **Every pasted card goes through the same checks as AI cards.** A card whose `sourceQuote` is not verbatim in the span is rejected, with the reason shown. Invented facts cannot reach the deck.
5. **Reply format:** JSON in one code block, parsed leniently: markdown fences, chatter around it, a missing wrapper, curly quotes in prose and a truncated reply are all tolerated, and each card is judged on its own so one bad card never loses the batch. `passageIndex` is optional (the span is one passage).
6. **Review before adding.** Parsed cards are listed with checkboxes (all ticked); only ticked cards are written, as kept and due now.
7. **State survives leaving the app.** Switching to the LLM app can get PageTime killed, so the marker and the end of the copied span are stored, not held in memory. The span text is recomputed from the stored positions when the reply arrives.
8. **The Gemini path is left untouched.** Nothing is removed in this change. Removal is a follow-up once the manual flow has proved itself.

## Options considered
- **Keep improving the AI pipeline.** Rejected by the owner: the friction is the pipeline itself.
- **Share-sheet only (no clipboard).** Rejected as the main path: intents have a size limit, so a long span could fail. The clipboard is the default; the share sheet is offered for short spans.
- **Strict all-or-nothing import.** Rejected: LLM replies are messy and one stray character would cost the whole batch.
- **Reset the marker on every open.** Rejected, see decision 1.

## Revisit when
- Manual batches are rarely made after the first week: the friction moved to the reader, so look at a one-tap "copy last N minutes".
- Pasted cards are often rejected: the prompt or the strictness of a rule needs tuning, shown by the rejection reasons.
- EPUB boundaries are visibly wrong on real books: switch to exact visible text from the navigator.
