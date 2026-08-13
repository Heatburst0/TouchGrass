package com.example.touchgrass.core.goals

/** Every trackable goal is one of these. Adding a type = new entry + a
 *  GoalVerifier bound into the registry. No engine/UI-hub changes. */
enum class GoalType { SHORTS_LIMIT, GITHUB_COMMIT, READING, FOCUS_SESSION }

/** ACHIEVE = do more → EARN points. LIMIT = stay under a cap or the stake is
 *  docked. Decides how a VerificationResult maps to the economy. */
enum class GoalDirection { ACHIEVE, LIMIT }

/** Per-GOAL recurrence (data): does it reset, or have a one-off deadline? */
enum class GoalSchedule { ONE_SHOT, DAILY, ONGOING }

/** Per-VERIFIER cadence (behavior): HOW the engine runs the check. This is a
 *  property of the verifier/type, not stored per goal. */
sealed interface VerificationCadence {
    data object Continuous : VerificationCadence               // fed live by the accessibility service
    data class Polled(val intervalMinutes: Long) : VerificationCadence  // WorkManager
    data object OnDemand : VerificationCadence                 // user-triggered (finish a quiz)
}

/** What a verifier reports for one check. */
sealed interface VerificationResult {
    data class Progress(val amount: Int) : VerificationResult  // +n toward target
    data object Passed : VerificationResult
    data object Failed : VerificationResult
    data object NoChange : VerificationResult
}
