package com.example.touchgrass.features.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusModelTest {

    private val start = 1_800_000_000_000L
    private val minute = 60_000L
    private val config = FocusConfig(
        focusBlockMin = 25, breakMin = 5, cycles = 4,
        blockedPackages = setOf("com.instagram.android"), strict = true
    )
    private val session = ActiveFocus(start, config)

    @Test
    fun `totals count breaks only between cycles`() {
        assertEquals(115, config.totalMinutes)
        assertEquals(100, config.focusMinutes)
        assertEquals(start + 115 * minute, session.endAt)
    }

    @Test
    fun `break is capped at the focus block length`() {
        assertEquals(25, FocusConfig.capBreak(25, 40))
        assertEquals(0, FocusConfig.capBreak(25, -5))
        assertEquals(5, FocusConfig.capBreak(25, 5))
    }

    @Test
    fun `no session is idle`() {
        assertEquals(FocusPhase.Idle, focusPhaseAt(null, start))
    }

    @Test
    fun `at the start you are focusing in cycle one`() {
        val phase = focusPhaseAt(session, start) as FocusPhase.Focusing

        assertEquals(1, phase.cycle)
        assertEquals(25 * 60L, phase.blockRemainingSec)
        assertEquals(115 * 60L, phase.sessionRemainingSec)
    }

    @Test
    fun `a clock before the start counts as the start`() {
        assertTrue(focusPhaseAt(session, start - 10 * minute) is FocusPhase.Focusing)
    }

    @Test
    fun `after the first block comes a break, then cycle two`() {
        val onBreak = focusPhaseAt(session, start + 25 * minute) as FocusPhase.OnBreak
        val second = focusPhaseAt(session, start + 30 * minute) as FocusPhase.Focusing

        assertEquals(1, onBreak.cycle)
        assertEquals(5 * 60L, onBreak.breakRemainingSec)
        assertEquals(2, second.cycle)
    }

    @Test
    fun `the last block has no trailing break`() {
        // Cycle 4 runs 90..115 minutes; at 115 the session is done.
        assertTrue(focusPhaseAt(session, start + 114 * minute) is FocusPhase.Focusing)
        assertEquals(FocusPhase.Done, focusPhaseAt(session, start + 115 * minute))
    }

    @Test
    fun `focused minutes exclude breaks`() {
        assertEquals(0, focusedMinutesAt(session, start))
        assertEquals(25, focusedMinutesAt(session, start + 27 * minute)) // in the first break
        assertEquals(35, focusedMinutesAt(session, start + 40 * minute)) // 10 min into cycle 2
        assertEquals(100, focusedMinutesAt(session, start + 200 * minute))
    }

    @Test
    fun `active session survives a JSON round trip`() {
        assertEquals(session, activeFocusFromJson(session.toJson()))
    }

    @Test
    fun `blank or corrupt JSON is no session`() {
        assertNull(activeFocusFromJson(null))
        assertNull(activeFocusFromJson(""))
        assertNull(activeFocusFromJson("{not json"))
        assertNull(activeFocusFromJson("""{"startAt":1}"""))
    }
}
