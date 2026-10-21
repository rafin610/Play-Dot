package com.streambox.mobile.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambox.mobile.data.repo.ContentError
import com.streambox.mobile.data.repo.ContentRepository
import com.streambox.mobile.data.remote.FeedItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<FeedItem> = emptyList(),
    /** True once the user has actually submitted/typed a real query (drives empty state). */
    val touched: Boolean = false,
    val error: ContentError? = null,
)

class SearchViewModel(private val repo: ContentRepository) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0

    fun onQueryChange(newQuery: String) {
        _state.value = _state.value.copy(query = newQuery)
        // Debounced live search (400ms). The generation must be advanced and
        // stored, otherwise a slow older request can overwrite newer results.
        job?.cancel()
        val gen = ++generation
        job = viewModelScope.launch {
            delay(400)
            execute(newQuery, gen)
        }
    }

    fun retry() {
        job?.cancel()
        val gen = ++generation
        execute(_state.value.query, gen)
    }

    fun clearQuery() {
        onQueryChange("")
    }

    private fun execute(query: String, gen: Int) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            if (gen == generation) {
                _state.value = SearchUiState(query = query, touched = false)
            }
            return
        }
        _state.value = _state.value.copy(loading = true, touched = true, error = null)
        viewModelScope.launch {
            if (gen != generation) return@launch
            repo.search(trimmed)
                .fold(
                    onSuccess = { raw ->
                        val items = raw
                            .filter { it.isPost.not() && it.actionType != "actor" && it.subjectId.isNotBlank() }
                        if (gen == generation) {
                            _state.value = _state.value.copy(loading = false, results = items, error = null)
                        }
                    },
                    onFailure = { e ->
                        if (gen == generation) {
                            _state.value = _state.value.copy(
                                loading = false,
                                results = emptyList(),
                                error = e as? ContentError ?: ContentError.Backend,
                            )
                        }
                    },
                )
        }
    }
}

/** Human-readable error copy derived from the repository taxonomy. */
fun ContentError.userMessage(): String = when (this) {
    ContentError.Network -> "Can't reach the server. Check your internet connection and try again."
    is ContentError.Malformed -> "The server returned an unexpected response. Please try again."
    ContentError.Backend -> "The server couldn't complete this request. Please try again."
}

/** Mirrors backend subjectType semantics from Phase 1 (1=movie, 2=series, 6/8/9=UGC). */
fun FeedItem.isBrowsable(): Boolean = subjectType == null || subjectType == 1 || subjectType == 2
