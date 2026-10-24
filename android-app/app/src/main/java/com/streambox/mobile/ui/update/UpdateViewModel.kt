package com.streambox.mobile.ui.update

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streambox.mobile.data.update.ApkDownloader
import com.streambox.mobile.data.update.ApkInstaller
import com.streambox.mobile.data.update.ApkVerifier
import com.streambox.mobile.data.update.UpdateCheckResult
import com.streambox.mobile.data.update.UpdateDownloadException
import com.streambox.mobile.data.update.UpdatePrompt
import com.streambox.mobile.data.update.UpdateRepository
import com.streambox.mobile.data.update.UpdateSeverity
import com.streambox.mobile.data.update.UpdateStore
import com.streambox.mobile.data.update.UpdateVerificationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "UpdateViewModel"
private const val STARTUP_DELAY_MS = 1_500L

data class UpdateUiState(
    val prompt: UpdatePrompt? = null,
    val currentVersionName: String = "",
    val downloading: Boolean = false,
    val progress: Float? = null,
    val verifying: Boolean = false,
    val apkReady: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Coordinates the optional/mandatory update dialog, background download, APK
 * verification and installer hand-off. Nothing here runs on the startup path:
 * [checkForUpdates] is fired after the first frame and is a no-op on error.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = UpdateRepository()
    private val store = UpdateStore(application)
    private val downloader = ApkDownloader(directory = File(application.cacheDir, "updates"))
    private val verifier = ApkVerifier(application)

    private val _state = MutableStateFlow(
        UpdateUiState(currentVersionName = installedVersionName()),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var activePrompt: UpdatePrompt? = null
    private var verifiedApk: File? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun checkForUpdates() {
        if (checkJob?.isActive == true || activePrompt != null) return
        checkJob = viewModelScope.launch {
            // Give the first screen a chance to draw; an update check must never
            // delay launch and is allowed to fail silently.
            delay(STARTUP_DELAY_MS)
            val application = getApplication<Application>()
            val installedCode = installedVersionCode()
            when (
                val result = repository.checkForUpdate(
                    installedVersionCode = installedCode,
                    expectedPackage = application.packageName,
                    allowCleartext = UpdateRepository.allowCleartext(),
                )
            ) {
                is UpdateCheckResult.UpdateAvailable -> {
                    if (result.prompt.severity == UpdateSeverity.OPTIONAL &&
                        result.prompt.metadata.latestVersionCode <= (store.skippedVersionCode() ?: 0L)
                    ) {
                        Log.d(TAG, "Optional update ${result.prompt.metadata.latestVersionName} already skipped")
                        return@launch
                    }
                    activePrompt = result.prompt
                    _state.update { it.copy(prompt = result.prompt) }
                }
                is UpdateCheckResult.NoUpdate -> Unit
                is UpdateCheckResult.Unavailable -> {
                    // Do not interrupt the user; the next launch retries naturally.
                    Log.d(TAG, "Update check unavailable")
                }
            }
        }
    }

    /** Dismisses an optional update and remembers the choice. Mandatory is a no-op. */
    fun onLater() {
        val prompt = activePrompt ?: return
        if (prompt.severity == UpdateSeverity.MANDATORY) return
        viewModelScope.launch {
            runCatching { store.markOptionalUpdateSkipped(prompt.metadata.latestVersionCode) }
        }
        activePrompt = null
        _state.update { it.copy(prompt = null) }
    }

    fun onUpdateNow() {
        if (downloadJob?.isActive == true) return
        val prompt = activePrompt ?: return
        downloadJob = viewModelScope.launch {
            var downloadedFile: File? = null
            _state.update {
                it.copy(
                    downloading = true,
                    verifying = false,
                    progress = null,
                    apkReady = false,
                    errorMessage = null,
                )
            }
            try {
                val file = downloader.download(
                    url = prompt.metadata.apkUrl,
                    expectedSha256 = prompt.metadata.sha256,
                    expectedSizeBytes = prompt.metadata.sizeBytes,
                ) { loaded, total ->
                    val fraction = if (total > 0L) {
                        (loaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _state.update { it.copy(progress = fraction) }
                }
                downloadedFile = file
                _state.update { it.copy(downloading = false, verifying = true) }
                withContext(Dispatchers.IO) { verifier.verify(file, prompt.metadata) }
                verifiedApk = file
                _state.update {
                    it.copy(
                        verifying = false,
                        apkReady = true,
                        progress = 1f,
                        errorMessage = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                downloadedFile?.delete()
                throw cancellation
            } catch (error: Throwable) {
                verifiedApk = null
                downloadedFile?.delete()
                Log.w(TAG, "Update download/verification failed: ${error.javaClass.simpleName}")
                _state.update {
                    it.copy(
                        downloading = false,
                        verifying = false,
                        apkReady = false,
                        errorMessage = userMessage(error),
                    )
                }
            }
        }
    }

    fun onRetry() {
        onUpdateNow()
    }

    /**
     * Starts the Android installer for the verified APK.
     *
     * Returns true when the installer UI was opened. Returns false when the
     * download is not ready, the "install unknown apps" permission is missing,
     * or no package-installer activity exists; callers surface [onInstallFailed].
     */
    fun install(context: Context): Boolean {
        val file = verifiedApk ?: return false
        if (!ApkInstaller.canRequestPackageInstalls(context)) return false
        return ApkInstaller.install(context, file)
    }

    fun onInstallFailed() {
        _state.update {
            it.copy(errorMessage = "The installer could not be opened. Please install the update manually.")
        }
    }

    fun onInstallPermissionDenied() {
        _state.update {
            it.copy(
                errorMessage = "Allow Play Dot to install unknown apps, then tap Install update again.",
            )
        }
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is UpdateDownloadException -> "The update download failed. Check your connection and try again."
        is UpdateVerificationException -> "The downloaded update could not be verified and was removed."
        else -> "The update could not be completed. Please try again."
    }

    private fun installedVersionCode(): Long {
        val application = getApplication<Application>()
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    private fun installedVersionName(): String {
        val application = getApplication<Application>()
        return application.packageManager
            .getPackageInfo(application.packageName, 0)
            .versionName
            .orEmpty()
    }

    override fun onCleared() {
        checkJob?.cancel()
        downloadJob?.cancel()
        super.onCleared()
    }
}
