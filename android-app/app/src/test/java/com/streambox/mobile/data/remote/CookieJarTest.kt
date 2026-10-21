package com.streambox.mobile.data.remote

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The backend expires idle sessions and Android must reuse the cookie it is
 * given. These tests are offline; they only exercise OkHttp's CookieJar hooks.
 */
class CookieJarTest {

    @Test
    fun `session cookie is returned for later backend requests`() {
        val jar = InMemoryCookieJar()
        val base = "http://10.0.2.2:8101/".toHttpUrl()
        val cookie = assertNotNull(
            Cookie.parse(base, "session_id=fixture-session; Path=/; HttpOnly")
        )

        jar.saveFromResponse(base, listOf(cookie))
        val loaded = jar.loadForRequest(base.resolve("detail/123")!!)

        assertEquals(1, loaded.size)
        assertEquals("session_id", loaded.single().name)
        assertEquals("fixture-session", loaded.single().value)
    }

    @Test
    fun `cookies are not sent to a different host`() {
        val jar = InMemoryCookieJar()
        val backend = "http://10.0.2.2:8101/".toHttpUrl()
        val cookie = assertNotNull(
            Cookie.parse(backend, "session_id=fixture-session; Path=/; HttpOnly")
        )
        jar.saveFromResponse(backend, listOf(cookie))

        assertTrue(jar.loadForRequest("https://cdn.example.test/video.mp4".toHttpUrl()).isEmpty())
    }
}
