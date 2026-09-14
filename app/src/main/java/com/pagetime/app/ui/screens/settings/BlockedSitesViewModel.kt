package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.local.BlockedSiteEntity
import com.pagetime.app.domain.GateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BlockedSitesViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val repo = container.blockedSiteRepository

    /**
     * Every rule, in the order they will be matched.
     *
     * The list is the whole screen — there is no separate "enabled" state to
     * toggle, because a site rule that is not in force is one the reader
     * deleted. A switch here would be a second way to say the same thing, and
     * the second way is the one that ends up disagreeing with the first.
     */
    val sites = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether rules may be REMOVED right now; the same fence the app list uses. */
    val gate = container.balanceManager.gate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateState.Unknown)

    val hardLockUntil = container.settingsRepository.settings
        .map { it.hardLockUntil }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()

    /**
     * What happened to the last thing typed.
     *
     * A refusal has to say something. "Add" that silently does nothing is the
     * worst possible answer for a field whose rules are not obvious — the
     * reader is left guessing which part of `stuff i read` was unacceptable.
     */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _refused = MutableStateFlow(false)
    val refused = _refused.asStateFlow()

    fun edit(text: String) {
        _draft.value = text
        if (_refused.value || _message.value != null) {
            _refused.value = false
            _message.value = null
        }
    }

    /**
     * Stores what was typed, or explains why it cannot be.
     *
     * Normalisation is done by the same [com.pagetime.app.blocker.SiteRules]
     * code that later matches the address bar, so the message reports what was
     * ACTUALLY stored: type `https://WWW.BBC.co.uk/News?x=1` and the field
     * clears and the list says `bbc.co.uk/news`, which is the rule that will be
     * enforced.
     */
    fun add() {
        val input = _draft.value
        viewModelScope.launch {
            val rule = repo.add(input)
            if (rule == null) {
                _refused.value = true
                _message.value = refusal(input)
            } else {
                _refused.value = false
                _message.value = "Added ${rule.id}"
                _draft.value = ""
            }
        }
    }

    fun remove(site: BlockedSiteEntity) {
        viewModelScope.launch {
            repo.remove(site.id)
            _message.value = "Removed ${site.id}"
            _refused.value = false
        }
    }

    /**
     * Why an address was refused.
     *
     * Specific where it can be, because the two ways to get this wrong are
     * quite different: a blank field is a slip, while `news` is a search and
     * needs telling that a site has to look like a site.
     */
    private fun refusal(input: String): String = if (input.isBlank()) {
        "Type a site first, like bbc.co.uk."
    } else {
        "\"${input.trim()}\" is not a site address. Try bbc.co.uk, " +
            "or bbc.co.uk/news for one section of it."
    }
}
