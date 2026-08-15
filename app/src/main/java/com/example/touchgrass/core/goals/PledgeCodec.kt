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
    JSONObject(configJson).optString("category", PillarType.READING.name)

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
    deadlineAt = deadlineAt ?: 0L,
    rewardPoints = rewardPoints,
    penaltyShorts = penaltyShorts,
    status = pledgeStatus().name
)

fun GoalEntity.withStatus(status: CommitmentStatus): GoalEntity = copy(
    stateJson = JSONObject(stateJson).put("status", status.name).toString(),
    active = status == CommitmentStatus.ACTIVE
)

fun newPledgeGoal(
    pillar: PillarType,
    title: String,
    target: Int,
    deadlineAt: Long,
    reward: Int,
    penalty: Int,
    now: Long
): GoalEntity = GoalEntity(
    type = GoalType.TASK.name,
    title = title,
    direction = GoalDirection.ACHIEVE.name,
    schedule = GoalSchedule.ONE_SHOT.name,
    target = target,
    unit = pillar.unit,
    progress = 0,
    rewardPoints = reward,
    penaltyShorts = penalty,
    configJson = JSONObject().put("category", pillar.name).toString(),
    stateJson = JSONObject().put("status", CommitmentStatus.ACTIVE.name).toString(),
    active = true,
    createdAt = now,
    deadlineAt = deadlineAt
)

/** One-time migration of a legacy commitments row into a Task goal. */
fun CommitmentEntity.toPledgeGoal(now: Long): GoalEntity = GoalEntity(
    type = GoalType.TASK.name,
    title = title,
    direction = GoalDirection.ACHIEVE.name,
    schedule = GoalSchedule.ONE_SHOT.name,
    target = targetAmount,
    unit = unitLabel,
    progress = progress,
    rewardPoints = rewardPoints,
    penaltyShorts = penaltyShorts,
    configJson = JSONObject().put("category", pillar).toString(),
    stateJson = JSONObject().put("status", status).toString(),
    active = status == CommitmentStatus.ACTIVE.name,
    createdAt = createdAt,
    deadlineAt = deadlineAt
)
