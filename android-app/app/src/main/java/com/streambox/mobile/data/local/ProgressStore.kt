package com.streambox.mobile.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

private const val TAG = "ProgressStore"

private val Context.progressDataStore by preferencesDataStore(
    name = "streambox_progress",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Phase 3 local playback progress (continuity). Stored per device; the provider
 * account history endpoints were never verified, so progress stays local-only
 * (and never claims to be cloud-synced).
 */
class ProgressStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val key = stringPreferencesKey("playback_positions")

    suspend fun save(position: PlaybackPosition) {
        try {
            val list = decode(context.progressDataStore.data.first()[key])
                .filterNot {
                    it.subjectId == position.subjectId &&
                        it.season == position.season &&
                        it.episode == position.episode
                }
                .toMutableList()
            list.add(0, position)
            // Keep the map bounded.
            val trimmed = list.take(200)
            context.progressDataStore.edit { prefs ->
                prefs[key] = json.encodeToString(ListSerializer(PlaybackPosition.serializer()), trimmed)
            }
        } catch (e: IOException) {
            // Non-fatal: losing a resume offset must never crash playback.
            Log.w(TAG, "Could not save playback position", e)
        }
    }

    /** Stored position for resuming; null when playback only ever reached the intro. */
    suspend fun find(subjectId: String, season: Int = 1, episode: Int = 1): PlaybackPosition? = try {
        decode(context.progressDataStore.data.first()[key]).firstOrNull {
            it.subjectId == subjectId && it.season == season && it.episode == episode
        }
    } catch (e: IOException) {
        Log.w(TAG, "Could not read playback position", e)
        null
    }

    private fun decode(raw: String?): List<PlaybackPosition> = runCatching {
        if (raw.isNullOrBlank()) emptyList()
        else json.decodeFromString(ListSerializer(PlaybackPosition.serializer()), raw)
    }.getOrDefault(emptyList())
}
