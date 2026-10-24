package com.streambox.mobile.ui.player

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.UnstableApi
import com.streambox.mobile.data.ApiConfig
import com.streambox.mobile.data.local.PlaybackPosition
import com.streambox.mobile.data.local.ProgressStore
import com.streambox.mobile.data.local.ResumePolicy
import com.streambox.mobile.data.remote.Season
import com.streambox.mobile.data.repo.ContentError
import com.streambox.mobile.data.repo.ContentRepository
import com.streambox.mobile.data.remote.StreamResponse
import java.io.IOException
import java.util.Locale
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
    val automaticVideo: Boolean = true,
    val automaticAudio: Boolean = true,
    val subtitleTrackSelected: Boolean = false,
    val qualitySelectionAvailable: Boolean = false,
    val videoTrackPresent: Boolean = false,
    val supportedVideoTrackPresent: Boolean = false,
    val firstVideoFrameRendered: Boolean = false,
    val usingCompatStream: Boolean = false,
    val compatFallbackAvailable: Boolean = false,
    val videoCodec: String? = null,
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
    private var firstFrameTimeoutJob: Job? = null
    private var firstFrameTimeoutStartedAtMs: Long? = null
    private var firstFrameTimeoutRemainingMs = FIRST_VIDEO_FRAME_TIMEOUT_MS
    private var firstFrameTimeoutLimitMs = FIRST_VIDEO_FRAME_TIMEOUT_MS

    private var currentStream: StreamResponse? = null
    private var currentPosterUrl: String? = null
    private var usingCompatStream = false
    private var compatAttempted = false
    private var currentVideoDecoderName: String? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishPlayerSnapshot()
            if (playWhenReady) startFirstFrameTimeout() else pauseFirstFrameTimeout()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishPlayerSnapshot()
            if (playbackState == Player.STATE_READY) {
                startFirstFrameTimeout()
            } else {
                pauseFirstFrameTimeout()
            }
            if (playbackState == Player.STATE_ENDED && _state.value.isSeries) {
                scheduleAutoNext()
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val groups = tracks.groups
            Log.i(
                TAG,
                "Track groups: video=${groups.count { it.type == C.TRACK_TYPE_VIDEO }} " +
                    "audio=${groups.count { it.type == C.TRACK_TYPE_AUDIO }} " +
                    "text=${groups.count { it.type == C.TRACK_TYPE_TEXT }} " +
                    "usingCompat=$usingCompatStream",
            )
            // Record the actual container/track formats. A provider label such
            // as "480p" says nothing about the codec, and a decoder can be
            // advertised as supported yet never emit a frame.
            groups.forEach { group ->
                val typeName = when (group.type) {
                    C.TRACK_TYPE_VIDEO -> "video"
                    C.TRACK_TYPE_AUDIO -> "audio"
                    C.TRACK_TYPE_TEXT -> "text"
                    else -> "other"
                }
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    Log.i(
                        TAG,
                        "Track type=$typeName idx=$i supported=${group.isTrackSupported(i)} " +
                            "selected=${group.isTrackSelected(i)} mime=${format.sampleMimeType} " +
                            "codecs=${format.codecs} ${format.width}x${format.height} " +
                            "fps=${format.frameRate} ch=${format.channelCount} bitrate=${format.bitrate}",
                    )
                }
            }
            publishPlayerSnapshot()
        }

        override fun onRenderedFirstFrame() {
            Log.i(
                TAG,
                "First video frame rendered (usingCompat=$usingCompatStream " +
                    "decoder=${currentVideoDecoderName ?: "unknown"})",
            )
            resetFirstFrameTimeout()
            _state.value = _state.value.copy(firstVideoFrameRendered = true)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height} usingCompat=$usingCompatStream")
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _state.value = _state.value.copy(playbackSpeed = playbackParameters.speed)
        }

        override fun onPlayerError(error: PlaybackException) {
            resetFirstFrameTimeout()
            // Never log error.message here: Media3 HTTP errors can embed the
            // signed CDN URL. The code, exception class chain and HTTP status
            // identify the failure layer without leaking the source.
            Log.e(
                TAG,
                "Playback error: code=${error.errorCode} name=${error.errorCodeName} " +
                    "causes=${throwableChain(error)} usingCompat=$usingCompatStream " +
                    "decoder=${currentVideoDecoderName ?: "unknown"}",
            )
            _state.value = _state.value.copy(
                status = PlayerStatus.ERROR,
                showError = true,
                errorRetryable = true,
                errorDetail = playbackErrorMessage(error),
            )
        }
    }

    /**
     * Diagnostics only. In particular, this distinguishes "decoder was
     * initialized" from "decoder actually rendered a frame" — the exact gap
     * that produced the HEVC black-screen report.
     */
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            currentVideoDecoderName = decoderName
            Log.i(
                TAG,
                "Video decoder initialized: $decoderName initMs=$initializationDurationMs " +
                    "usingCompat=$usingCompatStream",
            )
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                TAG,
                "Video input format: mime=${format.sampleMimeType} codecs=${format.codecs} " +
                    "${format.width}x${format.height} fps=${format.frameRate} " +
                    "bitrate=${format.bitrate} rotation=${format.rotationDegrees}",
            )
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                TAG,
                "Audio input format: mime=${format.sampleMimeType} codecs=${format.codecs} " +
                    "ch=${format.channelCount} sampleRate=${format.sampleRate}",
            )
        }

        override fun onVideoSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            videoSize: androidx.media3.common.VideoSize,
        ) {
            Log.i(
                TAG,
                "Analytics video size: ${videoSize.width}x${videoSize.height} " +
                    "pixelRatio=${videoSize.pixelWidthHeightRatio}",
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            surface: Any,
            renderTimeMs: Long,
        ) {
            Log.i(
                TAG,
                "Analytics first frame: surface=${surface.javaClass.simpleName} " +
                    "usingCompat=$usingCompatStream",
            )
        }

        override fun onVideoCodecError(
            eventTime: AnalyticsListener.EventTime,
            error: Exception,
        ) {
            Log.e(TAG, "Video codec error: ${error.javaClass.simpleName}")
        }

        override fun onAudioCodecError(
            eventTime: AnalyticsListener.EventTime,
            error: Exception,
        ) {
            Log.e(TAG, "Audio codec error: ${error.javaClass.simpleName}")
        }

        override fun onVideoDecoderReleased(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
        ) {
            Log.i(TAG, "Video decoder released: $decoderName")
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val httpCode = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            Log.w(
                TAG,
                "Media load error: ${error.javaClass.simpleName} http=${httpCode ?: "n/a"} " +
                    "canceled=$wasCanceled usingCompat=$usingCompatStream",
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
        resetFirstFrameTimeout()
        compatAttempted = false
        usingCompatStream = false
        currentStream = null
        currentVideoDecoderName = null
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
            posterUrl = currentPosterUrl,
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
                val preferCompat = shouldPreferCompat(stream)
                preparePlayer(
                    subjectId = subjectId,
                    season = season,
                    episode = episode,
                    stream = stream,
                    posterUrl = posterUrl,
                    useCompat = preferCompat,
                )
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
        useCompat: Boolean,
        resumePositionOverrideMs: Long? = null,
        reusePlayer: Boolean = false,
    ) {
        currentStream = stream
        currentPosterUrl = posterUrl
        currentVideoDecoderName = null

        // For a fallback, the caller supplies the position that was already
        // playing. Using progress storage here would jump backwards.
        val resumePositionMs = resumePositionOverrideMs
            ?: decideResumeOffset(subjectId, season, episode)
        val compatBaseUrl = compatUrlFor(stream)
        val compatUrl = if (useCompat) compatUrlFor(stream, resumePositionMs) else null
        val actuallyCompat = useCompat && compatUrl != null
        if (useCompat && compatUrl == null) {
            Log.w(TAG, "Compatibility playback requested but the backend supplied no compat URL")
        }
        usingCompatStream = actuallyCompat
        if (actuallyCompat) compatAttempted = true

        // Trust backend metadata for the container: a signed CDN URL often has
        // no .m3u8/.mpd/.mp4 suffix. The compatibility endpoint is always MP4.
        val mime = if (actuallyCompat) MimeTypes.VIDEO_MP4 else mimeTypeForStream(stream)
        val playbackUrl = if (actuallyCompat) compatUrl!! else stream.url

        _state.value = _state.value.copy(
            status = PlayerStatus.PREPARING,
            showError = false,
            errorDetail = "",
            usingCompatStream = actuallyCompat,
            compatFallbackAvailable = compatBaseUrl != null,
            videoCodec = stream.videoCodec,
            firstVideoFrameRendered = false,
        )
        resetFirstFrameTimeout(
            if (actuallyCompat) COMPAT_FIRST_VIDEO_FRAME_TIMEOUT_MS else FIRST_VIDEO_FRAME_TIMEOUT_MS,
        )
        Log.i(
            TAG,
            "Preparing player: compat=$actuallyCompat mime=$mime codec=${stream.videoCodec} " +
                "quality=${stream.selectedQuality} durationMs=${stream.duration * 1000L}",
        )

        val headers = buildMap<String, String> {
            // Media CDNs typically require Referer + Origin in addition to Cookie + User-Agent.
            put("Referer", MEDIA_REFERER)
            put("Origin", "https://apig.inmoviebox.com")
            // Never forward the provider signing cookie to our own backend.
            // Subtitles still get Referer/Origin; media CDNs get the cookie.
            if (!actuallyCompat) {
                stream.cookie?.takeIf { it.isNotBlank() }?.let { raw ->
                    val value = if (raw.contains("=")) raw else "signCookie=$raw"
                    put("Cookie", value)
                }
            }
        }

        val subtitleUrls = stream.subtitles.mapNotNull { it.url }.toSet()
        val configs = buildList {
            stream.subtitles.forEach { st ->
                val url = st.url ?: return@forEach
                add(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                        .setMimeType(mimeForSubtitle(url))
                        .setLanguage(st.lan ?: st.lanName ?: "und")
                        .setLabel(st.lanName ?: st.lan ?: "Subtitles")
                        .build(),
                )
            }
            stream.subtitleUrl?.takeIf { it !in subtitleUrls }?.let { url ->
                add(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                        .setMimeType(mimeForSubtitle(url))
                        .setLanguage("en")
                        .setLabel("English")
                        .build(),
                )
            }
        }

        val metaBuilder = MediaMetadata.Builder().setTitle(_state.value.title)
        posterUrl?.let { metaBuilder.setArtworkUri(android.net.Uri.parse(it)) }

        val mediaItem = MediaItem.Builder()
            .setUri(playbackUrl)
            .setMimeType(mime)
            .setMediaMetadata(metaBuilder.build())
            .setSubtitleConfigurations(configs)
            .build()

        // Fallback should swap only the media source, not the ExoPlayer instance.
        // The existing PlayerView binding and video surface stay valid, avoiding
        // decoder/surface churn and a visible reattach during recovery.
        if (reusePlayer && player != null) {
            val existing = player!!
            try {
                val compatDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(EXO_UA)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(45_000)
                    .setDefaultRequestProperties(headers)
                val mediaSource = DefaultMediaSourceFactory(compatDataSourceFactory)
                    .createMediaSource(mediaItem)
                existing.setMediaSource(mediaSource, 0L)
                existing.playWhenReady = true
                existing.prepare()
                startTicking()
                _state.value = _state.value.copy(
                    status = PlayerStatus.BUFFERING,
                    durationMs = max(_state.value.durationMs, stream.duration * 1000L),
                    resumeAppliedMs = 0L,
                    usingCompatStream = actuallyCompat,
                    compatFallbackAvailable = compatBaseUrl != null,
                    videoCodec = stream.videoCodec,
                    firstVideoFrameRendered = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not switch ExoPlayer to the compatible source: ${e.javaClass.simpleName}")
                _state.value = _state.value.copy(
                    status = PlayerStatus.ERROR,
                    showError = true,
                    errorRetryable = true,
                    errorDetail = "Could not start the compatible video (${e.javaClass.simpleName}).",
                )
            }
            return
        }

        // Release any previous player before creating a new one (cookie differs per resolve).
        player?.let { old ->
            old.removeListener(listener)
            old.removeAnalyticsListener(analyticsListener)
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
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(45_000)
                .setDefaultRequestProperties(headers)

            // Some phones expose more than one decoder for the same codec. The
            // provider stream may be accepted by the first decoder (audio starts
            // and the player reaches READY) while that decoder never produces a
            // video frame. Let Media3 fall back to another available decoder
            // before the first-frame watchdog reports a false black-screen
            // failure. This does not add a software codec; it only makes the
            // hardware-decoder selection more tolerant.
            val renderersFactory = DefaultRenderersFactory(appContext)
                .setEnableDecoderFallback(true)

            newPlayer = ExoPlayer.Builder(appContext, renderersFactory)
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
            newPlayer.addAnalyticsListener(analyticsListener)
            newPlayer.playWhenReady = true
            player = newPlayer
            startTicking()
            // The compat endpoint starts the transcode at start_time, so its
            // output timeline begins at zero; direct playback seeks locally.
            val startPositionMs = if (actuallyCompat) 0L else max(0L, resumePositionMs)
            newPlayer.setMediaItem(mediaItem, startPositionMs)
            newPlayer.prepare()

            _state.value = _state.value.copy(
                status = PlayerStatus.BUFFERING,
                durationMs = max(_state.value.durationMs, stream.duration * 1000L),
                resumeAppliedMs = if (actuallyCompat) 0L else resumePositionMs,
                usingCompatStream = actuallyCompat,
                compatFallbackAvailable = compatBaseUrl != null,
                videoCodec = stream.videoCodec,
                firstVideoFrameRendered = false,
            )
        } catch (e: CancellationException) {
            runCatching { newPlayer?.removeListener(listener) }
            runCatching { newPlayer?.removeAnalyticsListener(analyticsListener) }
            runCatching { newPlayer?.release() }
            if (player === newPlayer) player = null
            throw e
        } catch (e: Exception) {
            // Surface player init failures as a retryable player error instead of
            // letting an uncaught exception close the app. Do not include the
            // exception message: it can contain the signed media URL.
            Log.e(TAG, "Could not initialise ExoPlayer: ${e.javaClass.simpleName}")
            runCatching { newPlayer?.removeListener(listener) }
            runCatching { newPlayer?.removeAnalyticsListener(analyticsListener) }
            runCatching { newPlayer?.release() }
            player = null
            tickJob?.cancel()
            _state.value = _state.value.copy(
                status = PlayerStatus.ERROR,
                showError = true,
                errorRetryable = true,
                errorDetail = "Could not start the player (${e.javaClass.simpleName}).",
            )
        }
    }

    /**
     * A provider can advertise an HEVC resource while Android has no HEVC
     * decoder at all. In that case there is nothing to wait for: use the
     * compatibility stream immediately. A decoder that exists but is broken is
     * still caught by the first-frame watchdog below.
     */
    private fun shouldPreferCompat(stream: StreamResponse): Boolean {
        if (stream.compatUrl.isNullOrBlank()) return false
        if (!stream.videoCodec.equals("hevc", ignoreCase = true)) return false
        val hasDecoder = runCatching {
            MediaCodecUtil.getDecoderInfos("video/hevc", false, false).isNotEmpty()
        }.getOrElse { e ->
            Log.w(TAG, "Could not query HEVC decoders: ${e.javaClass.simpleName}")
            true // Do not preempt on a diagnostic-query failure.
        }
        if (!hasDecoder) {
            Log.i(TAG, "No HEVC decoder advertised; using compatibility stream")
        }
        return !hasDecoder
    }

    private fun mimeTypeForStream(stream: StreamResponse): String? {
        val backendMime = stream.mimeType?.trim()?.lowercase(Locale.US)
        val fromBackend = when {
            backendMime.isNullOrEmpty() -> null
            "mpegurl" in backendMime -> MimeTypes.APPLICATION_M3U8
            "dash" in backendMime -> MimeTypes.APPLICATION_MPD
            "video/mp4" in backendMime -> MimeTypes.VIDEO_MP4
            backendMime == "application/octet-stream" -> null
            else -> backendMime
        }
        if (fromBackend != null) return fromBackend
        val path = stream.url.substringBefore('?').lowercase(Locale.US)
        return when {
            stream.isHls || path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            stream.isDash || path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

    private fun compatUrlFor(stream: StreamResponse, startTimeMs: Long = 0L): String? {
        val raw = stream.compatUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = when {
            raw.startsWith("https://") -> raw
            raw.startsWith("http://") -> {
                // Cleartext is acceptable only for a debug/local backend.
                if (ApiConfig.BASE_URL.startsWith("http://")) raw else return null
            }
            else -> ApiConfig.BASE_URL.trimEnd('/') + "/" + raw.trimStart('/')
        }
        if (startTimeMs <= 0L) return base
        val separator = if ('?' in base) '&' else '?'
        val seconds = String.format(Locale.US, "%.3f", startTimeMs / 1000.0)
        return "$base$separator" + "start_time=" + seconds
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
        val tracks = snapshotTracks()
        val currentStatus = _state.value.status
        val status = when {
            currentStatus == PlayerStatus.ERROR || currentStatus == PlayerStatus.UNAVAILABLE -> currentStatus
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
            tracks = tracks,
            automaticVideo = isTrackTypeAutomatic(p, androidx.media3.common.C.TRACK_TYPE_VIDEO),
            automaticAudio = isTrackTypeAutomatic(p, androidx.media3.common.C.TRACK_TYPE_AUDIO),
            subtitleTrackSelected = p.currentTracks.groups.any { group ->
                group.type == androidx.media3.common.C.TRACK_TYPE_TEXT &&
                    (0 until group.length).any(group::isTrackSelected)
            },
            qualitySelectionAvailable = tracks.count { it.type == "video" } > 1,
            videoTrackPresent = p.currentTracks.groups.any {
                it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
            },
            supportedVideoTrackPresent = tracks.any { it.type == "video" },
        )
    }

    private fun startFirstFrameTimeout() {
        val currentPlayer = player ?: return
        if (
            _state.value.firstVideoFrameRendered ||
            !currentPlayer.playWhenReady ||
            currentPlayer.playbackState != Player.STATE_READY ||
            firstFrameTimeoutJob?.isActive == true
        ) {
            return
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        firstFrameTimeoutStartedAtMs = startedAtMs
        firstFrameTimeoutJob = viewModelScope.launch {
            delay(firstFrameTimeoutRemainingMs)
            firstFrameTimeoutRemainingMs = 0L
            firstFrameTimeoutStartedAtMs = null
            firstFrameTimeoutJob = null
            if (
                player !== currentPlayer ||
                _state.value.firstVideoFrameRendered ||
                !currentPlayer.playWhenReady ||
                currentPlayer.playbackState != Player.STATE_READY
            ) {
                return@launch
            }

            val videoGroups = currentPlayer.currentTracks.groups.filter {
                it.type == C.TRACK_TYPE_VIDEO
            }
            val hasSupportedVideo = videoGroups.any { group ->
                (0 until group.length).any(group::isTrackSupported)
            }
            val firstFormat = videoGroups.firstOrNull()?.getTrackFormat(0)
            val codec = firstFormat?.sampleMimeType ?: firstFormat?.codecs ?: "unknown codec"
            val dimensions = firstFormat?.height?.takeIf { it > 0 }?.let { " ${it}p" }.orEmpty()
            val timeoutSeconds = firstFrameTimeoutLimitMs / 1_000
            val capturedStream = currentStream

            // This is the key fallback: the HEVC decoder can initialize and
            // report support while never emitting a frame. Do not stop at a
            // dead-end error when the backend can provide an H.264 rendition.
            if (
                !usingCompatStream &&
                !compatAttempted &&
                capturedStream != null &&
                compatUrlFor(capturedStream) != null
            ) {
                val resumeAtMs = currentPlayer.currentPosition.coerceAtLeast(0L)
                currentPlayer.pause()
                compatAttempted = true
                Log.w(
                    TAG,
                    "First-frame timeout: codec=$codec height=${firstFormat?.height} " +
                        "videoGroups=${videoGroups.size} supported=$hasSupportedVideo " +
                        "decoder=${currentVideoDecoderName ?: "unknown"}; switching to compat at ${resumeAtMs}ms",
                )
                _state.value = _state.value.copy(
                    status = PlayerStatus.PREPARING,
                    showError = false,
                    errorDetail = "",
                    usingCompatStream = true,
                    compatFallbackAvailable = true,
                    videoCodec = capturedStream.videoCodec,
                    firstVideoFrameRendered = false,
                )
                viewModelScope.launch {
                    preparePlayer(
                        subjectId = _state.value.subjectId,
                        season = _state.value.season,
                        episode = _state.value.episode,
                        stream = capturedStream,
                        posterUrl = currentPosterUrl,
                        useCompat = true,
                        resumePositionOverrideMs = resumeAtMs,
                        reusePlayer = true,
                    )
                }
                return@launch
            }

            val detail = when {
                videoGroups.isEmpty() ->
                    "The selected source has no video track. Choose another source or title."
                usingCompatStream ->
                    "The compatibility stream did not produce a video frame after " +
                        "$timeoutSeconds seconds. The server transcode may be unavailable or " +
                        "the source may have ended. Retry or choose another title."
                !hasSupportedVideo -> {
                    "This device cannot decode the source video format ($codec$dimensions), " +
                        "and no compatibility stream is available. Choose another source or device."
                }
                else ->
                    "No video frame arrived for $codec$dimensions after " +
                        "$timeoutSeconds seconds of playback. Try another source; this stream " +
                        "may be damaged or incompatible with this device."
            }
            Log.w(
                TAG,
                "First-frame timeout: codec=$codec height=${firstFormat?.height} " +
                    "videoGroups=${videoGroups.size} supported=$hasSupportedVideo " +
                    "usingCompat=$usingCompatStream decoder=${currentVideoDecoderName ?: "unknown"}",
            )
            currentPlayer.pause()
            _state.value = _state.value.copy(
                status = PlayerStatus.ERROR,
                showError = true,
                errorRetryable = true,
                errorDetail = detail,
            )
        }
    }

    private fun pauseFirstFrameTimeout() {
        val startedAtMs = firstFrameTimeoutStartedAtMs ?: return
        firstFrameTimeoutRemainingMs = (
            firstFrameTimeoutRemainingMs - (SystemClock.elapsedRealtime() - startedAtMs)
        ).coerceAtLeast(0L)
        firstFrameTimeoutStartedAtMs = null
        firstFrameTimeoutJob?.cancel()
        firstFrameTimeoutJob = null
    }

    private fun resetFirstFrameTimeout(limitMs: Long = FIRST_VIDEO_FRAME_TIMEOUT_MS) {
        firstFrameTimeoutJob?.cancel()
        firstFrameTimeoutJob = null
        firstFrameTimeoutStartedAtMs = null
        firstFrameTimeoutLimitMs = limitMs
        firstFrameTimeoutRemainingMs = limitMs
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
            val manualOverride = p.trackSelectionParameters.overrides[group.mediaTrackGroup]
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val fmt = group.getTrackFormat(i)
                val label = when (type) {
                    androidx.media3.common.C.TRACK_TYPE_VIDEO ->
                        listOfNotNull(
                            fmt.height.takeIf { it > 0 }?.let { "${it}p" },
                            fmt.bitrate.takeIf { it > 0 }?.let { bitrate ->
                                "${(bitrate / 1_000_000f).roundToLong()} Mbps"
                            },
                        ).joinToString(" / ").ifBlank { "Video" }
                    androidx.media3.common.C.TRACK_TYPE_AUDIO ->
                        listOfNotNull(
                            readableLanguage(fmt.language, fmt.label, "Audio ${i + 1}"),
                            fmt.channelCount.takeIf { (it ?: 0) > 0 }?.let { "$it ch" },
                        ).joinToString(" · ")
                    androidx.media3.common.C.TRACK_TYPE_TEXT ->
                        readableLanguage(fmt.language, fmt.label, "Subtitle ${i + 1}")
                    else -> continue
                }
                out.add(
                    TrackOption(
                        type = when (type) {
                            androidx.media3.common.C.TRACK_TYPE_VIDEO -> "video"
                            androidx.media3.common.C.TRACK_TYPE_AUDIO -> "audio"
                            else -> "text"
                        },
                        label = label,
                        selected = if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                            group.isTrackSelected(i)
                        } else {
                            manualOverride?.trackIndices?.contains(i) == true
                        },
                        override = TrackSelectionOverride(group.mediaTrackGroup, listOf(i)),
                    ),
                )
            }
        }
        return out
    }

    private fun readableLanguage(language: String?, label: String?, fallback: String): String {
        val normalizedLanguage = language?.takeUnless { it.isBlank() || it == "und" }
        val displayName = normalizedLanguage?.let {
            java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault())
        }?.takeUnless { it.isBlank() || it == normalizedLanguage }
        return displayName ?: label?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun selectTrack(option: TrackOption) {
        val p = player ?: return
        val trackType = trackTypeFor(option.type) ?: return
        val override = option.override ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(trackType)
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(override)
            .build()
        publishPlayerSnapshot()
    }

    fun selectAutomaticTrack(type: String) {
        val p = player ?: return
        val trackType = trackTypeFor(type) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(trackType)
            .setTrackTypeDisabled(trackType, false)
            .build()
        publishPlayerSnapshot()
    }

    fun disableSubtitles() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            .build()
        publishPlayerSnapshot()
    }

    private fun trackTypeFor(type: String): Int? = when (type) {
        "video" -> androidx.media3.common.C.TRACK_TYPE_VIDEO
        "audio" -> androidx.media3.common.C.TRACK_TYPE_AUDIO
        "text" -> androidx.media3.common.C.TRACK_TYPE_TEXT
        else -> null
    }

    private fun isTrackTypeAutomatic(p: ExoPlayer, trackType: Int): Boolean {
        val overriddenGroups = p.trackSelectionParameters.overrides.keys
        return overriddenGroups.none { overrideGroup ->
            p.currentTracks.groups.any { group ->
                group.type == trackType && group.mediaTrackGroup == overrideGroup
            }
        }
    }

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
        resetFirstFrameTimeout()
        tickJob?.cancel()
        tickJob = null
        persistProgress()
        player?.removeListener(listener)
        player?.removeAnalyticsListener(analyticsListener)
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
            usingCompatStream && httpCode == 404 ->
                "The server does not expose the compatibility stream yet. " +
                    "Deploy the updated backend, then retry."
            usingCompatStream && httpCode == 429 ->
                "Compatibility mode is busy. Wait a moment, then retry."
            usingCompatStream && httpCode == 503 ->
                "Compatibility transcoding is unavailable on the server. Try again later."
            usingCompatStream && httpCode != null ->
                "The compatibility stream returned HTTP $httpCode. Retry in a moment."
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

    private fun throwableChain(error: Throwable?): String =
        generateSequence(error) { it.cause }
            .take(6)
            .joinToString("<-") { it.javaClass.simpleName }

    companion object {
        const val EXO_UA = "ExoPlayerLib/2.18.7"
        private const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        private const val FIRST_VIDEO_FRAME_TIMEOUT_MS = 15_000L
        // FFmpeg must resolve, connect and emit the first fragmented-MP4 chunk;
        // allow more time than a direct CDN request before declaring failure.
        private const val COMPAT_FIRST_VIDEO_FRAME_TIMEOUT_MS = 30_000L
    }
}
