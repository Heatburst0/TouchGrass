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
        val EXTRA_SHORTS_DAY = stringPreferencesKey("extra_shorts_day")
        val EXTRA_SHORTS_COUNT = intPreferencesKey("extra_shorts_count")
        val NUDGE_DAY = stringPreferencesKey("nudge_day")
        val NUDGE_BUCKET = intPreferencesKey("nudge_bucket")
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

    // ---- Earned extra shorts (redeemed with reading points; day-scoped) ----

    val extraShortsToday: Flow<Int> = context.dataStore.data.map {
        if (it[Keys.EXTRA_SHORTS_DAY] == LocalDate.now().toString()) {
            it[Keys.EXTRA_SHORTS_COUNT] ?: 0
        } else 0
    }

    suspend fun addExtraShorts(amount: Int) {
        context.dataStore.edit {
            val today = LocalDate.now().toString()
            if (it[Keys.EXTRA_SHORTS_DAY] != today) {
                it[Keys.EXTRA_SHORTS_DAY] = today
                it[Keys.EXTRA_SHORTS_COUNT] = amount
            } else {
                it[Keys.EXTRA_SHORTS_COUNT] = (it[Keys.EXTRA_SHORTS_COUNT] ?: 0) + amount
            }
        }
    }

    // ---- Screen-time nudge bookkeeping (which 3h bucket we last nudged for) ----

    suspend fun getNudgeBucket(dayKey: String): Int {
        val prefs = context.dataStore.data.first()
        return if (prefs[Keys.NUDGE_DAY] == dayKey) prefs[Keys.NUDGE_BUCKET] ?: 0 else 0
    }

    suspend fun setNudgeBucket(dayKey: String, bucket: Int) {
        context.dataStore.edit {
            it[Keys.NUDGE_DAY] = dayKey
            it[Keys.NUDGE_BUCKET] = bucket
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
    }
}
