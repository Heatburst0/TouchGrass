package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.GoalEntity
import java.time.Instant
import java.time.ZoneId

/**
 * What applying one verified result to a goal did: the row to save ([goal] null =
 * nothing changed) and, when the goal was JUST met, the points-ledger reason to
 * reward with. Pure, so every path that credits goals (the orchestrator's push/poll
 * and the legacy reading path) shares one rule and it can be unit-tested.
 */
data class GoalUpdate(val goal: GoalEntity?, val rewardReason: String?) {
    companion object {
        val NONE = GoalUpdate(goal = null, rewardReason = null)
    }
}

/**
 * Add [units] of verified progress, reported at [now] for work done at [occurredAt]
 * (they differ when sync delivers an event late).
 *  - Recurring goals: counts toward the current period only — work from an earlier
 *    period never fills today's; rewarded once per period.
 *  - One-shot goals: ignored once met or inactive, or when the work happened after
 *    the deadline; reaching the target marks the goal MET (inactive) so it can never
 *    be paid twice.
 */
fun GoalEntity.applyProgress(units: Int, now: Long, occurredAt: Long = now): GoalUpdate {
    if (units <= 0 || !active) return GoalUpdate.NONE
    if (recurrence() != Recurrence.Once) {
        if (metThisPeriod() || occurredAt < currentPeriodStart()) return GoalUpdate.NONE
        val (updated, metNow) = addRecurringProgress(units)
        return GoalUpdate(updated, if (metNow) "goal_met:$id:period" else null)
    }
    val open = pledgeStatus() == CommitmentStatus.ACTIVE &&
        progress < target &&
        (deadlineAt ?: Long.MAX_VALUE) >= occurredAt
    if (!open) return GoalUpdate.NONE
    val next = progress + units
    return if (next >= target) {
        GoalUpdate(copy(progress = target).withStatus(CommitmentStatus.MET), "goal_met:$id")
    } else {
        GoalUpdate(copy(progress = next), rewardReason = null)
    }
}

/** A verifier said the whole target is satisfied — same guarantees as [applyProgress]. */
fun GoalEntity.applyPassed(now: Long, occurredAt: Long = now): GoalUpdate {
    val remaining = if (recurrence() != Recurrence.Once) target else target - progress
    return applyProgress(remaining.coerceAtLeast(1), now, occurredAt)
}

/**
 * Repair for rows left by the pre-fix orchestrator: a one-shot that reached its
 * target but stayed ACTIVE. Returns it closed as MET (it was already paid, so no
 * reward), or null if the goal needs no repair. Without this, overdue settlement
 * would mark a finished goal MISSED and penalize it.
 */
fun GoalEntity.normalizedIfMet(): GoalEntity? {
    val stale = recurrence() == Recurrence.Once && active && progress >= target &&
        pledgeStatus() == CommitmentStatus.ACTIVE
    return if (stale) withStatus(CommitmentStatus.MET) else null
}

/** Start of the period that [periodEndAt] closes; MIN_VALUE when unknown. */
private fun GoalEntity.currentPeriodStart(zone: ZoneId = ZoneId.systemDefault()): Long {
    val end = periodEndAt()
    if (end <= 0) return Long.MIN_VALUE
    val lastDay = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
    return RecurrenceSchedule.periodOn(recurrence(), lastDay, zone)?.startAt ?: Long.MIN_VALUE
}

/** Whether an event at [occurredAt] may count toward this goal (it can't predate the goal). */
fun GoalEntity.existedAt(occurredAt: Long): Boolean = createdAt <= occurredAt
