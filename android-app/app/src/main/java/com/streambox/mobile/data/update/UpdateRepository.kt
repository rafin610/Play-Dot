package com.streambox.mobile.data.update

import android.util.Log
import com.streambox.mobile.BuildConfig
import com.streambox.mobile.data.ApiConfig
import com.streambox.mobile.data.remote.backendJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateCheck"

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult
    data class UpdateAvailable(val prompt: UpdatePrompt) : UpdateCheckResult

    /** Network/HTTP/parse failure. Callers keep this non-intrusive: never a startup dialog. */
    data class Unavailable(val reason: String) : UpdateCheckResult
}

/**
 * Asynchronous update metadata client.
 *
 * Uses the same backend base URL as the rest of the app and validates the
 * payload before exposing it to the UI. A missing endpoint (old deployments)
 * or an unreachable server is reported as [UpdateCheckResult.NoUpdate] /
 * [UpdateCheckResult.Unavailable]; neither blocks app startup.
 */
class UpdateRepository(
    private val client: OkHttpClient = defaultUpdateClient(),
    private val metadataUrl: HttpUrl? = defaultUpdateMetadataUrl(),
) {

    suspend fun checkForUpdate(
        installedVersionCode: Long,
        expectedPackage: String,
        allowCleartext: Boolean,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val url = metadataUrl
            ?: return@withContext UpdateCheckResult.Unavailable("update endpoint URL is invalid")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                // An older backend has no update route (404) and a deployment
                // may intentionally disable updates (503). Both mean "nothing
                // to offer", not an error the user should see.
                if (response.code == 404 || response.code == 503) {
                    Log.d(TAG, "Update endpoint unavailable (HTTP ${response.code})")
                    return@use UpdateCheckResult.NoUpdate
                }
                if (!response.isSuccessful) {
                    return@use UpdateCheckResult.Unavailable("HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: return@use UpdateCheckResult.Unavailable("empty response")
                val metadata = backendJson.decodeFromString<UpdateMetadata>(body)
                val validated = UpdatePolicy.validate(
                    metadata = metadata,
                    allowCleartext = allowCleartext,
                    expectedPackage = expectedPackage,
                )
                when (val decision = UpdatePolicy.decide(installedVersionCode, validated)) {
                    is UpdateAvailability.NoUpdate -> UpdateCheckResult.NoUpdate
                    is UpdateAvailability.Available -> UpdateCheckResult.UpdateAvailable(decision.prompt)
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Update check failed: ${error.javaClass.simpleName}")
            UpdateCheckResult.Unavailable(error.javaClass.simpleName)
        }
    }

    companion object {
        /** Debug builds may talk to the local emulator backend over cleartext. */
        fun allowCleartext(): Boolean = BuildConfig.DEBUG
    }
}

private fun defaultUpdateClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

private fun defaultUpdateMetadataUrl(): HttpUrl? = try {
    ApiConfig.BASE_URL.toHttpUrlOrNull()
        ?.newBuilder()
        ?.addPathSegment("app-update")
        ?.build()
} catch (_: IllegalArgumentException) {
    null
}
