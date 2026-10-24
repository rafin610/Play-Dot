package com.streambox.mobile.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

/**
 * Metadata returned by the backend `GET /app-update` endpoint.
 *
 * The schema intentionally mirrors the documented contract:
 * latestVersionCode / latestVersionName / minimumSupportedVersionCode /
 * releaseNotes / apkUrl, plus optional integrity fields (sha256, sizeBytes)
 * and the expected package name. Unknown fields are ignored by the shared
 * lenient JSON parser, so the backend can add fields without breaking older
 * clients.
 */
@Serializable
data class UpdateMetadata(
    @SerialName("latestVersionCode") val latestVersionCode: Long = 0L,
    @SerialName("latestVersionName") val latestVersionName: String = "",
    @SerialName("minimumSupportedVersionCode") val minimumSupportedVersionCode: Long = 0L,
    @SerialName("releaseNotes") val releaseNotes: String = "",
    @SerialName("apkUrl") val apkUrl: String = "",
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("sizeBytes") val sizeBytes: Long = 0L,
    @SerialName("packageName") val packageName: String = "",
)

enum class UpdateSeverity { OPTIONAL, MANDATORY }

/** A validated update plus the decision made for the installed build. */
data class UpdatePrompt(
    val metadata: UpdateMetadata,
    val severity: UpdateSeverity,
    val installedVersionCode: Long,
)

sealed interface UpdateAvailability {
    data object NoUpdate : UpdateAvailability
    data class Available(val prompt: UpdatePrompt) : UpdateAvailability
}

class InvalidUpdateMetadataException(message: String) : Exception(message)

/**
 * Pure, testable update decisions and metadata validation. Keeping this free of
 * Android imports lets the JVM unit tests cover the contract without a device.
 */
object UpdatePolicy {

    /** Upper bound avoids a hostile/incorrect metadata value filling app cache. */
    const val MAX_APK_BYTES: Long = 250L * 1024 * 1024
    const val MAX_RELEASE_NOTES_CHARS: Int = 8_000
    private const val MAX_VERSION_NAME_CHARS = 64
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Validates and normalizes metadata. [allowCleartext] must only be true for
     * debug builds; release builds require HTTPS. [expectedPackage] is the
     * installed applicationId and must match the payload.
     */
    fun validate(
        metadata: UpdateMetadata,
        allowCleartext: Boolean,
        expectedPackage: String,
    ): UpdateMetadata {
        if (metadata.latestVersionCode < 1L) {
            throw InvalidUpdateMetadataException("latestVersionCode must be positive")
        }
        val versionName = metadata.latestVersionName.trim()
        if (versionName.isEmpty() || versionName.length > MAX_VERSION_NAME_CHARS) {
            throw InvalidUpdateMetadataException("latestVersionName is missing or too long")
        }
        if (metadata.minimumSupportedVersionCode < 0L ||
            metadata.minimumSupportedVersionCode > metadata.latestVersionCode
        ) {
            throw InvalidUpdateMetadataException("minimumSupportedVersionCode is out of range")
        }
        val packageName = metadata.packageName.trim()
        if (packageName.isEmpty()) {
            throw InvalidUpdateMetadataException("packageName is missing")
        }
        if (packageName != expectedPackage) {
            throw InvalidUpdateMetadataException("packageName does not match the installed app")
        }
        val apkUrl = metadata.apkUrl.trim()
        if (!isAllowedApkUrl(apkUrl, allowCleartext)) {
            throw InvalidUpdateMetadataException("apkUrl must be an allowed HTTPS URL")
        }
        val sha = metadata.sha256?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        if (sha != null && !SHA256_PATTERN.matches(sha)) {
            throw InvalidUpdateMetadataException("sha256 must be a 64-character hex digest")
        }
        if (metadata.sizeBytes < 0L || metadata.sizeBytes > MAX_APK_BYTES) {
            throw InvalidUpdateMetadataException("sizeBytes is out of range")
        }
        val notes = metadata.releaseNotes.trim().take(MAX_RELEASE_NOTES_CHARS)

        return metadata.copy(
            latestVersionName = versionName,
            releaseNotes = notes,
            apkUrl = apkUrl,
            sha256 = sha,
            packageName = packageName,
        )
    }

    /**
     * HTTPS is always allowed. Cleartext is allowed only when the caller opts
     * in (debug builds). The debug network-security-config independently
     * narrows that to loopback/emulator hosts, so release builds have no
     * cleartext path at all.
     */
    fun isAllowedApkUrl(rawUrl: String, allowCleartext: Boolean): Boolean {
        if (rawUrl.isBlank()) return false
        val uri = try {
            URI(rawUrl)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme == null || uri.host.isNullOrBlank()) return false
        if (uri.userInfo != null) return false
        if (uri.fragment != null) return false
        return when (uri.scheme.lowercase(Locale.US)) {
            "https" -> true
            "http" -> allowCleartext
            else -> false
        }
    }

    /** Mandatory once the installed build is below the supported floor. */
    fun decide(installedVersionCode: Long, metadata: UpdateMetadata): UpdateAvailability {
        if (installedVersionCode >= metadata.latestVersionCode) {
            return UpdateAvailability.NoUpdate
        }
        val severity = if (installedVersionCode < metadata.minimumSupportedVersionCode) {
            UpdateSeverity.MANDATORY
        } else {
            UpdateSeverity.OPTIONAL
        }
        return UpdateAvailability.Available(
            UpdatePrompt(
                metadata = metadata,
                severity = severity,
                installedVersionCode = installedVersionCode,
            ),
        )
    }
}
