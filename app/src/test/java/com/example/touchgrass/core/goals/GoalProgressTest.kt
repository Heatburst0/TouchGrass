package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.GoalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class GoalProgressTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun oneShot(progress: Int = 0, target: Int = 10, deadline: Long = now + day): GoalEntity =
        newPledgeGoal(
            goalType = GoalType.READING, category = "Reading", unit = "pages", title = "Read",
            target = target, deadlineAt = deadline, reward = 50, penalty = 5, now = now - day
        ).copy(id = 7, progress = progress)

    private fun daily(target: Int = 2): GoalEntity =
        newPledgeGoal(
            goalType = GoalType.READING, category = "Reading", unit = "pages", title = "Daily read",
            target = target, deadlineAt = 0, reward = 20, penalty = 3, now = now - day,
            recurrence = Recurrence.Daily, zone = ZoneOffset.UTC
        ).copy(id = 9)

    @Test
    fun `one-shot progress below target just adds`() {
        val update = oneShot(progress = 2).applyProgress(3, now)

        assertEquals(5, update.goal?.progress)
        assertTrue(update.goal?.active == true)
        assertNull(update.rewardReason)
    }

    @Test
    fun `one-shot reaching target is marked MET, deactivated and rewarded`() {
        val update = oneShot(progress = 8).applyProgress(3, now)
        val met = checkNotNull(update.goal)

        assertEquals(10, met.progress)
        assertEquals(CommitmentStatus.MET, met.pledgeStatus())
        assertFalse(met.active)
        assertEquals("goal_met:7", update.rewardReason)
    }

    @Test
    fun `progress on an already-met one-shot changes nothing and pays nothing`() {
        val met = checkNotNull(oneShot(progress = 8).applyProgress(3, now).goal)

        val again = met.applyProgress(5, now)

        assertNull(again.goal)
        assertNull(again.rewardReason)
    }

    @Test
    fun `a one-shot at target but still flagged active is not paid again`() {
        // The pre-fix orchestrator left met goals active with progress == target.
        val stale = oneShot(progress = 10)

        assertEquals(GoalUpdate.NONE, stale.applyProgress(1, now))
    }

    @Test
    fun `passed twice rewards a one-shot only once`() {
        val first = oneShot().applyPassed(now)
        val second = checkNotNull(first.goal).applyPassed(now)

        assertEquals("goal_met:7", first.rewardReason)
        assertEquals(GoalUpdate.NONE, second)
    }

    @Test
    fun `expired one-shot ignores progress`() {
        assertEquals(GoalUpdate.NONE, oneShot(deadline = now - 1).applyProgress(5, now))
    }

    @Test
    fun `inactive goal ignores progress`() {
        assertEquals(GoalUpdate.NONE, oneShot().copy(active = false).applyProgress(5, now))
    }

    @Test
    fun `non-positive units are ignored`() {
        assertEquals(GoalUpdate.NONE, oneShot().applyProgress(0, now))
        assertEquals(GoalUpdate.NONE, oneShot().applyProgress(-3, now))
    }

    @Test
    fun `recurring goal is rewarded once per period`() {
        val met = daily(target = 2).applyProgress(2, now)
        val metGoal = checkNotNull(met.goal)

        assertEquals("goal_met:9:period", met.rewardReason)
        assertTrue(metGoal.metThisPeriod())
        assertTrue(metGoal.active)
        assertEquals(GoalUpdate.NONE, metGoal.applyProgress(1, now))
    }

    @Test
    fun `recurring progress below target accumulates without reward`() {
        val update = daily(target = 3).applyProgress(1, now)

        assertEquals(1, update.goal?.progress)
        assertNull(update.rewardReason)
    }

    @Test
    fun `recurring passed meets the period`() {
        val update = daily(target = 4).applyPassed(now)

        assertEquals(4, update.goal?.progress)
        assertEquals("goal_met:9:period", update.rewardReason)
    }

    @Test
    fun `a one-shot finished before its deadline counts even if reported after it`() {
        val goal = oneShot(progress = 8, deadline = now - 1_000)

        val update = goal.applyProgress(2, now = now, occurredAt = now - 5_000)

        assertEquals("goal_met:7", update.rewardReason)
    }

    @Test
    fun `an event from an earlier period does not count toward the current one`() {
        val goal = daily(target = 1) // current period = today (UTC)

        val update = goal.applyProgress(1, now = System.currentTimeMillis(), occurredAt = goal.periodEndAt() - 2 * day)

        assertEquals(GoalUpdate.NONE, update)
    }

    @Test
    fun `a stale met one-shot is normalized to MET without a reward`() {
        val normalized = checkNotNull(oneShot(progress = 10).normalizedIfMet())

        assertEquals(CommitmentStatus.MET, normalized.pledgeStatus())
        assertFalse(normalized.active)
    }

    @Test
    fun `normalization leaves unfinished and recurring goals alone`() {
        assertNull(oneShot(progress = 9).normalizedIfMet())
        assertNull(daily().copy(progress = 2).normalizedIfMet())
    }

    @Test
    fun `goal only accepts events that happened after it was created`() {
        val goal = oneShot()

        assertTrue(goal.existedAt(goal.createdAt))
        assertTrue(goal.existedAt(now))
        assertFalse(goal.existedAt(goal.createdAt - 1))
    }
}
