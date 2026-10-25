package com.example.touchgrass.core.discipline

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Whether the plan is locked and the next relevant local-week boundary. */
data class LockWindow(val locked: Boolean, val lockedUntil: Instant?, val nextLockAt: Instant?)

/** A paid request to relax one commitment after a cooling-off period. */
data class UnlockRequest(val field: PlanField, val requestedAtMs: Long)

/** Pure rules for weekday commitments and delayed paid unlocks. */
object WeeklyLock {
    const val UNLOCK_COST_POINTS: Int = 200
    const val UNLOCK_COOLDOWN_MS: Long = 24 * 60 * 60_000L

    /** Returns fields that reduce an existing commitment. */
    fun looseningFields(locked: WeeklyPlan, proposed: WeeklyPlan): Set<PlanField> {
        val fields = mutableSetOf<PlanField>()
        if (locked.forceReadEnabled && !proposed.forceReadEnabled) {
            fields.add(PlanField.FORCE_READ_ENABLED)
        }
        if (locked.forceReadEnabled && proposed.forceReadEnabled &&
            proposed.forceReadEveryMin > locked.forceReadEveryMin
        ) {
            fields.add(PlanField.FORCE_READ_INTERVAL)
        }
        if (proposed.shortsDailyLimit > locked.shortsDailyLimit) fields.add(PlanField.SHORTS_LIMIT)
        if (!proposed.blockedPackages.containsAll(locked.blockedPackages)) {
            fields.add(PlanField.BLOCKED_APPS)
        }
        if (proposed.weeklyReadingPages < locked.weeklyReadingPages) fields.add(PlanField.WEEKLY_PAGES)
        if (locked.accessibilityPenaltyEnabled && !proposed.accessibilityPenaltyEnabled) {
            fields.add(PlanField.ACCESSIBILITY_PENALTY)
        }
        if (locked.focusQuotaMinutes.any { (key, minutes) ->
                val proposedMinutes = proposed.focusQuotaMinutes[key]
                proposedMinutes == null || proposedMinutes < minutes
            }
        ) {
            fields.add(PlanField.FOCUS_QUOTAS)
        }
        return fields.toSet()
    }

    /** Uses local start-of-day boundaries so daylight-saving transitions are respected. */
    fun lockWindow(now: Instant, zone: ZoneId): LockWindow {
        val date = now.atZone(zone).toLocalDate()
        val locked = date.dayOfWeek.value < DayOfWeek.SATURDAY.value
        return if (locked) {
            val saturday = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            LockWindow(true, saturday.atStartOfDay(zone).toInstant(), null)
        } else {
            val monday = date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            LockWindow(false, null, monday.atStartOfDay(zone).toInstant())
        }
    }

    /** Saturates at the last representable timestamp instead of overflowing. */
    fun unlockReadyAt(request: UnlockRequest): Long =
        if (request.requestedAtMs > Long.MAX_VALUE - UNLOCK_COOLDOWN_MS) {
            Long.MAX_VALUE
        } else {
            request.requestedAtMs + UNLOCK_COOLDOWN_MS
        }

    fun isUnlockReady(request: UnlockRequest, nowMs: Long): Boolean =
        nowMs >= unlockReadyAt(request)

    /** Keeps locked fields unless the proposal tightens them or their unlock is ready. */
    fun applyChange(
        locked: WeeklyPlan,
        proposed: WeeklyPlan,
        window: LockWindow,
        readyUnlocks: Set<PlanField>
    ): WeeklyPlan {
        if (!window.locked) return proposed
        val loosening = looseningFields(locked, proposed)
        fun allowed(field: PlanField): Boolean = field !in loosening || field in readyUnlocks

        val forceReadEnabled = if (allowed(PlanField.FORCE_READ_ENABLED)) {
            proposed.forceReadEnabled
        } else {
            locked.forceReadEnabled
        }
        val intervalLoosens = PlanField.FORCE_READ_INTERVAL in looseningFields(
            locked,
            proposed.copy(forceReadEnabled = forceReadEnabled)
        )
        return WeeklyPlan(
            forceReadEnabled = forceReadEnabled,
            forceReadEveryMin = if (!intervalLoosens || PlanField.FORCE_READ_INTERVAL in readyUnlocks) {
                proposed.forceReadEveryMin
            } else {
                locked.forceReadEveryMin
            },
            shortsDailyLimit = if (allowed(PlanField.SHORTS_LIMIT)) proposed.shortsDailyLimit else locked.shortsDailyLimit,
            blockedPackages = if (allowed(PlanField.BLOCKED_APPS)) proposed.blockedPackages else locked.blockedPackages,
            weeklyReadingPages = if (allowed(PlanField.WEEKLY_PAGES)) proposed.weeklyReadingPages else locked.weeklyReadingPages,
            accessibilityPenaltyEnabled = if (allowed(PlanField.ACCESSIBILITY_PENALTY)) {
                proposed.accessibilityPenaltyEnabled
            } else {
                locked.accessibilityPenaltyEnabled
            },
            focusQuotaMinutes = if (allowed(PlanField.FOCUS_QUOTAS)) proposed.focusQuotaMinutes else locked.focusQuotaMinutes
        )
    }
}
