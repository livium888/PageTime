package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pagetime.app.PageTimeApp
import com.pagetime.app.blocker.SiteMode
import com.pagetime.app.data.local.AllowedSiteEntity
import com.pagetime.app.data.local.BlockedSiteEntity
import com.pagetime.app.domain.GateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Both of the reader's site rule lists, and which one is currently active —
 * see [SiteMode]. The screen this backs is one screen rather than two
 * because the two lists share everything except which direction they work
 * in: the same add field, the same host+path parsing, the same session
 * that opens either one.
 */
class SiteRulesViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val blockRepo = container.blockedSiteRepository
    private val allowRepo = container.allowedSiteRepository
    private val settingsRepo = container.settingsRepository

    val mode = settingsRepo.settings.map { it.siteMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SiteMode.BLOCKLIST)

    /** Every block rule, kept live even in allowlist mode so switching back loses nothing. */
    val blockedSites = blockRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every allow rule, kept live even in blocklist mode for the same reason. */
    val allowedSites = allowRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether rules may be LOOSENED right now — removing a block rule, adding an allow rule. */
    val gate = container.balanceManager.gate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GateState.Unknown)

    val hardLockUntil = settingsRepo.settings
        .map { it.hardLockUntil }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()

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
     * Stores what was typed into whichever list is currently active, or
     * explains why it cannot be. See [com.pagetime.app.data.BlockedSiteRepository.add].
     */
    fun add() {
        val input = _draft.value
        val addingToAllowlist = mode.value == SiteMode.ALLOWLIST
        viewModelScope.launch {
            val rule = if (addingToAllowlist) allowRepo.add(input) else blockRepo.add(input)
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

    /** Removing a block rule loosens the blocklist — see [GateState.canRemoveBlockedApps]. */
    fun removeBlocked(site: BlockedSiteEntity) {
        viewModelScope.launch {
            blockRepo.remove(site.id)
            _message.value = "Removed ${site.id}"
            _refused.value = false
        }
    }

    /** Removing an allow rule TIGHTENS the allowlist, so it is never fenced. */
    fun removeAllowed(site: AllowedSiteEntity) {
        viewModelScope.launch {
            allowRepo.remove(site.id)
            _message.value = "Removed ${site.id}"
            _refused.value = false
        }
    }

    /**
     * Switches which list is active. The screen fences switching FROM an
     * allowlist BACK TO a blocklist — see [GateState.canSwitchToBlocklist] —
     * this just performs the switch once permitted.
     */
    fun setMode(target: SiteMode) {
        viewModelScope.launch { settingsRepo.setSiteMode(target) }
    }

    private fun refusal(input: String): String = if (input.isBlank()) {
        "Type a site first, like bbc.co.uk."
    } else {
        "\"${input.trim()}\" is not a site address. Try bbc.co.uk, " +
            "or bbc.co.uk/news for one section of it."
    }
}
