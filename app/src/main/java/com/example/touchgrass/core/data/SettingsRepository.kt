package com.example.touchgrass.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "touchgrass_settings")

/** Snapshot of one day's persisted usage. */
data class PersistedDay(
    val dateKey: String,
    val count: Int,
    val timeMillis: Long
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SHORTS_LIMIT = intPreferencesKey("shorts_limit")
        val DAY_KEY = stringPreferencesKey("day_key")
        val DAY_COUNT = intPreferencesKey("day_count")
        val DAY_TIME_MILLIS = longPreferencesKey("day_time_millis")
    }

    val shortsLimit: Flow<Int> = context.dataStore.data
        .map { it[Keys.SHORTS_LIMIT] ?: DEFAULT_LIMIT }

    suspend fun setShortsLimit(limit: Int) {
        context.dataStore.edit { it[Keys.SHORTS_LIMIT] = limit.coerceIn(MIN_LIMIT, MAX_LIMIT) }
    }

    /** Returns today's stats, or a zeroed snapshot if the stored day is stale. */
    suspend fun loadToday(): PersistedDay {
        val prefs = context.dataStore.data.first()
        val today = LocalDate.now().toString()
        return if (prefs[Keys.DAY_KEY] == today) {
            PersistedDay(today, prefs[Keys.DAY_COUNT] ?: 0, prefs[Keys.DAY_TIME_MILLIS] ?: 0L)
        } else {
            PersistedDay(today, 0, 0L)
        }
    }

    suspend fun saveToday(count: Int, timeMillis: Long) {
        context.dataStore.edit {
            it[Keys.DAY_KEY] = LocalDate.now().toString()
            it[Keys.DAY_COUNT] = count
            it[Keys.DAY_TIME_MILLIS] = timeMillis
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
    }
}
