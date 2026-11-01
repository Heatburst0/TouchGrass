package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.GoalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodChangesTest {

    private fun goal(target: Int = 300) = GoalEntity(
        type = GoalType.APP_TIME.name, title = "Weekly quota: TouchGrass", direction = "ACHIEVE",
        schedule = "ONGOING", target = target, unit = "min", progress = 120,
        configJson = """{"workTargetId":"t1"}""", createdAt = 0
    )

    @Test
    fun `a new target waits for the period to close`() {
        val g = goal().scheduleTarget(600)
        assertEquals(300, g.target)
        assertEquals(600, g.pendingTarget())
        val rolled = g.applyScheduledChanges()
        assertEquals(600, rolled?.target)
        assertNull(rolled?.pendingTarget())
    }

    @Test
    fun `scheduling the current target clears a pending change`() {
        val g = goal().scheduleTarget(600).scheduleTarget(300)
        assertNull(g.pendingTarget())
        assertEquals(300, g.applyScheduledChanges()?.target)
    }

    @Test
    fun `a retiring goal stays until the period closes, then retires`() {
        val g = goal().scheduleRetire()
        assertTrue(g.retiring())
        assertNull(g.applyScheduledChanges())
    }

    @Test
    fun `restoring before the period closes cancels the retirement`() {
        val g = goal().scheduleRetire().cancelRetire()
        assertFalse(g.retiring())
        assertEquals(300, g.applyScheduledChanges()?.target)
    }

    @Test
    fun `other config survives scheduled changes`() {
        val rolled = goal().scheduleTarget(450).applyScheduledChanges()
        assertTrue(rolled?.configJson?.contains("\"workTargetId\":\"t1\"") == true)
    }

    @Test
    fun `goals without scheduled changes roll unchanged`() {
        val g = goal()
        assertEquals(g, g.applyScheduledChanges())
    }
}
