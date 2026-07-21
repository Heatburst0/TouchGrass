package com.example.touchgrass.core.goals

/**
 * A productivity pillar. Each pillar's verifier reports progress to the
 * [GoalEngine]; adding a new pillar is "call recordProgress(pillar, n) when
 * your verification passes" — no engine changes needed.
 */
enum class PillarType(val display: String, val unit: String) {
    READING("Reading", "pages"),
    FOCUS("Focus", "sessions"),
    GYM("Gym", "workouts"),
    LEARNING("Learning", "lessons");

    companion object {
        fun fromName(name: String): PillarType =
            entries.firstOrNull { it.name == name } ?: READING
    }
}

enum class CommitmentStatus {
    ACTIVE,   // in progress, before deadline
    MET,      // target reached -> reward paid
    MISSED    // deadline passed unmet -> penalty applied
}
