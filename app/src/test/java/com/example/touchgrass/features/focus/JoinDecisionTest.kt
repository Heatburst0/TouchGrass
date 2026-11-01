package com.example.touchgrass.features.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinDecisionTest {

    private val me = "phone-1"
    private val laptop = "laptop-1"
    private val start = 1_000_000_000_000L
    private val minute = 60_000L

    private fun live(
        active: Boolean = true,
        origin: String? = laptop,
        blockPhone: Boolean = true,
        startMs: Long? = start,
        focusMin: Int = 50
    ) = LiveSession(active, startMs, origin, blockPhone, focusMin = focusMin, breakMin = 0, cycles = 1, label = "TouchGrass")

    private fun decide(
        live: LiveSession?,
        now: Long = start + minute,
        joined: Long? = null,
        localStart: Long? = null,
        remainingMs: Long = 0
    ) = JoinDecision.decide(live, me, joined, localStart, remainingMs, now)

    @Test
    fun `joins an active laptop session that asked to block the phone`() {
        val session = live()
        assertEquals(JoinDecision.Action.Join(session), decide(session))
    }

    @Test
    fun `ignores sessions that don't block the phone, our own, inactive or unstarted ones`() {
        assertEquals(JoinDecision.Action.None, decide(live(blockPhone = false)))
        assertEquals(JoinDecision.Action.None, decide(live(origin = me)))
        assertEquals(JoinDecision.Action.None, decide(live(active = false)))
        assertEquals(JoinDecision.Action.None, decide(live(startMs = null)))
        assertEquals(JoinDecision.Action.None, decide(null))
    }

    @Test
    fun `joins late into the remaining time but not an expired or nearly finished session`() {
        assertEquals(JoinDecision.Action.Join(live()), decide(live(), now = start + 40 * minute))
        assertEquals(JoinDecision.Action.None, decide(live(), now = start + 49 * minute))
        assertEquals(JoinDecision.Action.None, decide(live(), now = start + 5 * 60 * minute)) // stale row
    }

    @Test
    fun `never joins over a session already running on the phone`() {
        assertEquals(JoinDecision.Action.None, decide(live(), localStart = start - 5 * minute, remainingMs = 600_000))
    }

    @Test
    fun `does not rejoin the same session after it was handled`() {
        assertEquals(JoinDecision.Action.None, decide(live(), joined = start))
    }

    @Test
    fun `joins a newer session after an older one was handled`() {
        val newer = live(startMs = start + 120 * minute)
        assertEquals(JoinDecision.Action.Join(newer), decide(newer, now = start + 121 * minute, joined = start))
    }

    @Test
    fun `ends the joined session when the laptop ends it early`() {
        val running = 20 * minute
        assertEquals(
            JoinDecision.Action.EndEarly,
            decide(live(active = false), joined = start, localStart = start, remainingMs = running)
        )
        assertEquals(
            JoinDecision.Action.EndEarly,
            decide(null, joined = start, localStart = start, remainingMs = running)
        )
    }

    @Test
    fun `lets the joined session finish naturally near its end`() {
        assertEquals(
            JoinDecision.Action.None,
            decide(live(active = false), joined = start, localStart = start, remainingMs = 90_000)
        )
    }

    @Test
    fun `never ends a session the phone started itself, even after an earlier join`() {
        val own = start + 200 * minute
        assertEquals(
            JoinDecision.Action.None,
            decide(live(active = false), joined = null, localStart = own, remainingMs = 20 * minute)
        )
        // A finished join leaves its marker; a later phone-owned session must not be ended by it.
        assertEquals(
            JoinDecision.Action.None,
            decide(live(origin = me, startMs = own), joined = start, localStart = own, remainingMs = 20 * minute)
        )
    }
}
