package com.streambox.mobile.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility as OverlayVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.streambox.mobile.ui.components.ErrorState
import com.streambox.mobile.ui.theme.AppBackground
import com.streambox.mobile.ui.theme.AppBrandAction
import com.streambox.mobile.ui.theme.AppSurface
import com.streambox.mobile.ui.theme.AppTextSecondary
import kotlinx.coroutines.delay

private const val SEEK_DELTA_MS = 10_000L

/**
 * Phase 3 player screen with premium streaming UI:
 * - Centered play/pause overlay when paused
 * - 10-second rewind / forward buttons
 * - Auto-hiding controls with tap-to-show
 * - Immersive fullscreen / orientation handling
 * - Previous / next episode navigation with auto-next countdown
 * - Subtitle + audio track selection
 * - Meaningful loading / buffering / error states
 */
@Composable
fun PlayerScreen(
    subjectId: String,
    initialSeason: Int,
    initialEpisode: Int,
    displayTitle: String,
    posterUrl: String?,
    isSeries: Boolean,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resourceId: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val configuration = LocalConfiguration.current
    val isFullscreen = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Resolve once per subject identity; subsequent episode switches are VM-managed.
    LaunchedEffect(subjectId, resourceId) {
        if (state.subjectId != subjectId || state.resourceId != resourceId) {
            viewModel.resolve(
                subjectId = subjectId,
                season = if (isSeries) initialSeason.coerceAtLeast(1) else 1,
                episode = if (isSeries) initialEpisode.coerceAtLeast(1) else 1,
                displayTitle = displayTitle,
                posterUrl = posterUrl,
                isSeries = isSeries,
                resourceId = resourceId,
            )
            if (isSeries) viewModel.primeEpisodes(subjectId, initialSeason, initialEpisode)
        }
    }

    // Immersive system bars in fullscreen.
    val view = LocalView.current
    val context = LocalContext.current
    val activity = context.findActivity()
    val exitPlayer: () -> Unit = {
        // Leaving a fullscreen player must not strand the previous screen in
        // landscape or with hidden system bars.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }
    BackHandler { exitPlayer() }
    DisposableEffect(isFullscreen) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            if (isFullscreen) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            modifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(230.dp)
            },
        ) {
            // --- video surface ---
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                    }
                },
                update = { view -> view.player = viewModel.player },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )

            // --- center state overlays ---
            when (state.status) {
                PlayerStatus.RESOLVING -> OverlayPanel("Resolving source…")
                PlayerStatus.PREPARING, PlayerStatus.BUFFERING -> OverlayPanel("Loading…")
                PlayerStatus.ERROR, PlayerStatus.UNAVAILABLE -> {
                    ErrorState(
                        title = if (state.status == PlayerStatus.ERROR) "Playback error" else "Source unavailable",
                        message = state.errorDetail.ifBlank { null },
                        onRetry = viewModel::retry,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {}
            }

            // --- center play/pause overlay (premium: tap to toggle) ---
            if (state.status == PlayerStatus.PAUSED && !state.episodeEnded && state.controlsVisible.not()) {
                CenterPlayPause(vm = viewModel)
            }

            // --- episode-ended / auto-next overlay ---
            if (state.episodeEnded && state.isSeries) {
                EpisodeEndedOverlay(
                    state = state,
                    vm = viewModel,
                    onNextClick = {
                        state.nextEpisode?.let { next ->
                            viewModel.cancelAutoNext()
                            viewModel.loadEpisode(state.season, next)
                        }
                    },
                    onCancelClick = { viewModel.cancelAutoNext() },
                )
            }

            // --- tap area toggles controls (behind controls so bars stay clickable) ---
            if (state.status in setOf(PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.BUFFERING)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { viewModel.showControls(!state.controlsVisible) },
                )
            }

            // --- controls overlay ---
            ControlsVisibilityHost(
                visible = state.controlsVisible && state.status in setOf(
                    PlayerStatus.PLAYING, PlayerStatus.PAUSED,
                ),
            ) {
                ControlsOverlay(
                    state = state,
                    isFullscreen = isFullscreen,
                    isPlaying = state.status == PlayerStatus.PLAYING,
                    vm = viewModel,
                    onBack = exitPlayer,
                    onToggleFullscreen = { activity ->
                        activity.requestedOrientation = if (isFullscreen) {
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    },
                )
            }
        }

        if (!isFullscreen) {
            PortraitInfoSection(state, viewModel)
        }
    }
}

/** Centered semi-transparent play/pause button that appears when paused & controls hidden. */
@Composable
private fun CenterPlayPause(vm: PlayerViewModel) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Overlay shown when an episode finishes, offering next episode / autoplay countdown. */
@Composable
private fun EpisodeEndedOverlay(
    state: PlayerUiState,
    vm: PlayerViewModel,
    onNextClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(24.dp)
                .clip(RoundedCornerShape(20.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next episode",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Episode finished",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Up next",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onNextClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppBrandAction,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Next", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onCancelClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.3f),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun OverlayPanel(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

/** Top bar + seek bar overlayed on the video surface. */
@Composable
private fun ControlsOverlay(
    state: PlayerUiState,
    isFullscreen: Boolean,
    isPlaying: Boolean,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onToggleFullscreen: (Activity) -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    var showSettingsDialog by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableStateOf(0) }
    LaunchedEffect(state.controlsVisible, state.status, interactionTick) {
        if (state.controlsVisible && state.status == PlayerStatus.PLAYING) {
            delay(3_500)
            vm.showControls(false)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Top controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close player", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.isSeries) {
                    Text(
                        text = "Season ${state.season} · Episode ${state.episode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextSecondary,
                    )
                }
            }
            IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.size(36.dp)) {
                Text("CC", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
            }
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Playback settings",
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = { activity?.let(onToggleFullscreen) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Filled.CloseFullscreen else Icons.Filled.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                    tint = Color.White,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Bottom controls
        Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp)) {
            if (state.resumeAppliedMs > 0) {
                Text(
                    text = "Resumed from ${formatTime(state.resumeAppliedMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                )
            }
            SeekBar(state = state, vm = vm, onUserSeeking = { interactionTick++ })

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 10s rewind
                IconButton(
                    onClick = { vm.seekRelative(-SEEK_DELTA_MS) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = "Rewind 10 seconds",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Play/pause
                IconButton(onClick = vm::togglePlayPause, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }

                // 10s forward
                IconButton(
                    onClick = { vm.seekRelative(SEEK_DELTA_MS) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = "Forward 10 seconds",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Prev / next episode (series only)
                if (state.isSeries) {
                    IconButton(
                        onClick = { state.prevEpisode?.let { vm.loadEpisode(state.season, it) } },
                        enabled = state.prevEpisode != null,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous episode",
                            tint = if (state.prevEpisode != null) Color.White else Color(0x66FFFFFF),
                        )
                    }
                    IconButton(
                        onClick = { state.nextEpisode?.let { vm.loadEpisode(state.season, it) } },
                        enabled = state.nextEpisode != null,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next episode",
                            tint = if (state.nextEpisode != null) Color.White else Color(0x66FFFFFF),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextSecondary,
                )
            }
        }
    }

    if (showSettingsDialog) {
        PlaybackSettingsDialog(
            state = state,
            vm = vm,
            onDismiss = { showSettingsDialog = false },
        )
    }
}

/** Buffered-range seek slider with drag preview. */
@Composable
private fun SeekBar(state: PlayerUiState, vm: PlayerViewModel, onUserSeeking: () -> Unit = {}) {
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    val maxMs = state.durationMs.coerceAtLeast(1L)
    val shown = (dragPosition ?: state.positionMs).coerceIn(0L, maxMs)

    Slider(
        value = shown.toFloat(),
        onValueChange = { value ->
            dragPosition = value.toLong()
            onUserSeeking()
        },
        onValueChangeFinished = {
            dragPosition?.let { vm.seekTo(it) }
            dragPosition = null
        },
        valueRange = 0f..maxMs.toFloat(),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color(0x33FFFFFF),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
    )
}

@Composable
private fun PortraitInfoSection(state: PlayerUiState, vm: PlayerViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
        if (state.isSeries) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Season ${state.season} · Episode ${state.episode}",
                style = MaterialTheme.typography.labelLarge,
                color = AppTextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { state.prevEpisode?.let { vm.loadEpisode(state.season, it) } },
                    enabled = state.prevEpisode != null,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = null)
                    Text("Prev")
                }
                Button(
                    onClick = { state.nextEpisode?.let { vm.loadEpisode(state.season, it) } },
                    enabled = state.nextEpisode != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppBrandAction,
                        contentColor = Color.White,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Next episode")
                    Icon(Icons.Filled.SkipNext, contentDescription = null)
                }
            }
        }
        if (state.status == PlayerStatus.PAUSED) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Paused — progress kept locally",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Playback settings dialog: audio/subtitle language, playback speed, video quality. */
@Composable
private fun PlaybackSettingsDialog(state: PlayerUiState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    val video = state.tracks.filter { it.type == "video" }
    val audio = state.tracks.filter { it.type == "audio" }
    val text = state.tracks.filter { it.type == "text" }
    var subsOn by remember { mutableStateOf(vm.subtitlesAvailable() && vm.subtitlesEnabled()) }
    var speed by remember { mutableStateOf(state.playbackSpeed) }

    AlertDialog(
        containerColor = AppSurface,
        onDismissRequest = onDismiss,
        title = { Text("Playback settings", color = Color.White) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (video.isNotEmpty()) TrackGroup("Video quality", video, vm)
                if (audio.isNotEmpty()) TrackGroup("Audio language", audio, vm)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Subtitles", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = subsOn, onCheckedChange = { checked ->
                        subsOn = checked
                        vm.toggleSubtitles()
                    })
                }
                if (text.isNotEmpty()) {
                    Text(
                        text = text.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Playback speed",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { sp ->
                        val isSelected = speed == sp
                        Button(
                            onClick = {
                                speed = sp
                                vm.setPlaybackSpeed(sp)
                            },
                            enabled = sp != state.playbackSpeed,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) AppBrandAction else Color.Black.copy(alpha = 0.3f),
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("${sp}x", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (video.isEmpty() && audio.isEmpty() && text.isEmpty()) {
                    Text(
                        text = "This stream reports no selectable tracks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun TrackGroup(title: String, options: List<TrackOption>, vm: PlayerViewModel) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        options.take(12).forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectTrack(option) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (option.selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (option.selected) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ControlsVisibilityHost(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    OverlayVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        content()
    }
}

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
