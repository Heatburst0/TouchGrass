package com.example.touchgrass.features.github

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.data.db.GoalEntity
import com.example.touchgrass.core.goals.GoalDirection
import com.example.touchgrass.core.goals.GoalSchedule
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.goals.GoalTypeKey
import com.example.touchgrass.core.goals.GoalVerifier
import com.example.touchgrass.core.goals.Recurrence
import com.example.touchgrass.core.goals.RecurrenceSchedule
import com.example.touchgrass.core.goals.VerificationCadence
import com.example.touchgrass.core.goals.VerificationResult
import com.example.touchgrass.core.goals.bestStreak
import com.example.touchgrass.core.goals.currentStreak
import com.example.touchgrass.core.goals.initialRecurringState
import com.example.touchgrass.core.goals.metThisPeriod
import com.example.touchgrass.core.goals.periodEndAt
import com.example.touchgrass.core.goals.recurrence
import com.example.touchgrass.core.goals.withRecurringState
import com.example.touchgrass.core.notifications.NotifChannel
import com.example.touchgrass.core.notifications.Notifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// Codec. A GITHUB_COMMIT goal is a RECURRING goal (Option B): its repo lives in
// configJson (with a recurrence, like a reading goal), and its streak lives in
// the shared recurring state (periodEndAt / metThisPeriod / currentStreak). The
// engine settles it exactly like any other recurring goal.
// ---------------------------------------------------------------------------

/** Immutable config — the repo to watch. Recurrence is read via [recurrence]. */
data class GitHubConfig(val owner: String, val repo: String, val author: String)

/** Legacy (pre-Option-B) state — read only, to reshape old rows into recurring state. */
data class GitHubState(
    val lastSuccessDate: String? = null,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0
)

fun GoalEntity.gitHubConfig(): GitHubConfig {
    val o = JSONObject(configJson)
    return GitHubConfig(o.optString("owner"), o.optString("repo"), o.optString("author"))
}

private fun GoalEntity.legacyGitHubState(): GitHubState {
    val o = JSONObject(stateJson)
    val ls = if (o.has("lastSuccessDate") && !o.isNull("lastSuccessDate")) o.getString("lastSuccessDate") else null
    return GitHubState(ls, o.optInt("currentStreak", 0), o.optInt("bestStreak", 0))
}

private fun githubConfigJson(c: GitHubConfig, recurrence: Recurrence): String =
    JSONObject()
        .put("owner", c.owner).put("repo", c.repo).put("author", c.author)
        .put("recurrence", RecurrenceSchedule.encode(recurrence))
        .toString()

/** GoalEntity → the DTO the existing GitHub card renders. */
fun GoalEntity.toGitHubGoalEntity(): GitHubGoalEntity {
    val c = gitHubConfig()
    val today = LocalDate.now().toString()
    return GitHubGoalEntity(
        id = id, owner = c.owner, repo = c.repo, author = c.author,
        createdDate = today,
        lastSuccessDate = if (metThisPeriod()) today else null,
        lastSettledDate = null,
        currentStreak = currentStreak(),
        bestStreak = bestStreak(),
        rewardPoints = rewardPoints,
        penaltyShorts = penaltyShorts,
        active = active
    )
}

/** Build a fresh recurring GITHUB_COMMIT goal (default Daily; user can pick Weekly/Custom). */
fun newGitHubGoal(
    owner: String,
    repo: String,
    author: String,
    reward: Int,
    penalty: Int,
    now: Long,
    recurrence: Recurrence = Recurrence.Daily
): GoalEntity = GoalEntity(
    type = GoalType.GITHUB_COMMIT.name,
    title = "${owner.trim()}/${repo.trim()}",
    direction = GoalDirection.ACHIEVE.name,
    schedule = GoalSchedule.ONGOING.name,
    target = 1,
    unit = "commit",
    progress = 0,
    rewardPoints = reward,
    penaltyShorts = penalty,
    configJson = githubConfigJson(GitHubConfig(owner.trim(), repo.trim(), author.trim()), recurrence),
    stateJson = initialRecurringState(recurrence),
    active = true,
    createdAt = now,
    deadlineAt = null
)

/** Legacy github_goals row → a recurring (Daily) goal, preserving streak. */
fun GitHubGoalEntity.toGoalEntity(now: Long): GoalEntity =
    newGitHubGoal(owner, repo, author, rewardPoints, penaltyShorts, now, Recurrence.Daily)
        .copy(active = active)
        .withRecurringState(
            streak = currentStreak,
            best = bestStreak,
            met = lastSuccessDate == LocalDate.now().toString()
        )

/** Reshape an old-shape GITHUB_COMMIT row (already in the goals table) into the
 *  recurring model, preserving id/active/streak. Used by the one-time migration. */
fun GoalEntity.reshapeGitHubToRecurring(): GoalEntity {
    val c = gitHubConfig()
    val s = legacyGitHubState()
    return newGitHubGoal(c.owner, c.repo, c.author, rewardPoints, penaltyShorts, createdAt, Recurrence.Daily)
        .copy(id = id, active = active)
        .withRecurringState(
            streak = s.currentStreak,
            best = s.bestStreak,
            met = s.lastSuccessDate == LocalDate.now().toString()
        )
}

// ---------------------------------------------------------------------------
// The verifier is now PURE: it only answers "was there a commit in the CURRENT
// period?". Streaks, rewards and penalties are owned by the shared recurring
// engine (GoalOrchestrator.applyResult + GoalEngine.settleRecurring). A network
// error propagates so the poll aborts and retries — we never punish for our own
// failure to check.
// ---------------------------------------------------------------------------

@Singleton
class GitHubVerifier @Inject constructor(
    private val api: GitHubApi,
    private val settings: SettingsRepository,
    private val notifier: Notifier
) : GoalVerifier {

    override val type = GoalType.GITHUB_COMMIT
    override val cadence = VerificationCadence.Polled(POLL_INTERVAL_MIN)

    override suspend fun verify(goal: GoalEntity): VerificationResult {
        if (goal.metThisPeriod()) return VerificationResult.NoChange   // already counted this period
        // Period already ended → wait for settleRecurring to roll it, so a NEW-period
        // commit is never mis-credited to the old period.
        val ended = goal.periodEndAt()
        if (ended in 1..System.currentTimeMillis()) return VerificationResult.NoChange
        val zone = ZoneId.systemDefault()
        val period = RecurrenceSchedule.periodOn(goal.recurrence(), LocalDate.now(zone), zone)
            ?: return VerificationResult.NoChange                      // Custom off-day: nothing due
        val config = goal.gitHubConfig()
        val token = settings.githubToken.first().ifBlank { null }
        val committed = api.hasCommit(
            config.owner, config.repo, config.author.ifBlank { null },
            since = Instant.ofEpochMilli(period.startAt),
            until = Instant.now(),
            token = token
        )
        if (!committed) return VerificationResult.NoChange
        notifier.post(
            channel = NotifChannel.GOALS,
            id = Notifier.Ids.commit(goal.id),
            title = "Commit logged ✅",
            body = "${config.owner}/${config.repo} — counted for this period."
        )
        return VerificationResult.Progress(1)
    }

    companion object {
        const val POLL_INTERVAL_MIN = 180L   // matches the maintenance worker's 3-hour cadence
    }
}

/** Registers the GitHub verifier in the goal-type → verifier map. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GitHubVerifierModule {
    @Binds
    @IntoMap
    @GoalTypeKey(GoalType.GITHUB_COMMIT)
    abstract fun bindGitHubVerifier(impl: GitHubVerifier): GoalVerifier
}
