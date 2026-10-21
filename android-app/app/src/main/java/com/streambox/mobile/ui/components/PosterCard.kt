package com.streambox.mobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.streambox.mobile.ui.theme.AppAccent
import com.streambox.mobile.ui.theme.AppBackground
import com.streambox.mobile.ui.theme.AppRating
import com.streambox.mobile.ui.theme.AppSurfaceHigh
import com.streambox.mobile.ui.theme.AppSurfaceHigher
import com.streambox.mobile.ui.theme.AppTextSecondary

/** Artist-style brush placeholder while a poster streams in / fails to load. */
private val PosterPlaceholderBrush = Brush.linearGradient(
    colors = listOf(AppSurfaceHigh, AppSurfaceHigher, AppSurfaceHigh),
)

private const val POSTER_RATIO = 2f / 3f

/** Small colored type indicator on the poster (SERIES/POST/PEOPLE/COLLECTION). */
@Composable
private fun TypeChip(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(AppBackground.copy(alpha = 0.75f), RoundedCornerShape(100))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AppAccent,
        )
    }
}

@Composable
fun RatingBadge(score: String?, modifier: Modifier = Modifier) {
    if (score.isNullOrBlank() || score == "N/A") return
    Row(
        modifier = modifier
            .background(AppRating, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "★ ${score.trim()}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AppBackground,
        )
    }
}

/** "2010 · 2h 28m" style meta line built strictly from Phase 1 mapped fields. */
fun metaLine(item: FeedItem): String = listOfNotNull(
    item.releaseTime?.takeIf { it.isNotBlank() && it != "N/A" },
    item.runtime?.takeIf { it.isNotBlank() },
).joinToString("  ·  ")

private fun typeLabel(item: FeedItem): String? = when {
    item.isPost -> "POST"
    item.actionType == "actor" -> "PEOPLE"
    item.subjectType == 2 -> "SERIES"
    item.isCollection -> "COLLECTION"
    else -> null
}

/**
 * Reusable poster card used across Home, Search, Detail rows and Watchlist.
 * Portrait 2:3 artwork, title + meta line under the image, rating chip overlay.
 * Clickable only for items the backend can open (detail routes).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: FeedItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_RATIO)
                .clip(RoundedCornerShape(14.dp))
                .background(AppSurfaceHigh),
        ) {
            SubcomposeAsyncImage(
                model = item.imageUrl.ifBlank { null },
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize().background(PosterPlaceholderBrush))
                },
                error = {
                    Box(Modifier.fillMaxSize().background(PosterPlaceholderBrush))
                },
            )
            if (!item.isPost && item.score != null && item.score != "N/A") {
                RatingBadge(
                    score = item.score,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
            typeLabel(item)?.let { TypeChip(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(item),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Horizontal poster rail with fixed-width cards (240dp cards → ~144dp wide). */
@Composable
fun PosterRail(
    items: List<FeedItem>,
    onItemClick: (FeedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        items(items.size, key = { idx -> items[idx].subjectId + idx }) { idx ->
            PosterCard(
                item = items[idx],
                onClick = { onItemClick(items[idx]) },
                modifier = Modifier.width(128.dp),
            )
        }
    }
}
