package com.example.touchgrass.core.discipline

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WeeklyLockTest {
    private val utc = ZoneId.of("UTC")
    private val lockedWindow = LockWindow(true, Instant.parse("2026-10-31T00:00:00Z"), null)
    private val plan = WeeklyPlan(
        forceReadEnabled = true,
        forceReadEveryMin = 60,
        shortsDailyLimit = 20,
        blockedPackages = setOf("video", "social"),
        weeklyReadingPages = 30,
        accessibilityPenaltyEnabled = true,
        focusQuotaMinutes = mapOf("reader" to 60, "study" to 30)
    )

    @Test
    fun `equal and stricter commitments have no loosening fields`() {
        assertEquals(emptySet<PlanField>(), WeeklyLock.looseningFields(plan, plan))
        val stricter = plan.copy(
            forceReadEveryMin = 30,
            shortsDailyLimit = 10,
            blockedPackages = plan.blockedPackages + "games",
            weeklyReadingPages = 60,
            focusQuotaMinutes = mapOf("reader" to 90, "study" to 30, "work" to 60)
        )
        assertEquals(emptySet<PlanField>(), WeeklyLock.looseningFields(plan, stricter))
        assertEquals(stricter, WeeklyLock.applyChange(plan, stricter, lockedWindow, emptySet()))
    }

    @Test
    fun `enabling force read and accessibility penalty is stricter`() {
        val disabled = plan.copy(forceReadEnabled = false, accessibilityPenaltyEnabled = false)
        assertEquals(emptySet<PlanField>(), WeeklyLock.looseningFields(disabled, plan))
        assertEquals(plan, WeeklyLock.applyChange(disabled, plan, lockedWindow, emptySet()))
    }

    @Test
    fun `raising shorts limit loosens only shorts limit`() {
        assertLoosens(PlanField.SHORTS_LIMIT, plan.copy(shortsDailyLimit = 21))
    }

    @Test
    fun `unblocking any app loosens blocked apps even when another app is added`() {
        assertLoosens(PlanField.BLOCKED_APPS, plan.copy(blockedPackages = setOf("video", "games")))
    }

    @Test
    fun `fewer reading pages loosens weekly pages`() {
        assertLoosens(PlanField.WEEKLY_PAGES, plan.copy(weeklyReadingPages = 29))
    }

    @Test
    fun `disabling force read loosens its enabled field`() {
        assertLoosens(PlanField.FORCE_READ_ENABLED, plan.copy(forceReadEnabled = false))
    }

    @Test
    fun `longer interval while force read is enabled loosens interval`() {
        assertLoosens(PlanField.FORCE_READ_INTERVAL, plan.copy(forceReadEveryMin = 61))
    }

    @Test
    fun `interval changes while force read is disabled are not loosening`() {
        val disabled = plan.copy(forceReadEnabled = false)
        val proposed = disabled.copy(forceReadEveryMin = 120)
        assertEquals(emptySet<PlanField>(), WeeklyLock.looseningFields(disabled, proposed))
        assertEquals(proposed, WeeklyLock.applyChange(disabled, proposed, lockedWindow, emptySet()))
        assertLoosens(
            PlanField.FORCE_READ_ENABLED,
            plan.copy(forceReadEnabled = false, forceReadEveryMin = 120)
        )
    }

    @Test
    fun `enabling force read accepts an interval that was irrelevant while disabled`() {
        val disabled = plan.copy(forceReadEnabled = false)
        val proposed = plan.copy(forceReadEveryMin = 120)
        assertEquals(emptySet<PlanField>(), WeeklyLock.looseningFields(disabled, proposed))
        assertEquals(proposed, WeeklyLock.applyChange(disabled, proposed, lockedWindow, emptySet()))
    }

    @Test
    fun `removing or lowering a quota loosens focus quotas`() {
        assertLoosens(PlanField.FOCUS_QUOTAS, plan.copy(focusQuotaMinutes = mapOf("reader" to 60)))
        assertLoosens(
            PlanField.FOCUS_QUOTAS,
            plan.copy(focusQuotaMinutes = mapOf("reader" to 59, "study" to 30, "work" to 100))
        )
    }

    @Test
    fun `removing even a zero quota loosens focus quotas`() {
        val withZero = plan.copy(focusQuotaMinutes = mapOf("reader" to 0))
        assertEquals(
            setOf(PlanField.FOCUS_QUOTAS),
            WeeklyLock.looseningFields(withZero, withZero.copy(focusQuotaMinutes = emptyMap()))
        )
    }

    @Test
    fun `disabling accessibility penalty loosens penalty field`() {
        assertLoosens(PlanField.ACCESSIBILITY_PENALTY, plan.copy(accessibilityPenaltyEnabled = false))
    }

    @Test
    fun `Wednesday is locked until Saturday midnight`() {
        assertEquals(
            lockedWindow,
            WeeklyLock.lockWindow(Instant.parse("2026-10-28T12:00:00Z"), utc)
        )
    }

    @Test
    fun `Saturday and Sunday are unlocked until Monday midnight`() {
        val expected = LockWindow(false, null, Instant.parse("2026-11-02T00:00:00Z"))
        assertEquals(expected, WeeklyLock.lockWindow(Instant.parse("2026-10-31T00:00:00Z"), utc))
        assertEquals(expected, WeeklyLock.lockWindow(Instant.parse("2026-11-01T23:59:59Z"), utc))
    }

    @Test
    fun `Monday midnight is locked and Friday last minute remains locked`() {
        assertEquals(lockedWindow, WeeklyLock.lockWindow(Instant.parse("2026-10-26T00:00:00Z"), utc))
        assertEquals(lockedWindow, WeeklyLock.lockWindow(Instant.parse("2026-10-30T23:59:00Z"), utc))
    }

    @Test
    fun `Kolkata and UTC have different local week boundaries`() {
        val now = Instant.parse("2026-10-30T20:00:00Z")
        assertTrue(WeeklyLock.lockWindow(now, utc).locked)
        assertEquals(
            LockWindow(false, null, Instant.parse("2026-11-01T18:30:00Z")),
            WeeklyLock.lockWindow(now, ZoneId.of("Asia/Kolkata"))
        )
        assertEquals(
            LockWindow(true, Instant.parse("2026-10-30T18:30:00Z"), null),
            WeeklyLock.lockWindow(Instant.parse("2026-10-28T12:00:00Z"), ZoneId.of("Asia/Kolkata"))
        )
    }

    @Test
    fun `next Monday uses its local midnight offset after a daylight saving transition`() {
        assertEquals(
            LockWindow(false, null, Instant.parse("2026-11-02T05:00:00Z")),
            WeeklyLock.lockWindow(Instant.parse("2026-10-31T16:00:00Z"), ZoneId.of("America/New_York"))
        )
    }

    @Test
    fun `unlock becomes ready at exactly twenty four hours`() {
        val request = UnlockRequest(PlanField.SHORTS_LIMIT, REQUEST_MS)
        assertEquals(REQUEST_MS + 24 * HOUR_MS, WeeklyLock.unlockReadyAt(request))
        assertFalse(WeeklyLock.isUnlockReady(request, REQUEST_MS - 1))
        assertFalse(WeeklyLock.isUnlockReady(request, REQUEST_MS + 24 * HOUR_MS - MINUTE_MS))
        assertTrue(WeeklyLock.isUnlockReady(request, REQUEST_MS + 24 * HOUR_MS))
        assertTrue(WeeklyLock.isUnlockReady(request, REQUEST_MS + 25 * HOUR_MS))
    }

    @Test
    fun `unlocked window saves the proposal as is`() {
        val proposed = looserPlan()
        val window = LockWindow(false, null, Instant.parse("2026-11-02T00:00:00Z"))
        assertSame(proposed, WeeklyLock.applyChange(plan, proposed, window, emptySet()))
    }

    @Test
    fun `locked window applies stricter fields and ignores looser fields`() {
        val proposed = plan.copy(
            forceReadEveryMin = 30,
            shortsDailyLimit = 30,
            blockedPackages = setOf("video"),
            weeklyReadingPages = 50,
            accessibilityPenaltyEnabled = false,
            focusQuotaMinutes = plan.focusQuotaMinutes + ("work" to 90)
        )
        assertEquals(
            plan.copy(
                forceReadEveryMin = 30,
                weeklyReadingPages = 50,
                focusQuotaMinutes = proposed.focusQuotaMinutes
            ),
            WeeklyLock.applyChange(plan, proposed, lockedWindow, emptySet())
        )
        assertEquals(plan, WeeklyLock.applyChange(plan, looserPlan(), lockedWindow, emptySet()))
    }

    @Test
    fun `ready unlock lets exactly the requested field loosen`() {
        val proposed = looserPlan()
        for (field in PlanField.values()) {
            val expected = when (field) {
                PlanField.FORCE_READ_ENABLED -> plan.copy(forceReadEnabled = false)
                PlanField.FORCE_READ_INTERVAL -> plan.copy(forceReadEveryMin = 120)
                PlanField.SHORTS_LIMIT -> plan.copy(shortsDailyLimit = 30)
                PlanField.BLOCKED_APPS -> plan.copy(blockedPackages = emptySet())
                PlanField.WEEKLY_PAGES -> plan.copy(weeklyReadingPages = 0)
                PlanField.ACCESSIBILITY_PENALTY -> plan.copy(accessibilityPenaltyEnabled = false)
                PlanField.FOCUS_QUOTAS -> plan.copy(focusQuotaMinutes = emptyMap())
            }
            // Keep the interval equal when testing the enabled-field unlock in isolation.
            val candidate = if (field == PlanField.FORCE_READ_ENABLED) {
                proposed.copy(forceReadEveryMin = plan.forceReadEveryMin)
            } else {
                proposed
            }
            assertEquals(field.name, expected, WeeklyLock.applyChange(plan, candidate, lockedWindow, setOf(field)))
        }
    }

    @Test
    fun `rejected disable cannot also relax the interval of force read that stays enabled`() {
        val proposed = plan.copy(forceReadEnabled = false, forceReadEveryMin = 120)
        assertEquals(plan, WeeklyLock.applyChange(plan, proposed, lockedWindow, emptySet()))
    }

    private fun assertLoosens(field: PlanField, proposed: WeeklyPlan) {
        assertEquals(setOf(field), WeeklyLock.looseningFields(plan, proposed))
    }

    private fun looserPlan(): WeeklyPlan = WeeklyPlan(
        forceReadEnabled = false,
        forceReadEveryMin = 120,
        shortsDailyLimit = 30,
        blockedPackages = emptySet(),
        weeklyReadingPages = 0,
        accessibilityPenaltyEnabled = false,
        focusQuotaMinutes = emptyMap()
    )

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val REQUEST_MS = 123_000L
    }
}
