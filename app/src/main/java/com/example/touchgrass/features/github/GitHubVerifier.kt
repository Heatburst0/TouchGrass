package com.example.touchgrass.features.github

import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.data.db.GoalDao
import com.example.touchgrass.core.data.db.GoalEntity
import com.example.touchgrass.core.goals.GoalDirection
import com.example.touchgrass.core.goals.GoalSchedule
import com.example.touchgrass.core.goals.GoalType
import com.example.touchgrass.core.goals.GoalTypeKey
import com.example.touchgrass.core.goals.GoalVerifier
import com.example.touchgrass.core.goals.VerificationCadence
import com.example.touchgrass.core.goals.VerificationResult
import com.example.touchgrass.core.notifications.NotifChannel
import com.example.touchgrass.core.notifications.Notifier
import com.example.touchgrass.core.rewards.RewardsManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// JSON codec: a GITHUB_COMMIT goal stores its immutable "what to track" in
// GoalEntity.configJson and its mutable "how it's going" in stateJson, so a new
// goal type never needs a schema migration.
// ---------------------------------------------------------------------------

/** Immutable config — the repo to watch. */
data class GitHubConfig(val owner: String, val repo: String, val author: String)

/** Mutable runtime — streak + settlement bookkeeping. */
data class GitHubState(
    val createdDate: String,
    val lastSuccessDate: String? = null,
    val lastSettledDate: String? = null,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0
)

fun GoalEntity.gitHubConfig(): GitHubConfig {
    val o = JSONObject(configJson)
    return GitHubConfig(o.optString("owner"), o.optString("repo"), o.optString("author"))
}

fun GoalEntity.gitHubState(): GitHubState {
    val o = JSONObject(stateJson)
    fun nstr(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
    return GitHubState(
        createdDate = o.optString("createdDate", LocalDate.now().toString()),
        lastSuccessDate = nstr("lastSuccessDate"),
        lastSettledDate = nstr("lastSettledDate"),
        currentStreak = o.optInt("currentStreak", 0),
        bestStreak = o.optInt("bestStreak", 0)
    )
}

private fun encodeConfig(c: GitHubConfig): String = JSONObject()
    .put("owner", c.owner).put("repo", c.repo).put("author", c.author).toString()

private fun encodeState(s: GitHubState): String {
    val o = JSONObject().put("createdDate", s.createdDate)
    s.lastSuccessDate?.let { o.put("lastSuccessDate", it) }
    s.lastSettledDate?.let { o.put("lastSettledDate", it) }
    return o.put("currentStreak", s.currentStreak).put("bestStreak", s.bestStreak).toString()
}

/** GoalEntity → the DTO the existing UI renders (keeps consumers unchanged). */
fun GoalEntity.toGitHubGoalEntity(): GitHubGoalEntity {
    val c = gitHubConfig()
    val s = gitHubState()
    return GitHubGoalEntity(
        id = id, owner = c.owner, repo = c.repo, author = c.author,
        createdDate = s.createdDate, lastSuccessDate = s.lastSuccessDate,
        lastSettledDate = s.lastSettledDate, currentStreak = s.currentStreak,
        bestStreak = s.bestStreak, rewardPoints = rewardPoints,
        penaltyShorts = penaltyShorts, active = active
    )
}

/** Build a fresh GITHUB_COMMIT goal row. */
fun newGitHubGoal(owner: String, repo: String, author: String, reward: Int, penalty: Int, now: Long): GoalEntity =
    GoalEntity(
        type = GoalType.GITHUB_COMMIT.name,
        title = "${owner.trim()}/${repo.trim()}",
        direction = GoalDirection.ACHIEVE.name,
        schedule = GoalSchedule.DAILY.name,
        target = 1,
        unit = "commit",
        progress = 0,
        rewardPoints = reward,
        penaltyShorts = penalty,
        configJson = encodeConfig(GitHubConfig(owner.trim(), repo.trim(), author.trim())),
        stateJson = encodeState(GitHubState(createdDate = LocalDate.now().toString())),
        active = true,
        createdAt = now,
        deadlineAt = null
    )

/** Copy a legacy github_goals row into the unified goals table, preserving streak/dates. */
fun GitHubGoalEntity.toGoalEntity(now: Long): GoalEntity =
    GoalEntity(
        type = GoalType.GITHUB_COMMIT.name,
        title = "$owner/$repo",
        direction = GoalDirection.ACHIEVE.name,
        schedule = GoalSchedule.DAILY.name,
        target = 1,
        unit = "commit",
        progress = 0,
        rewardPoints = rewardPoints,
        penaltyShorts = penaltyShorts,
        configJson = encodeConfig(GitHubConfig(owner, repo, author)),
        stateJson = encodeState(
            GitHubState(createdDate, lastSuccessDate, lastSettledDate, currentStreak, bestStreak)
        ),
        active = active,
        createdAt = now,
        deadlineAt = null
    )

// ---------------------------------------------------------------------------
// The verifier: same fairness rules as the old GitHubGoalManager, now operating
// on a GoalEntity. A MISS is only recorded when the API DEFINITIVELY answers
// "no commits"; any network/API error propagates so the caller aborts + retries
// (we never punish for our own failure to check).
// ---------------------------------------------------------------------------

@Singleton
class GitHubVerifier @Inject constructor(
    private val goalDao: GoalDao,
    private val api: GitHubApi,
    private val rewards: RewardsManager,
    private val settings: SettingsRepository,
    private val notifier: Notifier
) : GoalVerifier {

    override val type = GoalType.GITHUB_COMMIT
    override val cadence = VerificationCadence.Polled(POLL_INTERVAL_MIN)

    override suspend fun verify(goal: GoalEntity): VerificationResult {
        val token = settings.githubToken.first().ifBlank { null }
        val config = goal.gitHubConfig()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var state = goal.gitHubState()
        var g = goal
        var credited = false
        var missed = false

        // 1. Punish FIRST: finalize each fully-elapsed day that wasn't a success.
        // Settling before crediting today matters — a miss on an OLDER day must not
        // zero the streak that today's commit is about to (re)start.
        val created = LocalDate.parse(state.createdDate)
        var day = state.lastSettledDate?.let { LocalDate.parse(it).plusDays(1) } ?: created
        if (day.isBefore(created)) day = created

        var guard = 0
        while (day.isBefore(today) && guard < MAX_BACKFILL_DAYS) {
            val success = state.lastSuccessDate == day.toString() || api.hasCommit(
                config.owner, config.repo, config.author.ifBlank { null },
                since = day.atStartOfDay(zone).toInstant(),
                until = day.plusDays(1).atStartOfDay(zone).toInstant(),
                token = token
            )
            state = if (!success) {
                rewards.applyPenaltyShorts(goal.penaltyShorts)
                missed = true
                Timber.tag("GitHub").i("%s/%s MISSED %s (-%d shorts)", config.owner, config.repo, day, goal.penaltyShorts)
                state.copy(currentStreak = 0, lastSettledDate = day.toString())
            } else {
                state.copy(lastSettledDate = day.toString())
            }
            g = g.copy(stateJson = encodeState(state))
            goalDao.upsert(g)
            day = day.plusDays(1)
            guard++
        }

        // 2. Reward: has a commit landed today (and not yet credited)?
        if (state.lastSuccessDate != today.toString()) {
            val committed = api.hasCommit(
                config.owner, config.repo, config.author.ifBlank { null },
                since = today.atStartOfDay(zone).toInstant(),
                until = Instant.now(),
                token = token
            )
            if (committed) {
                val continues = state.lastSuccessDate == today.minusDays(1).toString()
                val streak = if (continues) state.currentStreak + 1 else 1
                state = state.copy(
                    lastSuccessDate = today.toString(),
                    currentStreak = streak,
                    bestStreak = maxOf(state.bestStreak, streak)
                )
                g = g.copy(stateJson = encodeState(state))
                goalDao.upsert(g)
                rewards.award(goal.rewardPoints, "github_commit:${goal.id}:$today")
                credited = true
                Timber.tag("GitHub").i("%s/%s committed %s (streak %d)", config.owner, config.repo, today, streak)
                notifier.post(
                    channel = NotifChannel.GOALS,
                    id = Notifier.Ids.commit(goal.id),
                    title = "Commit logged ✅",
                    body = "${config.owner}/${config.repo} — $streak-day streak. Keep it going."
                )
            }
        }

        // 3. Invariant: a commit today is always at least a 1-day streak. Also heals
        // any goal left at streak 0 by the old reward-then-settle ordering.
        if (state.lastSuccessDate == today.toString() && state.currentStreak < 1) {
            state = state.copy(currentStreak = 1, bestStreak = maxOf(state.bestStreak, 1))
            g = g.copy(stateJson = encodeState(state))
            goalDao.upsert(g)
        }

        return when {
            credited -> VerificationResult.Passed
            missed -> VerificationResult.Failed
            else -> VerificationResult.NoChange
        }
    }

    companion object {
        const val POLL_INTERVAL_MIN = 180L        // matches the WorkManager 3-hour cadence
        private const val MAX_BACKFILL_DAYS = 14   // cap catch-up work per run
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
