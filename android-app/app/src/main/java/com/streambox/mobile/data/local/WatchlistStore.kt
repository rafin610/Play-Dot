package com.streambox.mobile.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streambox.mobile.data.remote.FeedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Phase 2 watchlist: local-only persistence (no backend, no account).
 * Stores minimal FeedItem summaries as JSON in DataStore preferences.
 * The backend is intentionally NOT involved — provider watchlist endpoints
 * (/watchlist, /watchlist/toggle) were not verified in Phase 1.
 */
private const val TAG = "WatchlistStore"

private val Context.dataStore by preferencesDataStore(
    name = "streambox_prefs",
    // A corrupted preferences file is recreated instead of crashing at startup.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@kotlinx.serialization.Serializable
data class WatchlistEntry(
    val subjectId: String,
    val title: String,
    val poster: String,
    val subjectType: Int? = null,
    val score: String? = null,
    val releaseTime: String? = null,
)

class WatchlistStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val key = stringPreferencesKey("watchlist_entries")

    /** Cross-cutting flow for UI (Watchlist tab, detail toggle state). */
    private val fallback = MutableStateFlow<List<WatchlistEntry>>(emptyList())

    val entries: StateFlow<List<WatchlistEntry>> get() = fallback

    fun observe(): Flow<List<WatchlistEntry>> = context.dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.w(TAG, "Watchlist read failed; showing cached entries", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            prefs[key]?.let { decode(it) } ?: emptyList()
        }

    suspend fun load(): List<WatchlistEntry> {
        val prefs = try {
            context.dataStore.data.first()
        } catch (e: IOException) {
            Log.w(TAG, "Watchlist load failed; keeping cached entries", e)
            return fallback.value
        }
        val list = decode(prefs[key] ?: "[]")
        fallback.value = list
        return list
    }

    suspend fun contains(subjectId: String): Boolean =
        load().any { it.subjectId == subjectId }

    /** Watchlist screen: remove by id. */
    suspend fun remove(subjectId: String) {
        val current = load().filterNot { it.subjectId == subjectId }
        save(current)
    }

    suspend fun toggle(item: FeedItem) {
        val current = load().toMutableList()
        val existing = current.firstOrNull { it.subjectId == item.subjectId }
        if (existing != null) {
            current.remove(existing)
        } else {
            current.add(0, WatchlistEntry(
                subjectId = item.subjectId,
                title = item.title,
                poster = item.imageUrl,
                subjectType = item.subjectType,
                score = item.score,
                releaseTime = item.releaseTime,
            ))
        }
        save(current)
    }

    private suspend fun save(list: List<WatchlistEntry>) {
        try {
            context.dataStore.edit { prefs ->
                prefs[key] = json.encodeToString(ListSerializer(WatchlistEntry.serializer()), list)
            }
            fallback.value = list
        } catch (e: IOException) {
            // Local persistence failed (disk full / I/O). Keep the UI usable and log it;
            // do not crash the app over the watchlist cache.
            Log.e(TAG, "Could not persist watchlist", e)
        }
    }

    private fun decode(raw: String): List<WatchlistEntry> = runCatching {
        json.decodeFromString(ListSerializer(WatchlistEntry.serializer()), raw)
    }.getOrDefault(emptyList())
}
