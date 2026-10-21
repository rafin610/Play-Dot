package com.streambox.mobile.ui.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.UnstableApi
import com.streambox.mobile.data.local.PlaybackPosition
import com.streambox.mobile.data.local.ProgressStore
import com.streambox.mobile.data.local.ResumePolicy
import com.streambox.mobile.data.remote.Season
import com.streambox.mobile.data.repo.ContentError
import com.streambox.mobile.data.repo.ContentRepository
import com.streambox.mobile.data.remote.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToLong

private const val TAG = "PlayerViewModel"

/** Referer the media CDN expects (matches the backend client's own header). */
private const val MEDIA_REFERER = "https://apig.inmoviebox.com/"

/** High-level playback status exposed to the UI. */
enum class PlayerStatus { RESOLVING, PREPARING, BUFFERING, PLAYING, PAUSED, ERROR, UNAVAILABLE }

/** One selectable audio/video/text entry for the track-selection dialog. */
data class TrackOption(
    val type: String, // "video" | "audio" | "text"
    val label: String,
    val trackUid: String,
    val selected: Boolean,
    val override: TrackSelectionOverride?,
)

data class PlayerUiState(
    val status: PlayerStatus = PlayerStatus.RESOLVING,
    val title: String = "",
    val subtitle: String = "",
    val subjectId: String = "",
    val season: Int = 1,
    val episode: Int = 1,
    val isSeries: Boolean = false,
    val resourceId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val controlsVisible: Boolean = true,
    val showError: Boolean = false,
    val errorRetryable: Boolean = false,
    val errorDetail: String = "",
    val tracks: List<TrackOption> = emptyList(),
    val prevEpisode: Int? = null,
    val nextEpisode: Int? = null,
    val resumeAppliedMs: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val episodeEnded: Boolean = false,
)

/**
 * Owns the Media3 ExoPlayer instance for exactly one Screen-attached lifecycle:
 * created on resolve, [release]d when the ViewModel is cleared.
 * All playback data comes from the backend's verified `GET /stream/{subjectId}`
 * contract; URLs are never fabricated.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(
    private val repo: ContentRepository,
    private val progressStore: ProgressStore,
    val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    var player: ExoPlayer? = null
        private set

    private var tickJob: Job? = null
    private var autoNextJob: Job? = null
    private var resolveJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishPlayerSnapshot()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishPlayerSnapshot()
            if (playbackState == Player.STATE_ENDED && _state.value.isSeries) {
                scheduleAutoNext()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _state.value = _state.value.copy(playbackSpeed = playbackParameters.speed)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: code=${error.errorCode} msg=${error.message}", error)
            _state.value = _state.value.copy(
                status = PlayerStatus.ERROR,
                showError = true,
                errorRetryable = true,
                errorDetail = playbackErrorMessage(error),
            )
        }
    }

    // ------------------------------------------------------------------ resolve

    fun resolve(
        subjectId: String,
        season: Int,
        episode: Int,
        displayTitle: String,
        posterUrl: String? = null,
        isSeries: Boolean = false,
        resourceId: String? = null,
    ) {
        cancelAutoNext()
        // Only one resolve may be in flight. Without this, quickly switching
        // episodes can let an older request finish last and attach the wrong
        // stream (or leak a second player).
        resolveJob?.cancel()
        _state.value = PlayerUiState(
            status = PlayerStatus.RESOLVING,
            title = displayTitle,
            subjectId = subjectId,
            season = season,
            episode = episode,
            isSeries = isSeries,
            resourceId = resourceId,
            episodeEnded = false,
        )
        resolveJob = viewModelScope.launch {
            startResolve(subjectId, season, episode, posterUrl, resourceId)
        }
    }

    fun retry() {
        val s = _state.value
        // Preserve the selected dub/resource on retry; otherwise retry would
        // silently fall back to the default audio.
        resolve(
            subjectId = s.subjectId,
            season = s.season,
            episode = s.episode,
            displayTitle = s.title,
            isSeries = s.isSeries,
            resourceId = s.resourceId,
        )
    }

    private suspend fun startResolve(
        subjectId: String,
        season: Int,
        episode: Int,
        posterUrl: String? = null,
        resourceId: String? = null,
    ) {
        _state.value = _state.value.copy(status = PlayerStatus.RESOLVING, showError = false)
        val streamResult = repo.resolveStream(subjectId, season, episode, resourceId = resourceId)
        // A cancelled resolve must never prepare a player or show an error.
        currentCoroutineContext().ensureActive()

        streamResult.fold(
            onSuccess = { stream ->
                if (stream.url.isBlank()) {
                    _state.value = _state.value.copy(
                        status = PlayerStatus.UNAVAILABLE,
                        showError = true,
                        errorRetryable = true,
                        errorDetail = "The backend returned no playable source for this title.",
                    )
                    return@fold
                }
                preparePlayer(subjectId, season, episode, stream, posterUrl)
            },
            onFailure = { e ->
                val err = e as? ContentError ?: ContentError.Backend
                _state.value = _state.value.copy(
                    status = PlayerStatus.UNAVAILABLE,
                    showError = true,
                    errorRetryable = true,
                    errorDetail = when (err) {
                        is ContentError.Network ->
                            "Can't reach the server. Check your internet connection and try again."
                        is ContentError.Malformed -> "The server returned unexpected playback data."
                        ContentError.Backend ->
                            "No authorized playable source for this title right now."
                    },
                )
            },
        )
    }

    // ------------------------------------------------------------- Media3 wiring

    private suspend fun preparePlayer(
        subjectId: String,
        season: Int,
        episode: Int,
        stream: StreamResponse,
        posterUrl: String?,
    ) {
        _state.value = _state.value.copy(status = PlayerStatus.PREPARING)

        val mime = when {
            stream.isHls || stream.url.substringBefore('?').endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            stream.url.substringBefore('?').endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        val headers = buildMap<String, String> {
            // Media CDNs typically require Referer + Origin in addition to Cookie + User-Agent.
            put("Referer", MEDIA_REFERER)
            put("Origin", "https://apig.inmoviebox.com")
            stream.cookie?.takeIf { it.isNotBlank() }?.let { raw ->
                val value = if (raw.contains("=")) raw else "signCookie=$raw"
                put("Cookie", value)
            }
        }

        val configs = stream.subtitles.mapNotNull { st ->
            val url = st.url ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                .setMimeType(mimeForSubtitle(url))
                .setLanguage(st.lan ?: st.lanName ?: "und")
                .setLabel(st.lanName ?: st.lan ?: "Subtitles")
                .build()
        }

        val metaBuilder = MediaMetadata.Builder().setTitle(_state.value.title)
        posterUrl?.let { metaBuilder.setArtworkUri(android.net.Uri.parse(it)) }

        val mediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(mime)
            .setMediaMetadata(metaBuilder.build())
            .setSubtitleConfigurations(configs)
            .build()

        val resumePositionMs = decideResumeOffset(subjectId, season, episode)

        // Release any previous player before creating a new one (cookie differs per resolve).
        player?.let { old ->
            old.removeListener(listener)
            old.release()
        }
        player = null

        // DefaultLoadControl with sensible buffering targets for streaming.
        // Media3 requires minBufferMs >= bufferForPlaybackMs and
        // maxBufferMs >= minBufferMs; violating that throws at construction.
        var newPlayer: ExoPlayer? = null
        try {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                )
                .setTargetBufferBytes(-1)
                .build()

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(EXO_UA)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)

            newPlayer = ExoPlayer.Builder(appContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                .build()

            newPlayer.addListener(listener)
            newPlayer.playWhenReady = true
            player = newPlayer
            startTicking()
            newPlayer.setMediaItem(mediaItem, max(0L, resumePositionMs))
            newPlayer.prepare()

            _state.value = _state.value.copy(
                status = PlayerStatus.BUFFERING,
                durationMs = max(_state.value.durationMs, stream.duration * 1000L),
                resumeAppliedMs = resumePositionMs,
            )
        } catch (e: CancellationException) {
            runCatching { newPlayer?.removeListener(listener) }
            runCatching { newPlayer?.release() }
            if (player === newPlayer) player = null
            throw e
        } catch (e: Exception) {
            // Surface player init failures as a retryable player error instead of
            // letting an uncaught exception close the app.
            Log.e(TAG, "Could not initialise ExoPlayer", e)
            runCatching { newPlayer?.removeListener(listener) }
            runCatching { newPlayer?.release() }
            player = null
            tickJob?.cancel()
            _state.value = _state.value.copy(
                status = PlayerStatus.ERROR,
                showError = true,
                errorRetryable = true,
                errorDetail = "Could not start the player: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun mimeForSubtitle(url: String): String = when {
        url.substringBefore('?').endsWith(".vtt") -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private suspend fun decideResumeOffset(subjectId: String, season: Int, episode: Int): Long {
        val stored = progressStore.find(subjectId, season, episode) ?: return 0L
        val decided = ResumePolicy.decide(stored.positionMs, stored.durationMs)
        return decided ?: 0L
    }

    // -------------------------------------------------------------- progress I/O

    private val persistScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Background progress write failed", e)
        },
    )

    private fun persistProgress() {
        val p = player ?: return
        val s = _state.value
        val pos = p.currentPosition
        val dur = if (p.duration > 0) p.duration else s.durationMs
        if (pos <= 5_000L || p.playbackState == Player.STATE_IDLE) return
        persistScope.launch {
            progressStore.save(
                PlaybackPosition(
                    subjectId = s.subjectId,
                    season = s.season,
                    episode = s.episode,
                    positionMs = pos,
                    durationMs = dur,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var lastSaveAt = 0L
            while (isActive) {
                publishPlayerSnapshot()
                val now = System.currentTimeMillis()
                if (now - lastSaveAt >= PROGRESS_SAVE_INTERVAL_MS) {
                    persistProgress()
                    lastSaveAt = now
                }
                delay(500)
            }
        }
    }

    private fun publishPlayerSnapshot() {
        val p = player ?: return
        val status = when {
            p.playbackState == Player.STATE_IDLE && p.playerError != null -> PlayerStatus.ERROR
            p.playbackState == Player.STATE_IDLE -> PlayerStatus.PAUSED
            p.playbackState == Player.STATE_BUFFERING -> PlayerStatus.BUFFERING
            p.playbackState == Player.STATE_ENDED -> PlayerStatus.PAUSED
            p.playWhenReady -> PlayerStatus.PLAYING
            else -> PlayerStatus.PAUSED
        }
        _state.value = _state.value.copy(
            status = status,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (p.duration > 0) p.duration else _state.value.durationMs,
            bufferedMs = p.bufferedPosition.coerceAtLeast(0),
            tracks = snapshotTracks(),
        )
    }

    // ------------------------------------------------------------------ controls

    fun play() = player?.play()
    fun pause() = player?.pause()
    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        val dur = if (p.duration > 0) p.duration else _state.value.durationMs
        val bounded = positionMs.coerceIn(0L, if (dur > 0) (dur - 1_000L).coerceAtLeast(0) else positionMs)
        p.seekTo(bounded)
        publishPlayerSnapshot()
    }

    fun seekRelative(deltaMs: Long) {
        val p = player ?: return
        val newPos = (p.currentPosition + deltaMs).coerceAtLeast(0)
        seekTo(newPos)
    }

    fun setPlaybackSpeed(speed: Float) {
        val p = player ?: return
        p.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
    }

    fun showControls(use: Boolean) {
        _state.value = _state.value.copy(controlsVisible = use)
    }

    // -------------------------------------------------------------- track select

    private fun snapshotTracks(): List<TrackOption> {
        val p = player ?: return emptyList()
        val current = p.currentTracks
        val out = mutableListOf<TrackOption>()
        current.groups.forEach { group ->
            val type = group.type
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val fmt = group.getTrackFormat(i)
                val label = when (type) {
                    androidx.media3.common.C.TRACK_TYPE_VIDEO ->
                        listOfNotNull(
                            fmt.height.takeIf { it > 0 }?.let { "${it}p" },
                            fmt.sampleMimeType?.substringAfter('/')?.takeIf { it.isNotEmpty() },
                        ).joinToString(" / ").ifBlank { "Video" }
                    androidx.media3.common.C.TRACK_TYPE_AUDIO ->
                        listOfNotNull(
                            readableLanguage(fmt.language ?: fmt.label ?: "Audio"),
                            fmt.channelCount.takeIf { (it ?: 0) > 0 }?.let { "$it ch" },
                        ).joinToString(" · ")
                    androidx.media3.common.C.TRACK_TYPE_TEXT ->
                        readableLanguage(fmt.language ?: fmt.label ?: "Subtitles")
                    else -> continue
                }
                val trackUid = "$type|${group.mediaTrackGroup.id}|$i"
                out.add(
                    TrackOption(
                        type = when (type) {
                            androidx.media3.common.C.TRACK_TYPE_VIDEO -> "video"
                            androidx.media3.common.C.TRACK_TYPE_AUDIO -> "audio"
                            else -> "text"
                        },
                        label = label,
                        trackUid = trackUid,
                        selected = group.isTrackSelected(i),
                        override = null,
                    ),
                )
            }
        }
        return out
    }

    /** Maps ISO-639-1 language codes to readable names; falls back to the code itself. */
    private fun readableLanguage(code: String): String = when (code.lowercase()) {
        "en" -> "English"
        "hi" -> "Hindi"
        "ta" -> "Tamil"
        "te" -> "Telugu"
        "bn" -> "Bengali"
        "mr" -> "Marathi"
        "gu" -> "Gujarati"
        "kn" -> "Kannada"
        "ml" -> "Malayalam"
        "pa" -> "Punjabi"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "ru" -> "Russian"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "zh" -> "Chinese"
        "ar" -> "Arabic"
        else -> code.replaceFirstChar { it.uppercase() }
    }

    fun selectTrack(option: TrackOption) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        when (option.type) {
            "text" -> {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                overrideForOption(builder, option)
            }
            else -> overrideForOption(builder, option)
        }
        p.trackSelectionParameters = builder.build()
        publishPlayerSnapshot()
    }

    private fun overrideForOption(builder: TrackSelectionParameters.Builder, option: TrackOption) {
        val override = overrideForUid(option.trackUid) ?: return
        builder.setOverrideForType(override)
    }

    private fun overrideForUid(trackUid: String): TrackSelectionOverride? {
        val playerRef = player ?: return null
        val parts = trackUid.split("|")
        if (parts.size != 3) return null
        val typeFlag = parts[0].toInt()
        val groupUid = parts[1]
        val index = parts[2].toInt()
        val group = playerRef.currentTracks.groups.firstOrNull {
            it.type == typeFlag && it.mediaTrackGroup.id == groupUid
        } ?: return null
        return TrackSelectionOverride(group.mediaTrackGroup, listOf(index))
    }

    fun toggleSubtitles() {
        val p = player ?: return
        val disabled = p.trackSelectionParameters.disabledTrackTypes
            .contains(androidx.media3.common.C.TRACK_TYPE_TEXT)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !disabled)
            .build()
    }

    fun subtitlesAvailable(): Boolean {
        val p = player ?: return false
        return p.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.length > 0 }
    }

    fun subtitlesEnabled(): Boolean = player
        ?.trackSelectionParameters
        ?.disabledTrackTypes
        ?.contains(androidx.media3.common.C.TRACK_TYPE_TEXT) == false

    // --------------------------------------------------------- episode navigation

    fun primeEpisodes(seriesId: String, season: Int, currentEpisode: Int) {
        viewModelScope.launch {
            val seasons = repo.episodes(seriesId).getOrDefault(emptyList())
            updateNeighbors(seasons, season, currentEpisode)
        }
    }

    private fun updateNeighbors(seasons: List<Season>, season: Int, currentEpisode: Int) {
        val seasonData = seasons.firstOrNull { it.seasonNumber == season }
        val eps = seasonData?.episodes
            ?.mapNotNull { ep -> ep.episodeNumber.toIntOrNull()?.let { ep to it } }
            ?.sortedBy { it.second }
            ?: return
        val idx = eps.indexOfFirst { it.second == currentEpisode }
        if (idx >= 0) {
            _state.value = _state.value.copy(
                prevEpisode = eps.getOrNull(idx - 1)?.second,
                nextEpisode = eps.getOrNull(idx + 1)?.second,
            )
        }
    }

    fun loadEpisode(season: Int, episode: Int) {
        val s = _state.value
        cancelAutoNext()
        viewModelScope.launch {
            if (player != null) {
                player?.stop()
                player?.clearMediaItems()
            }
            _state.value = _state.value.copy(season = season, episode = episode, episodeEnded = false)
            resolve(s.subjectId, season, episode, s.title, resourceId = s.resourceId)
            val seasons = repo.episodes(s.subjectId).getOrDefault(emptyList())
            updateNeighbors(seasons, season, episode)
        }
    }

    // ---------------------------------------------------------- auto next episode

    private fun scheduleAutoNext() {
        cancelAutoNext()
        _state.value = _state.value.copy(episodeEnded = true)
    }

    fun cancelAutoNext() {
        autoNextJob?.cancel()
        autoNextJob = null
        _state.value = _state.value.copy(episodeEnded = false)
    }

    /** Autoplay the next episode after a short countdown (disabled when no next episode). */
    fun startAutoNext(countdownSeconds: Int = 10, onNext: (Int, Int) -> Unit) {
        cancelAutoNext()
        if (_state.value.nextEpisode == null || !_state.value.isSeries) return
        autoNextJob = viewModelScope.launch {
            var remaining = countdownSeconds.toLong()
            while (remaining > 0 && isActive) {
                delay(1000)
                remaining--
            }
            if (!isActive) return@launch
            val next = _state.value.nextEpisode ?: return@launch
            val season = _state.value.season
            cancelAutoNext()
            onNext(season, next)
        }
    }

    // ------------------------------------------------------------------- release

    override fun onCleared() {
        cancelAutoNext()
        resolveJob?.cancel()
        resolveJob = null
        tickJob?.cancel()
        tickJob = null
        persistProgress()
        player?.removeListener(listener)
        player?.release()
        player = null
        super.onCleared()
    }

    /**
     * ExoPlayer's raw messages are developer-facing. Map the actionable cases
     * (rate limits, expired sources, network, decode) to clear retry guidance
     * while the full exception stays in logcat.
     */
    private fun playbackErrorMessage(error: PlaybackException): String {
        val httpCode = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode
        return when {
            httpCode == 429 ->
                "The stream is temporarily rate-limited. Wait a moment, then retry."
            httpCode != null ->
                "The stream returned HTTP $httpCode. The source may have expired; retry in a moment."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Could not reach the stream. Check your connection and retry."
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "This device could not decode the stream."
            else -> "Playback failed (code ${error.errorCode}). Please retry."
        }
    }

    companion object {
        const val EXO_UA = "ExoPlayerLib/2.18.7"
        private const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
    }
}
