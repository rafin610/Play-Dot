package com.streambox.mobile

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.streambox.mobile.data.local.ProgressStore
import com.streambox.mobile.data.local.WatchlistStore
import com.streambox.mobile.data.remote.ApiClient
import com.streambox.mobile.data.repo.ContentRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal manual DI container (no extra framework, Phase 2 scope). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val repository = ContentRepository(ApiClient.api)
    val watchlist = WatchlistStore(context.applicationContext)
    val progress = ProgressStore(context.applicationContext)
}

class StreamBoxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = AppContainer(this)
    }

    /**
     * Writes the next fatal stack trace to files/last_crash.txt, then delegates
     * to Android's default handler. This is diagnostics only — it never
     * suppresses the crash. Retrieve with:
     *   adb shell run-as com.streambox.mobile cat files/last_crash.txt
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(filesDir, "last_crash.txt").writeText(
                    buildString {
                        appendLine("Play Dot crash report")
                        appendLine("time=$stamp thread=${thread.name}")
                        appendLine("app=${packageName}")
                        appendLine(Log.getStackTraceString(throwable))
                    },
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
