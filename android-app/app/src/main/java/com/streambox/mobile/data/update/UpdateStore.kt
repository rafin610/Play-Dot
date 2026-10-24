package com.streambox.mobile.data.update

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.updateDataStore by preferencesDataStore(
    name = "streambox_update",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persists the optional update the user dismissed, so an available-but-skipped
 * version does not reappear on every launch. Mandatory updates ignore this
 * value entirely.
 */
class UpdateStore(private val context: Context) {

    private val skippedVersionKey = longPreferencesKey("skipped_optional_version_code")

    suspend fun skippedVersionCode(): Long? = try {
        context.updateDataStore.data.first()[skippedVersionKey]
    } catch (_: Exception) {
        null
    }

    suspend fun markOptionalUpdateSkipped(versionCode: Long) {
        context.updateDataStore.edit { preferences ->
            preferences[skippedVersionKey] = versionCode
        }
    }
}
