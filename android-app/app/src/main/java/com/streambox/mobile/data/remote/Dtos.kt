/*
 * Typed models for the moviebox-internal-api backend (Phase 1 contract).
 *
 * Source of truth: `docs/api-integration.md` and the live endpoints verified in
 * Phase 1 (/trending, /home, /search, /detail, /episodes, /stream, /subtitles).
 * Field names are copied 1:1 from the actual JSON — nothing invented here.
 */
package com.streambox.mobile.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Item produced by the backend's map_item() — used by /trending, /search, /home
 * sections, /detail and /watchlist.
 */
@Serializable
data class FeedItem(
    // Provider IDs are large integers; backend serializes them as strings.
    @SerialName("subjectId") val subjectId: String = "",
    val id: String = "",
    val title: String = "Unknown",
    val cover: String? = null,
    val poster: String? = null,
    val score: String? = null,
    val releaseTime: String? = null,
    // 1 = movie, 2 = series, 6/8/9 = UGC/shorts/trailer mixes (Phase 1 notes).
    @Serializable(with = FlexibleIntSerializer::class) val subjectType: Int? = null,
    val runtime: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val season: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) val episode: Int? = null,
    val description: String? = null,
    val actionType: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val categoryId: Int? = null,
    val deepLink: String? = null,
    val isPost: Boolean = false,
    val isCollection: Boolean = false,
    @Serializable(with = FlexibleIntSerializer::class) val likeStatus: Int? = 0,
    val streamUrl: String? = null,
    val source: String? = null,
    val seeTime: Long? = null,
) {
    /** Poster image with sensible backend fallbacks (cover -> poster). */
    val imageUrl: String get() = cover ?: poster.orEmpty()
}

/** cast entry from /detail (map_actor output). */
@Serializable
data class CastMember(
    val name: String = "Unknown",
    val role: String = "Cast",
    val avatar: String = "",
)

/** Dub/language/resource entry from /detail `languages`. */
@Serializable
data class LanguageOption(
    // Provider IDs are 19-digit strings and do not fit in Int (live-verified).
    val subjectId: String? = null,
    val id: String? = null,
    val name: String = "",
    val type: String = "",
)

/** /detail response body (paid union of map_item + detail extras). */
@Serializable
data class DetailItem(
    @SerialName("subjectId") val subjectId: String = "",
    val id: String = "",
    val title: String = "Unknown",
    val cover: String? = null,
    val poster: String? = null,
    val score: String? = null,
    val releaseTime: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val subjectType: Int? = null,
    val runtime: String? = null,
    val description: String? = null,
    val cast: List<CastMember> = emptyList(),
    @SerialName("languages") val languages: List<LanguageOption> = emptyList(),
    val postCount: String? = null,
    val isCollection: Boolean = false,
    val isPost: Boolean = false,
    val collectionItems: List<FeedItem> = emptyList(),
    val streamUrl: String? = null,
    val source: String? = null,
) {
    val imageUrl: String get() = cover ?: poster.orEmpty()
}

/** Season struct from /episodes/{seriesId}. */
@Serializable
data class Episode(
    val episodeNumber: String = "",
    val title: String = "",
    val id: String = "",
)

@Serializable
data class Season(
    val seasonNumber: Int = 1,
    val episodes: List<Episode> = emptyList(),
)

/** /subtitles track shape (also embedded in /stream subtitles). */
@Serializable
data class SubtitleTrack(
    val id: String? = null,
    val lan: String? = null,
    val lanName: String? = null,
    val url: String? = null,
)

/*
 * Envelope models. The backend uses {"code": 0, "data": ...}; some failure envelopes
 * omit or replace `data`, so every `data` is nullable here.
 */

/** GET /trending -> {"code":0,"data":[ FeedItem ]} */
@Serializable
data class TrendingResponse(
    val code: Int = 1,
    val data: List<FeedItem> = emptyList(),
)

/** GET /home?page=1 -> {"code":0,"data":{"list":[ {"title","type","items":[FeedItem]} ]}} */
@Serializable
data class HomeSection(
    val title: String? = null,
    val type: String? = null,
    val items: List<FeedItem> = emptyList(),
)

@Serializable
data class HomeSections(val list: List<HomeSection> = emptyList())

@Serializable
data class HomeResponse(
    val code: Int = 1,
    val data: HomeSections? = null,
)

/** GET /search?q=..&page=1 -> {"code":0,"data":{"items":[FeedItem]}} */
@Serializable
data class SearchResults(val items: List<FeedItem> = emptyList())

@Serializable
data class SearchResponse(
    val code: Int = 1,
    val data: SearchResults? = null,
)

/** GET /search-suggestions?q=.. -> {"code":0,"data":["s1","s2",...]} */
@Serializable
data class SuggestionsResponse(
    val code: Int = 1,
    val data: List<String> = emptyList(),
)

/** GET /detail/{id} -> {"code":0,"data":{...}} (data absent on {"code":1,"msg":"Not found"}) */
@Serializable
data class DetailResponse(
    val code: Int = 1,
    val msg: String? = null,
    val data: DetailItem? = null,
)

/** GET /episodes/{seriesId} -> {"code":0,"data":{"seasons":[...]}} */
@Serializable
data class SeasonsPayload(val seasons: List<Season> = emptyList())

@Serializable
data class EpisodesResponse(
    val code: Int = 1,
    val data: SeasonsPayload? = null,
)

/**
 * GET /stream/{subjectId}?season&episode&quality
 * Phase 2 note: playback itself is NOT implemented; this model prepares the
 * Phase 3 repository layer only (see ContentRepository.resolveStream()).
 */
@Serializable
data class StreamResponse(
    val url: String = "",
    val cookie: String? = null,
    val duration: Long = 0,
    val subtitles: List<SubtitleTrack> = emptyList(),
    @SerialName("subtitle_url") val subtitleUrl: String? = null,
    val isHls: Boolean = false,
    val streamId: String? = null,
    val qualityList: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
)

/** GET /subtitles/{id} -> {"code":0,"data":{"list":[...]}} */
@Serializable
data class SubtitleListPayload(val list: List<SubtitleTrack> = emptyList())

@Serializable
data class SubtitlesResponse(
    val code: Int = 1,
    val data: SubtitleListPayload? = null,
)
