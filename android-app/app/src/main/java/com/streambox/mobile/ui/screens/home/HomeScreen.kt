package com.streambox.mobile.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.streambox.mobile.data.remote.FeedItem
import com.streambox.mobile.ui.components.ErrorState
import com.streambox.mobile.ui.components.HomeSkeleton
import com.streambox.mobile.ui.components.SectionRow
import com.streambox.mobile.ui.components.metaLine
import com.streambox.mobile.ui.screens.search.userMessage
import com.streambox.mobile.ui.theme.AppBackground
import com.streambox.mobile.ui.theme.AppBrandAction
import com.streambox.mobile.ui.theme.AppSurfaceHigh
import com.streambox.mobile.ui.theme.AppTextSecondary
import com.streambox.mobile.ui.theme.BrandGradient

/** Home = hero (first trending item) + Trending Now rail + /home section rows. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDetail: (FeedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    if (state.loading && state.trending.isEmpty()) {
        HomeSkeleton(modifier = modifier.fillMaxSize())
        return
    }

    state.fatal?.let { fatal ->
        ErrorState(
            title = "Could not load content",
            message = fatal.userMessage(),
            onRetry = viewModel::refresh,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        state.trending.firstOrNull()?.let { hero ->
            HeroCard(item = hero, onOpen = { onOpenDetail(hero) })
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        if (state.trending.size > 1) {
            SectionRow(
                title = "Trending Now",
                items = state.trending,
                onItemClick = onOpenDetail,
            )
        }
        state.sections.forEach { section ->
            SectionRow(
                title = section.title ?: "More for you",
                items = section.items,
                onItemClick = onOpenDetail,
            )
        }
        if (state.sectionsError) {
            // /trending can still succeed when the secondary /home sections fail.
            // Keep the feed browsable but make the partial failure visible/retryable.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Some rows couldn't load.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                )
                TextButton(onClick = viewModel::refresh) {
                    Text("Retry")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Featured "hero" card for the top trending title, with gradient scrub + play chip. */
@Composable
private fun HeroCard(item: FeedItem, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.dp))
            .background(AppSurfaceHigh)
            .clickable(onClick = onOpen),
    ) {
        SubcomposeAsyncImage(
            model = item.imageUrl.ifBlank { null },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(Modifier.fillMaxSize().background(AppSurfaceHigh))
            },
            error = {
                Box(Modifier.fillMaxSize().background(AppSurfaceHigh))
            },
        )
        // Cinematic bottom gradient for text legibility.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, AppBackground.copy(alpha = 0.95f)),
                        startY = 380f,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = metaLine(item).ifBlank { "Featured" },
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PlayChip(onOpen)
            }
        }
    }
}

@Composable
private fun PlayChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Play",
            tint = Color.White,
        )
        Text(
            text = "Play",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
