package com.streambox.mobile.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class UpdateDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UpdateVerificationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streams an APK into the app cache with bounded size, then checks the
 * advertised SHA-256 and byte count. The installer permission prompt happens
 * later, after [ApkVerifier] confirms package identity and signer continuity.
 */
class ApkDownloader(
    private val client: OkHttpClient = defaultDownloadClient(),
    private val directory: File,
) {

    suspend fun download(
        url: String,
        expectedSha256: String?,
        expectedSizeBytes: Long,
        onProgress: (loaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw UpdateDownloadException("Could not create the update cache directory")
        }
        val temp = File(directory, TEMP_FILE_NAME)
        val destination = File(directory, APK_FILE_NAME)
        temp.delete()
        destination.delete()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateDownloadException("Update server returned HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw UpdateDownloadException("Update server returned an empty body")
                val contentLength = body.contentLength().takeIf { it > 0 }
                if (contentLength != null && contentLength > UpdatePolicy.MAX_APK_BYTES) {
                    throw UpdateDownloadException("Update file is larger than the allowed limit")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var loaded = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            loaded += read
                            if (loaded > UpdatePolicy.MAX_APK_BYTES) {
                                throw UpdateDownloadException("Update file is larger than the allowed limit")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onProgress(loaded, expectedSizeBytes.takeIf { it > 0 } ?: contentLength ?: -1L)
                        }
                        output.flush()
                    }
                }

                if (expectedSizeBytes > 0L && loaded != expectedSizeBytes) {
                    throw UpdateDownloadException("Update file size did not match the published metadata")
                }
                if (!expectedSha256.isNullOrBlank()) {
                    val actual = digest.digest().toHexString()
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        throw UpdateDownloadException("Update file checksum did not match the published metadata")
                    }
                }
            }
            destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            destination
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    companion object {
        private const val TEMP_FILE_NAME = "app-update.apk.part"
        private const val APK_FILE_NAME = "app-update.apk"
    }
}

private fun defaultDownloadClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.MINUTES)
    .build()

private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val output = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        output[index * 2] = digits[value ushr 4]
        output[index * 2 + 1] = digits[value and 0x0F]
    }
    return String(output)
}
