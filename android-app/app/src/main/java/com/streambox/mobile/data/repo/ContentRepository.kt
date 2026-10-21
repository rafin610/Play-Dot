package com.streambox.mobile.data.repo

import com.streambox.mobile.data.remote.DetailItem
import com.streambox.mobile.data.remote.EpisodesResponse
import com.streambox.mobile.data.remote.FeedItem
import com.streambox.mobile.data.remote.HomeSection
import com.streambox.mobile.data.remote.MovieBoxApi
import com.streambox.mobile.data.remote.Season
import com.streambox.mobile.data.remote.StreamResponse
import com.streambox.mobile.data.remote.SubtitleTrack
import com.streambox.mobile.data.remote.TrendingResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Lightweight error taxonomy the UI can style against (retry text, messaging). */
sealed class ContentError : Exception() {
    /** No internet / host unreachable / connection reset. */
    object Network : ContentError() { private fun readResolve(): Any = Network }

    /** Backend reachable but returned a 4xx/5xx (e.g. 404 no streams, 502 upstream). */
    object Backend : ContentError() { private fun readResolve(): Any = Backend }

    /** Endpoint reachable but the payload was not the Phase 1 shape. */
    data class Malformed(override val message: String) : ContentError()
}

/**
 * Single source of truth for UI data. All calls go to the moviebox-internal-api
 * backend — never to the provider directly.
 */
class ContentRepository(private val api: MovieBoxApi) {

    private inline fun <T> wrap(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            // Changing episodes or leaving the player cancels the request. That is
            // not a playback failure, so it must not surface as one.
            throw e
        } catch (e: HttpException) {
            Result.failure(ContentError.Backend)
        } catch (e: IOException) {
            Result.failure(ContentError.Network)
        } catch (e: SerializationException) {
            Result.failure(ContentError.Malformed(e.message ?: "Unexpected backend payload"))
        } catch (e: IllegalArgumentException) {
            Result.failure(ContentError.Malformed(e.message ?: "Unexpected backend payload"))
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** GET /trending (verified in Phase 1). */
    suspend fun trending(): Result<TrendingResponse> = wrap { api.trending() }

    /** GET /home?page= (verified). Returns only sections that contain browsable items. */
    suspend fun homeSections(page: Int = 1): Result<List<HomeSection>> = wrap {
        api.home(page).data?.list
            ?.filter { it.items.isNotEmpty() }
            ?: emptyList()
    }

    /** GET /search?q= (verified). */
    suspend fun search(query: String, page: Int = 1): Result<List<FeedItem>> = wrap {
        if (query.isBlank()) emptyList() else api.search(query.trim(), page).data?.items ?: emptyList()
    }

    /**
     * GET /detail/{id} (verified). Fails with a 404-shaped envelope result when the
     * backend answers `{"code":1,"msg":"Not found"}` without data.
     */
    suspend fun detail(subjectId: String): Result<DetailItem> = wrap {
        val res = api.detail(subjectId)
        if (res.code != 0 || res.data == null) {
            if (res.code == 0 && res.data == null) throw ContentError.Malformed("Empty detail payload")
            throw ContentError.Backend
        }
        res.data
    }

    /** GET /episodes/{seriesId} (verified). */
    suspend fun episodes(seriesId: String): Result<List<Season>> = wrap {
        val res = api.episodes(seriesId)
        if (res.code != 0) throw ContentError.Backend
        res.data?.seasons ?: emptyList()
    }

    /** GET /subtitles/{id} (verified) — exposed for later phases. */
    suspend fun subtitles(subjectId: String, se: Int = 1, ep: Int = 1): Result<List<SubtitleTrack>> = wrap {
        api.subtitles(subjectId, se, ep).data?.list ?: emptyList()
    }

    /*
     * Phase 3 PREP — playback integration (NOT called by any Phase 2 screen).
     * The backend's GET /stream/{subjectId} was verified in Phase 1 and modeled in
     * StreamResponse. Phase 3 should call this from the player screen and hand
     * (url, cookie) to a Media3 DataSource with the cookie header.
     */
    suspend fun resolveStream(
        subjectId: String,
        season: Int = 1,
        episode: Int = 1,
        quality: String = "720p",
        resourceId: String? = null,
    ): Result<StreamResponse> = wrap {
        val res = api.stream(subjectId, season, episode, quality, resourceId)
        if (res.url.isBlank()) throw ContentError.Backend
        res
    }
}
