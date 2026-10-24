package com.streambox.mobile.data.remote

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses JSON fixtures shaped exactly like the envelopes documented (and live-verified)
 * in `docs/api-integration.md` during Phase 1.
 *
 * Fixture hygiene: URLs, cookies, real titles and provider hostnames are replaced with
 * clearly synthetic placeholders (`cdn.example.test`, `<signing-cookie-stub>`). Only the
 * JSON *structure* (field names, nesting, int-vs-string variants) follows the backend.
 */
class DtoParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `trending parses flexible-int subjectType in both int and string forms`() {
        val payload = """
            {"code":0,"data":[
              {"subjectId":"111111111111111111","id":"111111111111111111","title":"Fixture Movie A",
               "cover":"https://img.example.test/a.jpg","poster":"https://img.example.test/a2.jpg",
               "score":"8.4","releaseTime":"2024-01-05","subjectType":1,"runtime":"99 min",
               "description":"Fixture description","actionType":"detail","categoryId":5,
               "isPost":false,"isCollection":false,"likeStatus":0},
              {"subjectId":"222222222222222222","id":"222222222222222222","title":"Fixture Series B",
               "cover":"https://img.example.test/b.jpg","score":"7.1","releaseTime":"2023-06-12",
               "subjectType":"2","season":"1","episode":"3","actionType":"detail",
               "isPost":false,"isCollection":true,"likeStatus":"1"}
            ]}
        """.trimIndent()

        val res = json.decodeFromString(TrendingResponse.serializer(), payload)
        assertEquals(0, res.code)
        assertEquals(2, res.data.size)

        val movie = res.data[0]
        assertEquals("111111111111111111", movie.subjectId)
        assertEquals("Fixture Movie A", movie.title)
        assertEquals(1, movie.subjectType)
        assertEquals(0, movie.likeStatus)
        assertEquals("https://img.example.test/a.jpg", movie.imageUrl)

        val series = res.data[1]
        assertEquals(2, series.subjectType) // parsed from the "2" string form
        assertEquals(1, series.season)
        assertEquals(3, series.episode)
        assertEquals(1, series.likeStatus)
    }

    @Test
    fun `home sections parse with nested items`() {
        val payload = """
            {"code":0,"data":{"list":[
              {"title":"Featured","type":"banner","items":[
                {"subjectId":"333333333333333333","id":"333333333333333333","title":"Fixture C",
                 "cover":"https://img.example.test/c.jpg","subjectType":"6"}]},
              {"title":"New Releases","type":"rail","items":[
                {"subjectId":"444444444444444444","id":"444444444444444444","title":"Fixture D",
                 "cover":"https://img.example.test/d.jpg","subjectType":2,"season":1}]}
            ]}}
        """.trimIndent()

        val res = json.decodeFromString(HomeResponse.serializer(), payload)
        assertEquals(0, res.code)
        assertEquals(2, res.data?.list?.size)
        assertEquals("Featured", res.data?.list?.get(0)?.title)
        assertEquals(6, res.data?.list?.get(0)?.items?.single()?.subjectType)
        assertEquals(2, res.data?.list?.get(1)?.items?.single()?.subjectType)
    }

    @Test
    fun `search parses items and suggestions`() {
        val searchPayload = """
            {"code":0,"data":{"items":[
              {"subjectId":"555555555555555555","id":"555555555555555555","title":"Fixture E",
               "cover":"https://img.example.test/e.jpg","subjectType":"1","score":"6.9"}
            ]}}
        """.trimIndent()
        val res = json.decodeFromString(SearchResponse.serializer(), searchPayload)
        assertEquals(0, res.code)
        assertEquals("Fixture E", res.data?.items?.single()?.title)

        val suggestions = json.decodeFromString(
            SuggestionsResponse.serializer(),
            """{"code":0,"data":["fix","fixture two"]}""",
        )
        assertEquals(listOf("fix", "fixture two"), suggestions.data)
    }

    @Test
    fun `detail parses movie shape and the not-found envelope keeps data null`() {
        val ok = """
            {"code":0,"data":{
              "subjectId":"666666666666666666","id":"666666666666666666","title":"Fixture F",
              "cover":"https://img.example.test/f.jpg","poster":"https://img.example.test/f2.jpg",
              "score":"8.0","releaseTime":"2024-02-01","subjectType":1,"runtime":"Special | 108 min",
              "description":"Fixture description",
              "cast":[{"name":"Actor One","role":"Lead","avatar":"https://img.example.test/p1.jpg"},
                      {"name":"Actor Two","role":"Support","avatar":"https://img.example.test/p2.jpg"}],
              "languages":[{"subjectId":"7000000000000000001","id":"7000000000000000002","name":"English","type":"dub"},
                           {"subjectId":"8000000000000000003","id":"8000000000000000004","name":"Original","type":"original"}],
              "postCount":"12","isCollection":false,"isPost":false
            }}
        """.trimIndent()
        val res = json.decodeFromString(DetailResponse.serializer(), ok)
        assertEquals(0, res.code)
        val item = res.data
        assertTrue(item != null)
        assertEquals("Fixture F", item.title)
        assertEquals(1, item.subjectType)
        assertEquals(2, item.cast.size)
        assertEquals("Lead", item.cast[0].role)
        assertEquals("English", item.languages[0].name)
        // Regression: provider IDs are 19-digit strings and must not be truncated
        // to an Int (the old DTO silently lost the final digits).
        assertEquals("7000000000000000001", item.languages[0].subjectId)
        assertEquals("7000000000000000002", item.languages[0].id)
        assertEquals("8000000000000000003", item.languages[1].subjectId)

        val missing = json.decodeFromString(
            DetailResponse.serializer(),
            """{"code":1,"msg":"Not found"}""",
        )
        assertEquals(1, missing.code)
        assertEquals("Not found", missing.msg)
        assertNull(missing.data)
    }

    @Test
    fun `episodes parse seasons list with string episode numbers`() {
        val payload = """
            {"code":0,"data":{"seasons":[
              {"seasonNumber":1,"episodes":[
                {"episodeNumber":"1","title":"Pilot (fixture)","id":"901"},
                {"episodeNumber":"2","title":"Second (fixture)","id":"902"},
                {"episodeNumber":"10","title":"Tenth (fixture)","id":"910"}]},
              {"seasonNumber":2,"episodes":[{"episodeNumber":"1","title":"S2E1","id":"911"}]}
            ]}}
        """.trimIndent()

        val res = json.decodeFromString(EpisodesResponse.serializer(), payload)
        assertEquals(0, res.code)
        val seasons = res.data?.seasons.orEmpty()
        assertEquals(2, seasons.size)
        assertEquals(1, seasons[0].seasonNumber)
        assertEquals(3, seasons[0].episodes.size)
        assertEquals("10", seasons[0].episodes[2].episodeNumber)
    }

    @Test
    fun `stream response parses playback fields from the verified contract`() {
        // Live-verified in Phase 1: /stream returns the source dict FLAT (no {"code":0,"data":...}
        // envelope, unlike /trending, /detail, /episodes, /subtitles). Values here are sanitized
        // (no real cookie/URL/provider hostname).
        val payload = """
            {"url":"https://cdn.example.test/video/fixture.mp4?st=stub",
             "cookie":"<signing-cookie-stub>",
             "duration":13680,
             "subtitles":[{"id":"1","lan":"en","lanName":"English",
                           "url":"https://cdn.example.test/subs/fixture.en.srt"}],
             "subtitle_url":null,
             "isHls":false,
             "isDash":false,
             "mimeType":"video/mp4",
             "videoCodec":"hevc",
             "audioCodec":"aac",
             "videoWidth":850,
             "videoHeight":480,
             "isTranscoded":false,
             "compatUrl":"/play-compat/123456789012345678?season=1&episode=1&quality=480p",
             "selectedQuality":"480p",
             "streamId":"5f7d",
             "qualityList":[],
             "season":1,
             "episode":1}
        """.trimIndent()

        val stream = json.decodeFromString(StreamResponse.serializer(), payload)
        assertEquals("https://cdn.example.test/video/fixture.mp4?st=stub", stream.url)
        assertEquals("<signing-cookie-stub>", stream.cookie)
        assertEquals(13680L, stream.duration)
        assertEquals(false, stream.isHls)
        assertEquals(false, stream.isDash)
        assertEquals("video/mp4", stream.mimeType)
        assertEquals("hevc", stream.videoCodec)
        assertEquals("aac", stream.audioCodec)
        assertEquals(850, stream.videoWidth)
        assertEquals(480, stream.videoHeight)
        assertEquals(false, stream.isTranscoded)
        assertEquals(
            "/play-compat/123456789012345678?season=1&episode=1&quality=480p",
            stream.compatUrl,
        )
        assertEquals("480p", stream.selectedQuality)
        assertEquals("5f7d", stream.streamId)
        assertTrue(stream.qualityList.isEmpty())
        assertEquals(1, stream.subtitles.size)
        assertEquals("English", stream.subtitles[0].lanName)
        assertEquals("en", stream.subtitles[0].lan)
        assertEquals("https://cdn.example.test/subs/fixture.en.srt", stream.subtitles[0].url)
        assertEquals(1, stream.season)
        assertEquals(1, stream.episode)
    }

    @Test
    fun `hls stream variant parses with quality list`() {
        val payload = """
            {"url":"https://cdn.example.test/video/fixture.m3u8?st=stub",
             "cookie":null,
             "duration":0,
             "subtitles":[],
             "isHls":true,
             "streamId":"ab01",
             "qualityList":["720P","1080P"]}
        """.trimIndent()

        val stream = json.decodeFromString(StreamResponse.serializer(), payload)
        assertTrue(stream.isHls)
        assertEquals(listOf("720P", "1080P"), stream.qualityList)
        assertNull(stream.cookie)
        assertTrue(stream.subtitles.isEmpty())
    }

    @Test
    fun `stream response tolerates an older backend without metadata fields`() {
        val payload = """
            {"url":"https://cdn.example.test/video/legacy.mp4","duration":1200,
             "isHls":false,"subtitles":[],"streamId":"legacy"}
        """.trimIndent()

        val stream = json.decodeFromString(StreamResponse.serializer(), payload)
        assertEquals("https://cdn.example.test/video/legacy.mp4", stream.url)
        assertNull(stream.mimeType)
        assertNull(stream.videoCodec)
        assertNull(stream.audioCodec)
        assertNull(stream.compatUrl)
        assertNull(stream.selectedQuality)
        assertEquals(0, stream.videoWidth)
        assertEquals(0, stream.videoHeight)
        assertEquals(false, stream.isTranscoded)
    }

    @Test
    fun `subtitles endpoint envelope parses`() {
        val payload = """
            {"code":0,"data":{"list":[
              {"id":"1","lan":"en","lanName":"English","url":"https://cdn.example.test/subs/fixture.en.srt"}
            ]}}
        """.trimIndent()
        val res = json.decodeFromString(SubtitlesResponse.serializer(), payload)
        assertEquals(0, res.code)
        assertEquals(1, res.data?.list?.size)
    }
}
