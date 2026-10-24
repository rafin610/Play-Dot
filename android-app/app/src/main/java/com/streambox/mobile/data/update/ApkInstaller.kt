package com.streambox.mobile.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Starts the platform package-installer for a verified APK. The installer is
 * always the Android system UI: this app never installs silently and never
 * disables package signature verification.
 */
object ApkInstaller {

    fun fileProviderUri(context: Context, apkFile: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile,
    )

    fun canRequestPackageInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownAppSourcesSettingsIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    /** Returns false when no package-installer activity is available. */
    fun install(context: Context, apkFile: File): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                fileProviderUri(context, apkFile),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
