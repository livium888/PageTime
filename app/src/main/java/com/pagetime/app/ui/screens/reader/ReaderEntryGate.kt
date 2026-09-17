package com.pagetime.app.ui.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.PageTimeApp
import com.pagetime.app.ui.screens.review.ReviewSessionScreen
import com.pagetime.app.ui.screens.review.ReviewSessionViewModel
import com.pagetime.app.ui.screens.review.ReviewSessionViewModelFactory

/**
 * Stands between every path into the reader and the book itself, and asks
 * whether anything due should be answered first.
 *
 * ONE PLACE, NOT TWELVE
 *
 * A book is opened from the library, the bookshelf, a search result, a
 * concept map, "continue reading", the block screen's own "Read now"
 * button, a notification tap, and a handful of other spots — each one
 * navigating to the same "reader/{bookId}" (or "pdf-reader/{bookId}")
 * destination. Gating each call site individually would mean a dozen places
 * that have to remember the rule, and a next one, someday, that forgets it.
 * Wrapping the destination itself means every path already goes through
 * here without having to know it exists.
 *
 * THE SAME QUESTION THE REVIEW SCREEN ASKS, NOT A CHEAPER COPY OF IT
 *
 * [ReviewSessionViewModel] in gate mode (see its own doc) IS the due
 * query — chapter cards and slip box cards, not reading chunks; see
 * [ReviewSessionViewModel.load] for why a chunk is left out of the gate
 * specifically. Mounting the real thing here means there is exactly one
 * place "what is due" can be answered, rather than a second, cheaper count
 * that could disagree with the one the gate actually enforces.
 *
 * NOTHING VISIBLE WHEN NOTHING IS DUE
 *
 * The overwhelmingly common case — no cards waiting — passes straight
 * through to [content] the moment the due query answers, showing nothing
 * beyond the same brief "Finding what is due…" the standalone Review screen
 * already shows while loading. A reader with an empty queue should never be
 * able to tell this exists.
 *
 * WHY "SATISFIED" IS VIEWMODEL STATE, NOT A REMEMBERED BOOLEAN
 *
 * The book screen offers side trips that are still part of THIS reading
 * session — Explain-back, Highlights, Concepts, the slip box — and every one
 * of them pushes a NEW nav destination on top of this one. Compose Navigation
 * is free to dispose this composable's own composition while it is not the
 * top entry, which would reset a plain `remember`ed flag the moment the
 * reader tapped "Explain this" and came back — re-litigating the entire
 * gate for a side trip, not a fresh visit. [ReviewSessionViewModel] is scoped
 * to the SAME nav back-stack entry this composable is, and survives exactly
 * as long as it does: gone when the entry is genuinely popped and a fresh
 * visit creates a new one, intact through anything pushed on top of it. So
 * [ReviewUiState.startReadingRequested] lives on the ViewModel, and this
 * reads it rather than keeping a copy that cannot make the same promise.
 */
@Composable
fun ReaderEntryGate(
    onBack: () -> Unit,
    onOpenSource: (bookId: String) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PageTimeApp
    val gateVm: ReviewSessionViewModel = viewModel(
        factory = ReviewSessionViewModelFactory(app, gateMode = true),
    )
    val state by gateVm.state.collectAsStateWithLifecycle()

    val satisfied = state.startReadingRequested ||
        (!state.loading && state.session.started == 0)

    if (satisfied) {
        content()
    } else {
        ReviewSessionScreen(
            vm = gateVm,
            onBack = onBack,
            onOpenSource = onOpenSource,
            gateMode = true,
            onStartReading = gateVm::requestStartReading,
        )
    }
}
