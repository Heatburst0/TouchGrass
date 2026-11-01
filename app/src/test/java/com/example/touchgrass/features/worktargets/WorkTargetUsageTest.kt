package com.example.touchgrass.features.worktargets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkTargetUsageTest {

    private val monday = LocalDate.of(2026, 10, 26)
    private val vsTouchGrass = WorkTarget("t1", "TouchGrass", "code", "TouchGrass")
    private val anyIntellij = WorkTarget("t2", "IntelliJ", "idea64", null)

    private fun row(day: LocalDate, app: String, project: String, secs: Int) =
        AppUsageRow(day, app, project, secs)

    @Test
    fun `project target matches app substring and project ignoring case`() {
        assertTrue(WorkTargetUsage.matches(vsTouchGrass, "code", "touchgrass"))
        assertTrue(WorkTargetUsage.matches(vsTouchGrass, "code.exe", " TouchGrass "))
        assertFalse(WorkTargetUsage.matches(vsTouchGrass, "code", "portfolio"))
        assertFalse(WorkTargetUsage.matches(vsTouchGrass, "code", ""))
        assertFalse(WorkTargetUsage.matches(vsTouchGrass, "idea64", "TouchGrass"))
    }

    @Test
    fun `any-project target matches every project including unknown`() {
        assertTrue(WorkTargetUsage.matches(anyIntellij, "idea64", "leetcode"))
        assertTrue(WorkTargetUsage.matches(anyIntellij, "idea64", ""))
    }

    @Test
    fun `blank app never matches`() {
        assertFalse(WorkTargetUsage.matches(WorkTarget("x", "x", "", null), "code", ""))
    }

    @Test
    fun `week starts on monday`() {
        assertEquals(monday, WorkTargetUsage.weekStart(monday))
        assertEquals(monday, WorkTargetUsage.weekStart(monday.plusDays(6)))
        assertEquals(monday.plusDays(7), WorkTargetUsage.weekStart(monday.plusDays(7)))
    }

    @Test
    fun `minutes sum matching rows inside the week only, across laptops`() {
        val rows = listOf(
            row(monday, "code", "TouchGrass", 1200),             // 20 min
            row(monday.plusDays(3), "code", "touchgrass", 1500), // 25 min (2nd laptop / case)
            row(monday.plusDays(6), "code", "TouchGrass", 59),   // under a minute
            row(monday.minusDays(1), "code", "TouchGrass", 6000),// last week
            row(monday.plusDays(7), "code", "TouchGrass", 6000), // next week
            row(monday, "code", "portfolio", 6000)               // other project
        )
        assertEquals(45, WorkTargetUsage.minutesInWeek(vsTouchGrass, rows, monday))
    }

    @Test
    fun `recent projects are ranked by time and filtered by app`() {
        val rows = listOf(
            row(monday, "code", "portfolio", 100),
            row(monday, "code", "TouchGrass", 900),
            row(monday.plusDays(1), "code", "portfolio", 100),
            row(monday, "idea64", "leetcode", 5000),
            row(monday, "code", "", 9999)
        )
        assertEquals(listOf("TouchGrass", "portfolio"), WorkTargetUsage.recentProjects(rows, "code"))
        assertEquals(listOf("leetcode", "TouchGrass", "portfolio"), WorkTargetUsage.recentProjects(rows, null))
    }

    @Test
    fun `formats minutes`() {
        assertEquals("0m", WorkTargetUsage.formatMinutes(0))
        assertEquals("45m", WorkTargetUsage.formatMinutes(45))
        assertEquals("5h", WorkTargetUsage.formatMinutes(300))
        assertEquals("3h 20m", WorkTargetUsage.formatMinutes(200))
    }
}
