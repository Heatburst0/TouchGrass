package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.GoalEntity

/** One strategy per GoalType. Phase 0 ships zero implementations — they arrive
 *  as each feature is ported (GitHub → Reading → Shorts). */
interface GoalVerifier {
    val type: GoalType
    val cadence: VerificationCadence
    suspend fun verify(goal: GoalEntity): VerificationResult
}
