package com.streambox.mobile.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM contract tests for update validation/decision logic. They cover the
 * states the device flow depends on without needing a backend or emulator.
 */
class UpdatePolicyTest {

    private fun metadata(
        latestVersionCode: Long = 3,
        latestVersionName: String = "1.1.0",
        minimumSupportedVersionCode: Long = 1,
        releaseNotes: String = "Fixes",
        apkUrl: String = "https://releases.example.test/playdot-1.1.0.apk",
        sha256: String? = null,
        sizeBytes: Long = 0,
        packageName: String = "com.streambox.mobile",
    ) = UpdateMetadata(
        latestVersionCode = latestVersionCode,
        latestVersionName = latestVersionName,
        minimumSupportedVersionCode = minimumSupportedVersionCode,
        releaseNotes = releaseNotes,
        apkUrl = apkUrl,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        packageName = packageName,
    )

    private fun validate(value: UpdateMetadata, allowCleartext: Boolean = false) =
        UpdatePolicy.validate(value, allowCleartext, "com.streambox.mobile")

    @Test
    fun `newer version is an optional update above the floor`() {
        val result = UpdatePolicy.decide(2, validate(metadata()))
        val prompt = (result as UpdateAvailability.Available).prompt
        assertEquals(UpdateSeverity.OPTIONAL, prompt.severity)
        assertEquals(3, prompt.metadata.latestVersionCode)
    }

    @Test
    fun `installed version below minimum is mandatory`() {
        val result = UpdatePolicy.decide(2, validate(metadata(minimumSupportedVersionCode = 3)))
        val prompt = (result as UpdateAvailability.Available).prompt
        assertEquals(UpdateSeverity.MANDATORY, prompt.severity)
    }

    @Test
    fun `current or newer installed version is not prompted`() {
        val valid = validate(metadata())
        assertEquals(UpdateAvailability.NoUpdate, UpdatePolicy.decide(3, valid))
        assertEquals(UpdateAvailability.NoUpdate, UpdatePolicy.decide(4, valid))
    }

    @Test
    fun `valid metadata is normalized`() {
        val valid = validate(
            metadata(
                latestVersionName = " 1.1.0 ",
                releaseNotes = "  notes  ",
                sha256 = "A".repeat(64),
            ),
        )
        assertEquals("1.1.0", valid.latestVersionName)
        assertEquals("notes", valid.releaseNotes)
        assertEquals("a".repeat(64), valid.sha256)
    }

    @Test
    fun `release builds reject cleartext urls`() {
        assertFailsWith<InvalidUpdateMetadataException> {
            validate(metadata(apkUrl = "http://10.0.2.2:8001/app-release.apk"))
        }
        assertFalse(UpdatePolicy.isAllowedApkUrl("http://10.0.2.2:8001/app-release.apk", false))
    }

    @Test
    fun `debug builds may opt in to cleartext but release never does`() {
        assertTrue(
            UpdatePolicy.isAllowedApkUrl(
                "http://10.0.2.2:8001/app-release.apk",
                allowCleartext = true,
            ),
        )
        // Policy-level opt-in: the debug network-security-config still limits
        // the platform to loopback/emulator hosts.
        assertTrue(
            UpdatePolicy.isAllowedApkUrl(
                "http://releases.example.test/playdot.apk",
                allowCleartext = true,
            ),
        )
        assertFalse(
            UpdatePolicy.isAllowedApkUrl(
                "http://releases.example.test/playdot.apk",
                allowCleartext = false,
            ),
        )
    }

    @Test
    fun `urls with credentials or fragments are rejected`() {
        assertFalse(
            UpdatePolicy.isAllowedApkUrl(
                "https://user:pass@releases.example.test/playdot.apk",
                allowCleartext = false,
            ),
        )
        assertFalse(
            UpdatePolicy.isAllowedApkUrl(
                "https://releases.example.test/playdot.apk#fragment",
                allowCleartext = false,
            ),
        )
    }

    @Test
    fun `package mismatch is rejected`() {
        assertFailsWith<InvalidUpdateMetadataException> {
            UpdatePolicy.validate(
                metadata(packageName = "com.example.other"),
                allowCleartext = false,
                expectedPackage = "com.streambox.mobile",
            )
        }
    }

    @Test
    fun `bad checksum and oversized payload are rejected`() {
        assertFailsWith<InvalidUpdateMetadataException> {
            validate(metadata(sha256 = "not-a-digest"))
        }
        assertFailsWith<InvalidUpdateMetadataException> {
            validate(metadata(sizeBytes = UpdatePolicy.MAX_APK_BYTES + 1))
        }
    }

    @Test
    fun `minimum above latest is rejected`() {
        assertFailsWith<InvalidUpdateMetadataException> {
            validate(metadata(latestVersionCode = 3, minimumSupportedVersionCode = 4))
        }
    }
}
