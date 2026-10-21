package com.streambox.mobile.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.streambox.mobile.data.remote.CastMember
import com.streambox.mobile.data.remote.DetailItem
import com.streambox.mobile.data.remote.Episode
import com.streambox.mobile.data.remote.LanguageOption
import com.streambox.mobile.data.remote.Season
import com.streambox.mobile.ui.components.ErrorState
import com.streambox.mobile.ui.components.RatingBadge
import com.streambox.mobile.ui.components.SectionHeader
import com.streambox.mobile.ui.screens.search.userMessage
import com.streambox.mobile.ui.theme.AppAccent
import com.streambox.mobile.ui.theme.AppBackground
import com.streambox.mobile.ui.theme.AppOutline
import com.streambox.mobile.ui.theme.AppBrandAction
import com.streambox.mobile.ui.theme.AppSurface
import com.streambox.mobile.ui.theme.AppSurfaceHigh
import com.streambox.mobile.ui.theme.AppTextSecondary
import com.streambox.mobile.ui.theme.BrandGradient

/**
 * Movie & Series detail. Backdrop hero, meta chips, synopsis, dubs/languages,
 * cast rail, and (series only) season chips + episode list from /episodes.
 * The Play action is a clean Phase 3 hook and shows a toast for now.
 */
@Composable
fun DetailScreen(
    subjectId: String,
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlay: (season: Int, episode: Int, resourceId: String?) -> Unit,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(subjectId) { viewModel.load(subjectId) }
    val state by viewModel.state.collectAsState()

    when {
        state.loading -> com.streambox.mobile.ui.components.DetailSkeleton(modifier = modifier.fillMaxSize())
        state.error != null -> ErrorState(
            title = "Could not load details",
            message = state.error!!.userMessage(),
            onRetry = viewModel::retry,
            modifier = modifier.fillMaxSize(),
        )
        state.item == null -> ErrorState(
            title = "Title unavailable",
            message = "The backend could not resolve this id.",
            onRetry = viewModel::retry,
            modifier = modifier.fillMaxSize(),
        )
        else -> {
            val item = state.item!!
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                DetailHero(
                    item = item,
                    inWatchlist = state.inWatchlist,
                    onBack = onBack,
                    onToggleWatchlist = viewModel::toggleWatchlist,
                )

                // Meta line: score · year · runtime (all nullable and optional).
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RatingBadge(item.score)
                        Text(
                            text = listOfNotNull(
                                item.releaseTime?.takeIf { it.isNotBlank() && it != "N/A" },
                                item.runtime?.takeIf { it.isNotBlank() },
                                if (item.subjectType == 2) "Series" else "Movie",
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelLarge,
                            color = AppTextSecondary,
                        )
                    }

                    // Dubs / language resources from /detail languages.
                    if (item.languages.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader("Audio & Dubs")
                        Spacer(Modifier.height(6.dp))
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(item.languages.size) { idx ->
                                val lang = item.languages[idx]
                                val isDub = lang.type == "dub"
                                val buttonText = if (isDub) "${lang.name} (dub)" else lang.name
                                Button(
                                    onClick = {
                                        if (isDub) {
                                            // Dub = different subjectId → navigate to that title's detail
                                            onOpenDetail(lang.subjectId?.toString() ?: "")
                                        } else {
                                            // Resource = same subjectId, different resourceId → pass to player
                                            val safestSeason = state.selectedSeason ?: 1
                                            onPlay(safestSeason, 1, lang.id)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppSurface,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(buttonText, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    ExpandableSynopsis(description = item.description.orEmpty().ifBlank {
                        "No description was returned by the backend."
                    })

                    // CAST (real /detail cast list)
                    if (item.cast.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        SectionHeader("Cast")
                        Spacer(Modifier.height(6.dp))
                        CastRail(item.cast)
                    }

                    // SERIES: seasons + episodes
                    if (item.subjectType == 2) {
                        Spacer(Modifier.height(18.dp))
                        if (state.episodesError) {
                            // The series detail succeeded but /episodes failed. Do not
                            // silently render an empty season list; offer a retry.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Episodes couldn't load.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppTextSecondary,
                                )
                                TextButton(onClick = viewModel::retry) {
                                    Text("Retry")
                                }
                            }
                        } else if (state.seasons.isNotEmpty()) {
                            SectionHeader("Seasons")
                            SeasonPicker(state.seasons, state.selectedSeason, onSelected = viewModel::selectSeason)
                            val season = state.seasons.firstOrNull { it.seasonNumber == state.selectedSeason } ?: state.seasons.first()
                            EpisodeList(
                                season = season,
                                onPlayEpisode = { ep ->
                                    viewModel.selectSeason(season.seasonNumber)
                                    onPlay(
                                        season.seasonNumber,
                                        ep.episodeNumber.toIntOrNull() ?: 1,
                                        null,
                                    )
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    val safestSeason = state.selectedSeason ?: 1
                    PlayBar(
                        label = "Play",
                        onClick = { onPlay(safestSeason, 1, null) },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailHero(
    item: DetailItem,
    inWatchlist: Boolean,
    onBack: () -> Unit,
    onToggleWatchlist: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
            .background(AppSurfaceHigh),
    ) {
        SubcomposeAsyncImage(
            model = item.imageUrl.ifBlank { null },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize().background(AppSurfaceHigh)) },
            error = { Box(Modifier.fillMaxSize().background(AppSurfaceHigh)) },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, AppBackground.copy(alpha = 0.85f)),
                        startY = 300f,
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(AppBackground.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            IconButton(
                onClick = onToggleWatchlist,
                modifier = Modifier.background(AppBackground.copy(alpha = 0.55f), CircleShape),
            ) {
                val tint = if (inWatchlist) AppAccent else Color.White
                Icon(
                    imageVector = if (inWatchlist) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (inWatchlist) "Remove from watchlist" else "Add to watchlist",
                    tint = tint,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExpandableSynopsis(description: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded },
        )
        if (!expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "more",
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = true },
            )
        }
    }
}

@Composable
private fun CastRail(members: List<CastMember>) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(members.size) { idx ->
            val member = members[idx]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp),
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(AppSurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (member.avatar.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = member.avatar,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = { Box(Modifier.fillMaxSize().background(AppSurfaceHigh)) },
                            error = {
                                Box(Modifier.fillMaxSize().background(AppSurfaceHigh), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Person,
                                        contentDescription = null,
                                        tint = AppTextSecondary,
                                    )
                                }
                            },
                        )
                    } else {
                        Icon(
                                        Icons.Filled.Person,
                                        contentDescription = null,
                                        tint = AppTextSecondary,
                                    )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = member.role,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SeasonPicker(
    seasons: List<Season>,
    selected: Int?,
    onSelected: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons.size) { idx ->
            val season = seasons[idx]
            val isSelected = season.seasonNumber == selected
            Text(
                text = "S${season.seasonNumber}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
                    .background(if (isSelected) AppAccent else AppSurface)
                    .clickable { onSelected(season.seasonNumber) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                color = if (isSelected) AppBackground else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun EpisodeList(season: Season, onPlayEpisode: (Episode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppSurface),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        season.episodes.forEachIndexed { index, episode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayEpisode(episode) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 34.dp, height = 26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppOutline),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = episode.episodeNumber,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = episode.title.ifBlank { "Episode ${episode.episodeNumber}" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play episode ${episode.episodeNumber}",
                    tint = AppBrandAction,
                )
            }
            if (index != season.episodes.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AppOutline.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

/** Full-width primary action — the Phase-3 watch hook. */
@Composable
private fun PlayBar(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
    }
}
