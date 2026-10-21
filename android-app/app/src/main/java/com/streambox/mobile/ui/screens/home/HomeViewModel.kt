package com.streambox.mobile.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambox.mobile.data.repo.ContentError
import com.streambox.mobile.data.repo.ContentRepository
import com.streambox.mobile.data.remote.FeedItem
import com.streambox.mobile.data.remote.HomeSection
import com.streambox.mobile.data.remote.TrendingResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val trending: List<FeedItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    // Home sections can fail independently of /trending; keep feed browsable.
    val sectionsError: Boolean = false,
    val fatal: ContentError? = null,
)

class HomeViewModel(private val repo: ContentRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_state.value.loading && (_state.value.trending.isNotEmpty() || _state.value.sections.isNotEmpty())) {
            return
        }
        _state.value = _state.value.copy(loading = true, fatal = null)
        viewModelScope.launch {
            val trendingDeferred = async { repo.trending() }
            val homeDeferred = async { repo.homeSections() }

            val trendingResult = trendingDeferred.await()
            val homeResult = homeDeferred.await()

            val trendingItems = trendingResult
                .getOrDefault(TrendingResponse())
                .data
                // Browsable cards only: skip posts/actors (detail route not verified).
                .filter { it.isPost.not() && it.actionType != "actor" && it.subjectId.isNotBlank() }

            if (trendingItems.isEmpty() && trendingResult.isFailure && homeResult.isFailure) {
                // Both verified feeds failed -> error screen with retry.
                _state.value = _state.value.copy(
                    loading = false,
                    fatal = trendingResult.exceptionOrNull() as? ContentError ?: ContentError.Backend,
                )
            } else {
                _state.value = HomeUiState(
                    loading = false,
                    trending = trendingItems,
                    sections = homeResult.getOrDefault(emptyList<HomeSection>()),
                    sectionsError = homeResult.isFailure,
                )
            }
        }
    }
}
