package com.example.touchgrass.core.remote

import com.example.touchgrass.features.focus.insights.SessionSource

/**
 * Whether a focus session pulled from the laptop should advance the phone's
 * FOCUS_SESSION goals. Phone sessions credit themselves when they settle, so sync
 * only credits laptop ones — and only when they're not the laptop half of a
 * phone-synced session (already credited), finished properly, and are recent
 * (so history pulled at sign-in doesn't flood today's goals).
 */
internal object LaptopSessionCredit {
    const val RECENT_WINDOW_MS: Long = 24 * 60 * 60_000L

    fun shouldCredit(
        source: SessionSource,
        outcome: String,
        endedAt: Long,
        now: Long,
        pairedWithPhone: Boolean
    ): Boolean =
        source == SessionSource.LAPTOP &&
            outcome == "COMPLETED" &&
            !pairedWithPhone &&
            endedAt >= now - RECENT_WINDOW_MS
}
