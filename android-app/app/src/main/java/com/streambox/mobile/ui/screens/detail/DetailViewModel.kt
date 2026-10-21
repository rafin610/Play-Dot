package com.streambox.mobile.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambox.mobile.data.repo.ContentError
import com.streambox.mobile.data.repo.ContentRepository
import com.streambox.mobile.data.local.WatchlistStore
import com.streambox.mobile.data.remote.DetailItem
import com.streambox.mobile.data.remote.Season
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val item: DetailItem? = null,
    /** Populated only for subjectType == 2 (series), from GET /episodes. */
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int? = null,
    /** True when the series loaded but its episode list did not. */
    val episodesError: Boolean = false,
    val inWatchlist: Boolean = false,
    val error: ContentError? = null,
)

class DetailViewModel(
    private val repo: ContentRepository,
    private val watchlist: WatchlistStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val watchlistIds = MutableStateFlow<Set<String>>(emptySet())
    private var subjectId: String = ""

    init {
        viewModelScope.launch {
            watchlist.observe().collect { list ->
                watchlistIds.value = list.map { it.subjectId }.toSet()
                _state.value = _state.value.copy(inWatchlist = subjectId in watchlistIds.value)
            }
        }
    }

    fun load(id: String) {
        subjectId = id
        loadNow()
    }

    fun retry() = loadNow()

    fun selectSeason(seasonNumber: Int) {
        _state.value = _state.value.copy(selectedSeason = seasonNumber)
    }

    /** Local watchlist toggle (DataStore-based; provider watchlist endpoints unverified in Phase 1). */
    fun toggleWatchlist() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            if (_state.value.inWatchlist) {
                watchlist.remove(item.subjectId)
            } else {
                watchlist.toggle(
                    com.streambox.mobile.data.remote.FeedItem(
                        subjectId = item.subjectId,
                        id = item.id,
                        title = item.title,
                        cover = item.cover,
                        poster = item.poster,
                        score = item.score,
                        releaseTime = item.releaseTime,
                        subjectType = item.subjectType,
                        runtime = item.runtime,
                    ),
                )
            }
        }
    }

    private fun loadNow() {
        _state.value = DetailUiState(loading = true)
        viewModelScope.launch {
            repo.detail(subjectId).fold(
                onSuccess = { item ->
                    var seasons: List<Season> = emptyList()
                    var episodesError = false
                    if (item.subjectType == 2) {
                        val episodesResult = repo.episodes(item.subjectId)
                        seasons = episodesResult.getOrDefault(emptyList())
                        episodesError = episodesResult.isFailure
                    }
                    _state.value = DetailUiState(
                        loading = false,
                        item = item,
                        seasons = seasons,
                        selectedSeason = seasons.minOfOrNull { it.seasonNumber },
                        episodesError = episodesError,
                        inWatchlist = subjectId in watchlistIds.value,
                    )
                },
                onFailure = { e ->
                    _state.value = DetailUiState(
                        loading = false,
                        error = e as? ContentError ?: ContentError.Backend,
                    )
                },
            )
        }
    }
}
