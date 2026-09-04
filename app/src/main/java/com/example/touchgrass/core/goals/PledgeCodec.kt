package com.example.touchgrass.core.goals

import com.example.touchgrass.core.data.db.CommitmentEntity
import com.example.touchgrass.core.data.db.GoalEntity
import org.json.JSONObject

/**
 * A pledge is a ONE_SHOT Task goal in the `goals` table. Its category (currently
 * a [PillarType] name; freeform later) lives in configJson; its lifecycle status
 * lives in stateJson, and is mirrored on the `active` column so "is it still
 * open?" is a plain query (active = status == ACTIVE).
 *
 * These map a GoalEntity ↔ the CommitmentEntity DTO the existing Goals UI renders,
 * so porting pledges onto the unified table changed no UI.
 */

fun GoalEntity.pledgeCategory(): String =
    JSONObject(configJson).optString("category", "Reading")

fun GoalEntity.pledgeStatus(): CommitmentStatus =
    runCatching {
        CommitmentStatus.valueOf(
            JSONObject(stateJson).optString("status", CommitmentStatus.ACTIVE.name)
        )
    }.getOrDefault(CommitmentStatus.ACTIVE)

fun GoalEntity.toCommitment(): CommitmentEntity = CommitmentEntity(
    id = id,
    pillar = pledgeCategory(),
    title = title,
    targetAmount = target,
    unitLabel = unit,
    progress = progress,
    createdAt = createdAt,
    deadlineAt = if (recurrence() != Recurrence.Once) periodEndAt() else (deadlineAt ?: 0L),
    rewardPoints = rewardPoints,
    penaltyShorts = penaltyShorts,
    status = pledgeStatus().name
)

fun GoalEntity.withStatus(status: CommitmentStatus): GoalEntity = copy(
    stateJson = JSONObject(stateJson).put("status", status.name).toString(),
    active = status == CommitmentStatus.ACTIVE
)

fun GoalEntity.recurrence(): Recurrence =
    RecurrenceSchedule.decode(JSONObject(configJson).optJSONObject("recurrence"))

fun GoalEntity.currentStreak(): Int = JSONObject(stateJson).optInt("currentStreak", 0)
fun GoalEntity.bestStreak(): Int = JSONObject(stateJson).optInt("bestStreak", 0)
fun GoalEntity.periodEndAt(): Long = JSONObject(stateJson).optLong("periodEndAt", 0L)
fun GoalEntity.metThisPeriod(): Boolean = JSONObject(stateJson).optBoolean("metThisPeriod", false)

fun GoalEntity.withRecurringState(
    streak: Int = currentStreak(),
    best: Int = bestStreak(),
    periodEndAt: Long = periodEndAt(),
    met: Boolean = metThisPeriod()
): GoalEntity = copy(
    stateJson = JSONObject(stateJson)
        .put("currentStreak", streak).put("bestStreak", best)
        .put("periodEndAt", periodEndAt).put("metThisPeriod", met)
        .toString()
)

/**
 * Apply [units] of progress to a recurring goal's CURRENT period. Returns the
 * updated entity and whether the target was JUST met (so the caller rewards once).
 * Idempotent once the period is met. Shared by reading (push) and GitHub (poll).
 */
fun GoalEntity.addRecurringProgress(units: Int): Pair<GoalEntity, Boolean> {
    if (metThisPeriod()) return this to false
    val next = progress + units
    return if (next >= target) {
        // Meeting a period IS the streak — count it now, not at settlement, so a
        // goal done today reads 1 immediately. settleRecurring must not re-count.
        val streak = currentStreak() + 1
        copy(progress = target)
            .withRecurringState(streak = streak, best = maxOf(bestStreak(), streak), met = true) to true
    } else {
        copy(progress = next) to false
    }
}

/** Fresh recurring state JSON (streak 0, first period, not yet met). */
fun initialRecurringState(
    recurrence: Recurrence,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
): String {
    val first = RecurrenceSchedule.periodAtOrAfter(recurrence, java.time.LocalDate.now(zone), zone)
    return JSONObject()
        .put("status", CommitmentStatus.ACTIVE.name)
        .put("currentStreak", 0)
        .put("bestStreak", 0)
        .put("periodEndAt", first?.endAt ?: 0L)
        .put("metThisPeriod", false)
        .toString()
}


/**
 * Build a pledge goal. [goalType] is the verification mechanism (READING pledges
 * are auto-credited by the reading path; a generic TASK pledge is manual/future);
 * [category] is the life-area label shown in the UI, [unit] the counting noun.
 */
fun newPledgeGoal(
    goalType: GoalType,
    category: String,
    unit: String,
    title: String,
    target: Int,
    deadlineAt: Long,
    reward: Int,
    penalty: Int,
    now: Long,
    recurrence: Recurrence = Recurrence.Once,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
): GoalEntity {
    val recurring = recurrence != Recurrence.Once
    val config = JSONObject()
        .put("category", category)
        .put("recurrence", RecurrenceSchedule.encode(recurrence))
    val state = JSONObject().put("status", CommitmentStatus.ACTIVE.name)
    if (recurring) {
        val first = RecurrenceSchedule.periodAtOrAfter(recurrence, java.time.LocalDate.now(zone), zone)
        state.put("currentStreak", 0).put("bestStreak", 0)
            .put("periodEndAt", first?.endAt ?: 0L).put("metThisPeriod", false)
    }
    return GoalEntity(
        type = goalType.name,
        title = title,
        direction = GoalDirection.ACHIEVE.name,
        schedule = if (recurring) GoalSchedule.ONGOING.name else GoalSchedule.ONE_SHOT.name,
        target = target,
        unit = unit,
        progress = 0,
        rewardPoints = reward,
        penaltyShorts = penalty,
        configJson = config.toString(),
        stateJson = state.toString(),
        active = true,
        createdAt = now,
        deadlineAt = if (recurring) null else deadlineAt
    )
}

/**
 * Richer read model for the Goals UI — carries recurrence + streak, which the
 * legacy [CommitmentEntity] DTO can't (it's still a Room entity for the old
 * table). The dashboard keeps using CommitmentEntity for its simple row.
 */
data class GoalView(
    val id: Long,
    val category: String,
    val title: String,
    val target: Int,
    val unit: String,
    val progress: Int,
    val status: CommitmentStatus,
    val recurrence: Recurrence,
    val currentStreak: Int,
    val bestStreak: Int,
    val deadlineAt: Long?,     // ONCE goals
    val periodEndAt: Long,     // recurring goals
    val rewardPoints: Int,
    val penaltyShorts: Int
) {
    val isRecurring: Boolean get() = recurrence != Recurrence.Once
}

fun GoalEntity.toGoalView(): GoalView = GoalView(
    id = id,
    category = pledgeCategory(),
    title = title,
    target = target,
    unit = unit,
    progress = progress,
    status = pledgeStatus(),
    recurrence = recurrence(),
    currentStreak = currentStreak(),
    bestStreak = bestStreak(),
    deadlineAt = deadlineAt,
    periodEndAt = periodEndAt(),
    rewardPoints = rewardPoints,
    penaltyShorts = penaltyShorts
)
