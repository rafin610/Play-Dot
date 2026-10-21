package com.streambox.mobile.data.remote

import com.streambox.mobile.data.ApiConfig
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Retrofit service mirroring the verified Phase 1 backend routes. */
interface MovieBoxApi {

    @retrofit2.http.GET("trending")
    suspend fun trending(): TrendingResponse

    @retrofit2.http.GET("home")
    suspend fun home(@retrofit2.http.Query("page") page: Int = 1): HomeResponse

    @retrofit2.http.GET("search")
    suspend fun search(
        @retrofit2.http.Query("q") q: String,
        @retrofit2.http.Query("page") page: Int = 1,
    ): SearchResponse

    @retrofit2.http.GET("search-suggestions")
    suspend fun suggestions(@retrofit2.http.Query("q") q: String): SuggestionsResponse

    @retrofit2.http.GET("detail/{subjectId}")
    suspend fun detail(@retrofit2.http.Path("subjectId") subjectId: String): DetailResponse

    @retrofit2.http.GET("episodes/{seriesId}")
    suspend fun episodes(@retrofit2.http.Path("seriesId") seriesId: String): EpisodesResponse

    /**
     * Phase 3 prep only: modeled from the Phase 1 verified response.
     * The app must NOT call this for playback until Phase 3 wires Media3.
     */
    @retrofit2.http.GET("stream/{subjectId}")
    suspend fun stream(
        @retrofit2.http.Path("subjectId") subjectId: String,
        @retrofit2.http.Query("season") season: Int = 1,
        @retrofit2.http.Query("episode") episode: Int = 1,
        @retrofit2.http.Query("quality") quality: String = "720p",
        @retrofit2.http.Query("resource_id") resourceId: String? = null,
    ): StreamResponse

    @retrofit2.http.GET("subtitles/{subjectId}")
    suspend fun subtitles(
        @retrofit2.http.Path("subjectId") subjectId: String,
        @retrofit2.http.Query("se") se: Int = 1,
        @retrofit2.http.Query("ep") ep: Int = 1,
    ): SubtitlesResponse
}

/** Lenient JSON config as observed from backend variance (project contains both int/string numerics). */
val backendJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = true
}

/**
 * Keeps the backend's `session_id` cookie for the lifetime of this app process.
 *
 * Without a CookieJar, OkHttp deliberately ignores Set-Cookie, so every screen
 * created a fresh server session and triggered another provider guest bootstrap.
 * This is memory-only: no cookie is written to DataStore, matching the previous
 * privacy behavior. The backend remains bounded by SESSION_TTL_SECONDS and
 * MAX_SESSIONS if the process restarts.
 */
internal class InMemoryCookieJar : CookieJar {
    private val stored = mutableMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this) {
            cookies.forEach { cookie ->
                val key = "${cookie.domain}|${cookie.path}|${cookie.name}"
                stored[key] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this) {
        val now = System.currentTimeMillis()
        stored.values.filter { cookie ->
            cookie.expiresAt > now && cookie.matches(url)
        }
    }
}

object ApiClient {
    val api: MovieBoxApi by lazy {
        val okHttp = OkHttpClient.Builder()
            // Signed content endpoints can be slow upstream (Phase 1: multi-second failover).
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .cookieJar(InMemoryCookieJar())
            .build()

        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(backendJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MovieBoxApi::class.java)
    }
}
