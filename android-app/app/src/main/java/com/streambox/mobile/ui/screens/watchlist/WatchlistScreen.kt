package com.streambox.mobile.ui.screens.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streambox.mobile.data.remote.FeedItem
import com.streambox.mobile.ui.components.EmptyState
import com.streambox.mobile.ui.components.PosterCard

/**
 * Local watchlist (Phase 2, DataStore-backed). Tap opens the detail screen;
 * long-press removes the entry from local persistence.
 */
@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.warmUp() }
    val entries by viewModel.entries.collectAsState()

    if (entries.isEmpty()) {
        EmptyState(
            title = "Your watchlist is empty",
            message = "Add movies and series from their detail pages (tap the ♥).",
            modifier = modifier,
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(entries, key = { it.subjectId }) { entry ->
            PosterCard(
                item = FeedItem(
                    subjectId = entry.subjectId,
                    id = entry.subjectId,
                    title = entry.title,
                    poster = entry.poster,
                    cover = entry.poster,
                    score = entry.score,
                    releaseTime = entry.releaseTime,
                    subjectType = entry.subjectType,
                ),
                onClick = { onOpenDetail(entry.subjectId) },
                onLongClick = { viewModel.remove(entry.subjectId) },
            )
        }
    }
}
