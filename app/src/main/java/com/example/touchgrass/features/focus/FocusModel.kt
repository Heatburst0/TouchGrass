package com.example.touchgrass.features.focus

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Pomodoro-style focus session config: [cycles] rounds of [focusBlockMin]
 * minutes of focus each, separated by [breakMin]-minute breaks (no trailing break
 * after the last focus block). [blockedPackages] are bounced during focus blocks.
 *
 * [strict] makes the session hard to bail on: an ongoing (non-dismissible)
 * notification is posted and the in-app "End session" button is hidden until the
 * session finishes. Android can't make a session truly unkillable, so this is a
 * deterrent, not a jail.
 */
data class FocusConfig(
    val focusBlockMin: Int,
    val breakMin: Int,
    val cycles: Int,
    val blockedPackages: Set<String>,
    val strict: Boolean = false
) {
    /** focus×cycles + break×(cycles-1). */
    val totalMinutes: Int
        get() = cycles * focusBlockMin + (cycles - 1).coerceAtLeast(0) * breakMin

    /** Pure focus minutes (breaks excluded) — the "productive" budget. */
    val focusMinutes: Int
        get() = cycles * focusBlockMin

    companion object {
        const val MIN_FOCUS = 5
        const val MAX_FOCUS = 120
        const val MIN_BREAK = 0
        const val MAX_CYCLES = 8

        /** Keep breaks from eating the session — a break can't exceed the focus block. */
        fun capBreak(focusBlockMin: Int, breakMin: Int): Int =
            breakMin.coerceIn(MIN_BREAK, focusBlockMin)
    }
}

/** A started session: the wall-clock start plus its config. Phase is derived. */
data class ActiveFocus(val startAt: Long, val config: FocusConfig) {
    /** Epoch millis the session naturally completes. */
    val endAt: Long get() = startAt + config.totalMinutes * 60_000L
}

/** How a recorded session ended. Stored as [name] in the history table. */
enum class FocusOutcome { COMPLETED, ENDED_EARLY }

/** Where the session is right now, derived from [ActiveFocus.startAt] + config. */
sealed interface FocusPhase {
    data object Idle : FocusPhase
    data class Focusing(
        val cycle: Int,
        val totalCycles: Int,
        val blockRemainingSec: Long,
        val sessionRemainingSec: Long
    ) : FocusPhase
    data class OnBreak(
        val cycle: Int,
        val totalCycles: Int,
        val breakRemainingSec: Long
    ) : FocusPhase
    data object Done : FocusPhase
}

/** Pure: what phase [a] is in at [now] (epoch millis). */
fun focusPhaseAt(a: ActiveFocus?, now: Long): FocusPhase {
    if (a == null) return FocusPhase.Idle
    val cfg = a.config
    val elapsed = ((now - a.startAt) / 1000).coerceAtLeast(0)
    val focusSec = cfg.focusBlockMin * 60L
    val breakSec = cfg.breakMin * 60L
    val totalSec = cfg.totalMinutes * 60L
    if (elapsed >= totalSec) return FocusPhase.Done

    var cursor = 0L
    for (c in 1..cfg.cycles) {
        if (elapsed < cursor + focusSec) {
            return FocusPhase.Focusing(
                cycle = c, totalCycles = cfg.cycles,
                blockRemainingSec = cursor + focusSec - elapsed,
                sessionRemainingSec = totalSec - elapsed
            )
        }
        cursor += focusSec
        if (c < cfg.cycles) {
            if (elapsed < cursor + breakSec) {
                return FocusPhase.OnBreak(c, cfg.cycles, cursor + breakSec - elapsed)
            }
            cursor += breakSec
        }
    }
    return FocusPhase.Done
}

/** Pure: whole focus minutes actually served by [now] (breaks excluded). Used to
 *  record how much productive time an early-ended session earned. */
fun focusedMinutesAt(a: ActiveFocus, now: Long): Int {
    val cfg = a.config
    val elapsed = ((now - a.startAt) / 1000).coerceAtLeast(0)
    val focusSec = cfg.focusBlockMin * 60L
    val breakSec = cfg.breakMin * 60L
    var cursor = 0L
    var focusedSec = 0L
    for (c in 1..cfg.cycles) {
        focusedSec += (elapsed - cursor).coerceIn(0L, focusSec)
        cursor += focusSec
        if (c < cfg.cycles) cursor += breakSec
    }
    return (focusedSec / 60).toInt()
}

// ---- JSON persistence (stored in DataStore so a session survives process death) ----

fun ActiveFocus.toJson(): String = JSONObject()
    .put("startAt", startAt)
    .put("config", JSONObject()
        .put("focusBlockMin", config.focusBlockMin)
        .put("breakMin", config.breakMin)
        .put("cycles", config.cycles)
        .put("strict", config.strict)
        .put("blocked", JSONArray(config.blockedPackages.toList())))
    .toString()

fun activeFocusFromJson(json: String?): ActiveFocus? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val c = o.getJSONObject("config")
        val arr = c.optJSONArray("blocked") ?: JSONArray()
        val blocked = (0 until arr.length()).map { arr.getString(it) }.toSet()
        ActiveFocus(
            startAt = o.getLong("startAt"),
            config = FocusConfig(
                focusBlockMin = c.getInt("focusBlockMin"),
                breakMin = c.getInt("breakMin"),
                cycles = c.getInt("cycles"),
                blockedPackages = blocked,
                strict = c.optBoolean("strict", false)
            )
        )
    }.getOrNull()
}
