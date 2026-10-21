package com.streambox.mobile.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streambox.mobile.data.remote.FeedItem
import com.streambox.mobile.ui.components.EmptyState
import com.streambox.mobile.ui.components.ErrorState
import com.streambox.mobile.ui.components.GridSkeleton
import com.streambox.mobile.ui.components.PosterCard
import com.streambox.mobile.ui.theme.AppTextSecondary

/**
 * Search over GET /search?q= with loading / results / empty / error states.
 * Results render in a 3-column poster grid.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (FeedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = {
                Text("Search movies & series", color = AppTextSecondary)
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            },
            trailingIcon = if (state.query.isNotEmpty()) {
                { IconButton(onClick = viewModel::clearQuery) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        Spacer(Modifier.height(14.dp))

        when {
            // Error state
            state.error != null -> ErrorState(
                title = "Search failed",
                message = state.error!!.userMessage(),
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxSize(),
            )
            // Empty (reached only when a real query returned nothing)
            state.touched && !state.loading && state.results.isEmpty() && state.error == null -> EmptyState(
                title = "No results found",
                message = "Try a different title or check the spelling.",
                modifier = Modifier.fillMaxSize(),
            )
            // Loading
            state.loading -> GridSkeleton(modifier = Modifier.fillMaxSize())
            // Browse prompt before first interaction
            !state.touched -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Find something to watch",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Search movies and series, then pick up where you left off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            // Results
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.results, key = { "${it.subjectId}-${it.id}" }) { item ->
                    PosterCard(item = item, onClick = { onOpenDetail(item) })
                }
            }
        }
    }
}
