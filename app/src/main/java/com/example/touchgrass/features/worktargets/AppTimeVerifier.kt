package com.example.touchgrass.features.worktargets

import com.example.touchgrass.core.data.db.GoalEntity
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.goals.GoalTypeKey
import com.example.touchgrass.core.goals.GoalVerifier
import com.example.touchgrass.core.goals.VerificationCadence
import com.example.touchgrass.core.goals.VerificationResult
import com.example.touchgrass.core.goals.metThisPeriod
import com.example.touchgrass.core.goals.periodEndAt
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Weekly app-time quotas (e.g. "5h in VS Code on TouchGrass"). The goal's progress
 * is minutes this week; each poll reports the minutes the laptop agent logged since
 * the last check. Measures the GOAL's week, so a final check after Sunday still
 * credits the week that just closed.
 */
class AppTimeVerifier @Inject constructor(
    private val repo: WorkTargetsRepository
) : GoalVerifier {
    override val type = GoalType.APP_TIME
    override val cadence = VerificationCadence.Polled(POLL_INTERVAL_MIN)

    override suspend fun verify(goal: GoalEntity): VerificationResult {
        if (goal.metThisPeriod()) return VerificationResult.NoChange
        val targetId = JSONObject(goal.configJson).optString(CONFIG_TARGET_ID)
        val target = repo.currentTargets().firstOrNull { it.id == targetId }
            ?: return VerificationResult.NoChange // deleted target: its goal gets retired by the plan
        val minutes = repo.minutesInWeek(target, weekOf(goal))
            ?: throw IOException("app usage unavailable") // offline — let the caller retry
        val delta = minutes - goal.progress
        return if (delta > 0) VerificationResult.Progress(delta) else VerificationResult.NoChange
    }

    /** Monday of the goal's current period (or of this week if the period is unknown). */
    private fun weekOf(goal: GoalEntity): LocalDate {
        val zone = ZoneId.systemDefault()
        val end = goal.periodEndAt()
        val day = if (end > 0) Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate() else LocalDate.now(zone)
        return WorkTargetUsage.weekStart(day)
    }

    companion object {
        const val CONFIG_TARGET_ID = "workTargetId"
        const val POLL_INTERVAL_MIN = 180L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppTimeVerifierModule {
    @Binds
    @IntoMap
    @GoalTypeKey(GoalType.APP_TIME)
    abstract fun bindAppTimeVerifier(impl: AppTimeVerifier): GoalVerifier
}
