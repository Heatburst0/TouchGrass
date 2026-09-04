package com.example.touchgrass.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val READING_GATE_ENABLED = booleanPreferencesKey("reading_gate_enabled")
        val READING_GATE_PENDING = booleanPreferencesKey("reading_gate_pending")
        val PENALTY_SHORTS_DAY = stringPreferencesKey("penalty_shorts_day")
        val PENALTY_SHORTS_COUNT = intPreferencesKey("penalty_shorts_count")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val NUDGE_INTERVAL_MIN = intPreferencesKey("nudge_interval_min")
        val GOAL_LOCK_ENABLED = booleanPreferencesKey("goal_lock_enabled")
        val SHORTS_TEST_MODE = booleanPreferencesKey("shorts_test_mode")
        val GITHUB_GOALS_MIGRATED = booleanPreferencesKey("github_goals_migrated")
        val PLEDGES_MIGRATED = booleanPreferencesKey("pledges_migrated")
        val GITHUB_RECURRING_MIGRATED = booleanPreferencesKey("github_recurring_migrated")
        val ACTIVE_FOCUS = stringPreferencesKey("active_focus")
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

    // ---- Penalty shorts (missed-commitment punishment; day-scoped) ----

    val penaltyShortsToday: Flow<Int> = context.dataStore.data.map {
        if (it[Keys.PENALTY_SHORTS_DAY] == LocalDate.now().toString()) {
            it[Keys.PENALTY_SHORTS_COUNT] ?: 0
        } else 0
    }

    suspend fun addPenaltyShorts(amount: Int) {
        context.dataStore.edit {
            val today = LocalDate.now().toString()
            if (it[Keys.PENALTY_SHORTS_DAY] != today) {
                it[Keys.PENALTY_SHORTS_DAY] = today
                it[Keys.PENALTY_SHORTS_COUNT] = amount
            } else {
                it[Keys.PENALTY_SHORTS_COUNT] = (it[Keys.PENALTY_SHORTS_COUNT] ?: 0) + amount
            }
        }
    }

    // ---- Screen-time nudge / force-read interval (minutes of watch time) ----

    val nudgeIntervalMinutes: Flow<Int> = context.dataStore.data
        .map { it[Keys.NUDGE_INTERVAL_MIN] ?: DEFAULT_NUDGE_MINUTES }

    suspend fun setNudgeIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.NUDGE_INTERVAL_MIN] = minutes.coerceAtLeast(15) }
    }

    // ---- Goal lock: block entertainment until today's commit is done ----

    val goalLockEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.GOAL_LOCK_ENABLED] ?: false }

    suspend fun setGoalLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GOAL_LOCK_ENABLED] = enabled }
    }

    // ---- Detector test mode: count tracked shorts WITHOUT touching the real limit ----

    val shortsTestMode: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SHORTS_TEST_MODE] ?: false }

    suspend fun setShortsTestMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHORTS_TEST_MODE] = enabled }
    }

    // ---- One-time flag: legacy github_goals rows copied into the goals table ----

    val githubGoalsMigrated: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.GITHUB_GOALS_MIGRATED] ?: false }

    suspend fun setGithubGoalsMigrated(done: Boolean) {
        context.dataStore.edit { it[Keys.GITHUB_GOALS_MIGRATED] = done }
    }

    // ---- Focus session (raw JSON; the focus layer (de)serializes it) ----

    val activeFocusJson: Flow<String> = context.dataStore.data
        .map { it[Keys.ACTIVE_FOCUS] ?: "" }

    suspend fun setActiveFocusJson(json: String) {
        context.dataStore.edit { it[Keys.ACTIVE_FOCUS] = json }
    }

    val pledgesMigrated: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.PLEDGES_MIGRATED] ?: false }

    suspend fun setPledgesMigrated(done: Boolean) {
        context.dataStore.edit { it[Keys.PLEDGES_MIGRATED] = done }
    }

    val githubRecurringMigrated: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.GITHUB_RECURRING_MIGRATED] ?: false }

    suspend fun setGithubRecurringMigrated(done: Boolean) {
        context.dataStore.edit { it[Keys.GITHUB_RECURRING_MIGRATED] = done }
    }

    // ---- GitHub (optional PAT for private repos / higher rate limit) ----

    val githubToken: Flow<String> = context.dataStore.data
        .map { it[Keys.GITHUB_TOKEN] ?: "" }

    suspend fun setGithubToken(token: String) {
        context.dataStore.edit { it[Keys.GITHUB_TOKEN] = token.trim() }
    }

    // ---- Force-read gate (screen-time balance) ----

    /** User opted into hard-blocking watch apps until a page is verified. */
    val readingGateEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.READING_GATE_ENABLED] ?: false }

    /** A gate is currently owed — watch apps stay blocked until cleared. */
    val readingGatePending: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.READING_GATE_PENDING] ?: false }

    suspend fun setReadingGateEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.READING_GATE_ENABLED] = enabled }
    }

    suspend fun setReadingGatePending(pending: Boolean) {
        context.dataStore.edit { it[Keys.READING_GATE_PENDING] = pending }
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
        const val DEFAULT_NUDGE_MINUTES = 180
    }
}
