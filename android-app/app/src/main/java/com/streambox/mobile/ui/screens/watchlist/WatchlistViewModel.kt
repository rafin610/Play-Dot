package com.streambox.mobile.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambox.mobile.data.local.WatchlistEntry
import com.streambox.mobile.data.local.WatchlistStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Watchlist is local-only in Phase 2 (DataStore). Entries live on the device;
 * the provider account watchlist endpoints were not verified in Phase 1.
 */
class WatchlistViewModel(private val store: WatchlistStore) : ViewModel() {

    val entries: StateFlow<List<WatchlistEntry>> = store.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Guarantees the persisted list is loaded as soon as the tab opens. */
    fun warmUp() {
        viewModelScope.launch { store.load() }
    }

    fun remove(subjectId: String) {
        viewModelScope.launch { store.remove(subjectId) }
    }
}
