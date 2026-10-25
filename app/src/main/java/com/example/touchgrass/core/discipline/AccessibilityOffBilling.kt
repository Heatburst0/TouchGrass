package com.example.touchgrass.core.discipline

/** Billing state of one accessibility-off spell. */
data class OffState(
    val bootCount: Int,
    val sinceElapsedMs: Long,
    val billedTicks: Int,
    val confirmedOff: Boolean
)

/** Newly due ticks and the state to persist after billing. */
data class Bill(val newTicks: Int, val state: OffState) {
    val points: Int
        get() = newTicks * AccessibilityOffBilling.POINTS_PER_TICK
}

/** Bills confirmed off-time using only the current boot's elapsed clock. */
object AccessibilityOffBilling {
    const val GRACE_MS: Long = 10 * 60_000L
    const val TICK_MS: Long = 30 * 60_000L
    const val POINTS_PER_TICK: Int = 10

    fun start(bootCount: Int, nowElapsedMs: Long): OffState =
        OffState(bootCount, nowElapsedMs, billedTicks = 0, confirmedOff = false)

    fun confirmOff(state: OffState): OffState = state.copy(confirmedOff = true)

    /** Rebases on reboot, then charges each started tick beyond the grace period. */
    fun bill(state: OffState, bootCount: Int, nowElapsedMs: Long): Bill {
        val current = if (bootCount == state.bootCount) {
            state
        } else {
            state.copy(bootCount = bootCount, sinceElapsedMs = 0, billedTicks = 0)
        }
        if (!current.confirmedOff) return Bill(0, current)

        val offTime = elapsedSince(nowElapsedMs, current.sinceElapsedMs)
        val chargeable = (offTime - GRACE_MS).coerceAtLeast(0)
        val due = if (chargeable == 0L) {
            0
        } else {
            (1 + (chargeable - 1) / TICK_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val billedTicks = maxOf(current.billedTicks, due)
        return Bill(
            newTicks = (due.toLong() - current.billedTicks).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            state = current.copy(billedTicks = billedTicks)
        )
    }

    private fun elapsedSince(now: Long, since: Long): Long {
        if (now <= since) return 0
        val elapsed = now - since
        return if (elapsed < 0) Long.MAX_VALUE else elapsed
    }
}
