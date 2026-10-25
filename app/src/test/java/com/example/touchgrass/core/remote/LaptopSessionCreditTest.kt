package com.example.touchgrass.core.remote

import com.example.touchgrass.features.focus.insights.SessionSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopSessionCreditTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun credit(
        source: SessionSource = SessionSource.LAPTOP,
        outcome: String = "COMPLETED",
        endedAt: Long = now - hour,
        paired: Boolean = false
    ) = LaptopSessionCredit.shouldCredit(source, outcome, endedAt, now, paired)

    @Test
    fun `a recent completed laptop-only session counts toward focus goals`() {
        assertTrue(credit())
    }

    @Test
    fun `an ended-early laptop session does not count`() {
        assertFalse(credit(outcome = "ENDED_EARLY"))
    }

    @Test
    fun `a laptop session paired with a phone session is not counted twice`() {
        assertFalse(credit(paired = true))
    }

    @Test
    fun `old history pulled at sign-in is not credited to today's goals`() {
        assertFalse(credit(endedAt = now - 3 * 24 * hour))
    }

    @Test
    fun `phone sessions are credited by the phone itself, not by sync`() {
        assertFalse(credit(source = SessionSource.PHONE))
    }

    @Test
    fun `small clock skew into the future is tolerated`() {
        assertTrue(credit(endedAt = now + 60_000L))
    }
}
