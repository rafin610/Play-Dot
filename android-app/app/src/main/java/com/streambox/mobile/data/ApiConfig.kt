package com.streambox.mobile.data

import com.streambox.mobile.BuildConfig

/**
 * Base URL of the backend (`moviebox-internal-api`).
 *
 * The value is injected at build time through the Gradle property
 * `streambox.baseUrl`; there is no production URL hardcoded in source.
 *
 *  - Production / Render: build with the deployed HTTPS origin, e.g.
 *      `./gradlew assembleRelease -Pstreambox.baseUrl=https://<service>.onrender.com/`
 *  - Emulator against a local backend: the emulator maps the host's loopback to
 *    `10.0.2.2`, so the default `http://10.0.2.2:8000/` is used when no property
 *    is supplied.
 *  - Physical device: use the backend machine's LAN address for local testing.
 *
 * Cleartext HTTP is allowlisted only for local development hosts; a Render HTTPS
 * URL needs no cleartext exception. No provider credentials live in the APK.
 */
object ApiConfig {
    /** Injectable per build via the Gradle property `streambox.baseUrl` (no code change needed). */
    val BASE_URL: String = BuildConfig.STREAM_BASE_URL
}
