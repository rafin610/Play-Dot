package com.streambox.mobile.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a downloaded APK before handing it to the package installer:
 *
 *  1. It is a parseable package.
 *  2. Its applicationId is the installed app's package name.
 *  3. Its versionCode is at least the version advertised by metadata.
 *  4. Its signing certificate is byte-identical to the installed app's signer.
 *
 * Step 4 is essential for a direct-APK update: Android itself will reject a
 * mismatched signer at install time, but checking here produces a clear error
 * instead of a generic package-installer failure.
 */
class ApkVerifier(private val context: Context) {

    fun verify(apkFile: File, metadata: UpdateMetadata) {
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, signatureFlags())
            ?: throw UpdateVerificationException("The downloaded file is not a valid Android package")

        val installedPackage = context.packageName
        if (archive.packageName != installedPackage) {
            throw UpdateVerificationException("The downloaded package does not match this app")
        }
        if (metadata.packageName.isNotBlank() && metadata.packageName != archive.packageName) {
            throw UpdateVerificationException("The update metadata package name does not match")
        }

        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archive)
        if (archiveVersionCode < metadata.latestVersionCode) {
            throw UpdateVerificationException("The downloaded package is older than the advertised update")
        }

        val archiveSigner = signerSha256(archive, installedPackage)
        val installedSigner = installedSignerSha256(packageManager, installedPackage)
        if (!MessageDigest.isEqual(archiveSigner, installedSigner)) {
            throw UpdateVerificationException("The downloaded package was signed by a different key")
        }
    }

    private fun installedSignerSha256(packageManager: PackageManager, packageName: String): ByteArray {
        val installed = packageManager.getPackageInfo(packageName, signatureFlags())
        return signerSha256(installed, packageName)
    }

    private fun signerSha256(packageInfo: PackageInfo, packageName: String): ByteArray {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
                ?: throw UpdateVerificationException("No signing information for $packageName")
            if (signingInfo.hasMultipleSigners()) {
                throw UpdateVerificationException("Multiple signing keys are not supported for updates")
            }
            signingInfo.apkContentsSigners
                ?: throw UpdateVerificationException("No current signer for $packageName")
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: throw UpdateVerificationException("No signature for $packageName")
        }
        if (signatures.isEmpty()) {
            throw UpdateVerificationException("No signature for $packageName")
        }
        return MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
    }

    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
}
