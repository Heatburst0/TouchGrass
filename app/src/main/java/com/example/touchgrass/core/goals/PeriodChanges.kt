package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.GoalEntity
import org.json.JSONObject

/*
 * Changes to a recurring goal that wait for its current period to close. Editing a
 * weekly commitment mid-week (lowering it, removing and re-adding it) must not dodge
 * this week's penalty or earn this week's reward twice — so the edit is recorded now
 * and applied by GoalEngine when it rolls the period.
 */

private const val NEXT_TARGET = "nextTarget"
private const val RETIRE_AT_PERIOD_END = "retireAtPeriodEnd"

/** The target the goal switches to at the next period, if one is pending. */
fun GoalEntity.pendingTarget(): Int? =
    JSONObject(configJson).optInt(NEXT_TARGET, 0).takeIf { it > 0 }

/** Whether the goal retires when its current period closes. */
fun GoalEntity.retiring(): Boolean = JSONObject(configJson).optBoolean(RETIRE_AT_PERIOD_END, false)

/** Use [next] from the next period on (scheduling the current target clears any pending change). */
fun GoalEntity.scheduleTarget(next: Int): GoalEntity = withConfig {
    if (next == target) remove(NEXT_TARGET) else put(NEXT_TARGET, next)
}

fun GoalEntity.scheduleRetire(): GoalEntity = withConfig { put(RETIRE_AT_PERIOD_END, true) }

fun GoalEntity.cancelRetire(): GoalEntity = withConfig { remove(RETIRE_AT_PERIOD_END) }

/** Apply what was scheduled, at a period roll. Null = the goal retires now. */
fun GoalEntity.applyScheduledChanges(): GoalEntity? {
    if (retiring()) return null
    val next = pendingTarget() ?: return this
    return copy(target = next).withConfig { remove(NEXT_TARGET) }
}

private fun GoalEntity.withConfig(change: JSONObject.() -> Unit): GoalEntity {
    val config = JSONObject(configJson).apply(change).toString()
    return if (config == configJson) this else copy(configJson = config)
}
