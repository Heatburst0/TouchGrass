package com.example.touchgrass.presentation.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.GitHubGoalEntity
import com.example.touchgrass.core.goals.CommitmentStatus
import com.example.touchgrass.core.goals.GoalEngine
import com.example.touchgrass.core.goals.GoalView
import com.example.touchgrass.core.goals.PillarType
import com.example.touchgrass.core.goals.Recurrence
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.github.GitHubGoalManager
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalEngine: GoalEngine,
    private val gitHubManager: GitHubGoalManager,
    private val settings: SettingsRepository
) : ViewModel() {
    val active: StateFlow<List<GoalView>> = goalEngine.activeGoals
    val past: StateFlow<List<GoalView>> = goalEngine.pastGoals

    val gitHubGoals: StateFlow<List<GitHubGoalEntity>> = gitHubManager.goals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Deadline options offered in the create dialog, in hours. */
    val deadlineChoices = listOf("Today" to hoursUntilEndOfDay(), "24h" to 24, "3 days" to 72)

    private val _gitHubError = MutableStateFlow<String?>(null)
    val gitHubError: StateFlow<String?> = _gitHubError.asStateFlow()

    init {
        // Refresh GitHub verification whenever the Goals tab opens.
        viewModelScope.launch { gitHubManager.runChecks() }
    }

    fun create(pillar: PillarType, title: String, target: Int, hours: Int, recurrence: Recurrence) {
        val deadline = System.currentTimeMillis() + hours.toLong() * 3_600_000L
        goalEngine.createCommitment(
            pillar = pillar,
            title = title,
            targetAmount = target,
            deadlineAt = deadline,
            // Bonus on top of the per-page points; penalty scales with the pledge.
            rewardPoints = target * RewardsManager.POINTS_PER_PAGE,
            penaltyShorts = target.coerceIn(1, 10),
            recurrence = recurrence
        )
    }

    /** Invokes [onSuccess] only if the repo validated and the goal was saved. */
    fun createGitHubGoal(
        owner: String,
        repo: String,
        author: String,
        token: String,
        recurrence: Recurrence,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _gitHubError.value = null
            if (token.isNotBlank()) settings.setGithubToken(token)
            val error = gitHubManager.addGoal(owner, repo, author, recurrence)
            if (error == null) {
                gitHubManager.runChecks()
                onSuccess()
            } else {
                _gitHubError.value = error
            }
        }
    }

    fun clearGitHubError() {
        _gitHubError.value = null
    }


    fun removeGitHubGoal(id: Long) {
        viewModelScope.launch { gitHubManager.removeGoal(id) }
    }

    val goalLockEnabled: StateFlow<Boolean> = settings.goalLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setGoalLock(enabled: Boolean) {
        viewModelScope.launch { settings.setGoalLockEnabled(enabled) }
    }
}

@Composable
fun GoalsScreen(viewModel: GoalsViewModel = hiltViewModel()) {
    val active by viewModel.active.collectAsState()
    val past by viewModel.past.collectAsState()
    val gitHubError by viewModel.gitHubError.collectAsState()
    val gitHubGoals by viewModel.gitHubGoals.collectAsState()
    val goalLock by viewModel.goalLockEnabled.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showGitHub by remember { mutableStateOf(false) }

    if (showCreate) {
        CreateCommitmentDialog(
            deadlineChoices = viewModel.deadlineChoices,
            onConfirm = { pillar, title, target, hours, recurrence ->
                viewModel.create(pillar, title, target, hours, recurrence)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }

    if (showGitHub) {
        CreateGitHubGoalDialog(
            error = gitHubError,
            onConfirm = { owner, repo, author, token, recurrence ->
                // Dialog closes only if the repo validates (onSuccess callback).
                viewModel.createGitHubGoal(owner, repo, author, token, recurrence) {
                    showGitHub = false
                }
            },
            onDismiss = {
                viewModel.clearGitHubError()
                showGitHub = false
            }
        )
    }


    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink)
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Goals", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    text = "Pledge it, verify it, earn screen time. Miss it, lose screen time.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { showCreate = true },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
                ) {
                    Text("New pledge", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { showGitHub = true },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkElevated,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Track a repo", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            // Hard mode: block entertainment apps until today's commit is done.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(InkElevated)
                    .border(1.dp, InkBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Hard lock",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Block YouTube / Instagram / Netflix until you've committed to every tracked repo today",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = goalLock,
                    onCheckedChange = viewModel::setGoalLock,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink,
                        checkedTrackColor = GrassGreen,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = InkBorder
                    )
                )
            }
        }

        if (gitHubGoals.isNotEmpty()) {
            item { SectionLabel("Daily commits") }
            items(gitHubGoals, key = { "gh_${it.id}" }) { goal ->
                GitHubGoalCard(goal, onRemove = { viewModel.removeGitHubGoal(goal.id) })
            }
        }

        if (active.isNotEmpty()) {
            item { SectionLabel("Active") }
            items(active, key = { it.id }) { GoalCard(it) }
        }

        if (past.isNotEmpty()) {
            item { SectionLabel("History") }
            items(past, key = { it.id }) { GoalCard(it) }
        }

        if (active.isEmpty() && past.isEmpty() && gitHubGoals.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No pledges yet.\nMake one and put your scroll time on the line.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun GoalCard(g: GoalView) {
    val progressFrac = if (g.target > 0) g.progress.toFloat() / g.target else 0f
    val accent = when (g.status) {
        CommitmentStatus.ACTIVE -> GrassGreen
        CommitmentStatus.MET -> GrassGreen
        CommitmentStatus.MISSED -> DangerRed
    }
    val showBar = g.isRecurring || g.status == CommitmentStatus.ACTIVE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = g.title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (g.isRecurring) RecurrenceTag(g.recurrence) else StatusTag(g.status, accent)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${g.progress}/${g.target} ${g.unit}  ·  " + when {
                g.isRecurring -> "resets ${timeLeft(g.periodEndAt)}"
                g.status == CommitmentStatus.ACTIVE -> timeLeft(g.deadlineAt ?: 0L)
                else -> "closed"
            },
            color = TextSecondary,
            fontSize = 12.sp
        )

        if (g.isRecurring) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "🔥 ${g.currentStreak}-${streakUnit(g.recurrence)} streak" +
                        if (g.bestStreak > g.currentStreak) "   ·   best ${g.bestStreak}" else "",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        if (showBar) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(InkBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFrac.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Win +${g.rewardPoints} pts   ·   Miss -${g.penaltyShorts} shorts" +
                    if (g.isRecurring) " / ${streakUnit(g.recurrence)}" else "",
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}

private fun streakUnit(r: Recurrence): String = when (r) {
    Recurrence.Weekly -> "week"
    else -> "day"
}

@Composable
private fun RecurrenceTag(r: Recurrence) {
    val label = when (r) {
        Recurrence.Daily -> "DAILY"
        Recurrence.Weekly -> "WEEKLY"
        is Recurrence.Custom -> "CUSTOM"
        Recurrence.Once -> "ONCE"
    }
    Text(
        text = label,
        color = GrassGreen,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GrassGreen.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun StatusTag(status: CommitmentStatus, accent: androidx.compose.ui.graphics.Color) {
    val label = when (status) {
        CommitmentStatus.ACTIVE -> "ACTIVE"
        CommitmentStatus.MET -> "WON"
        CommitmentStatus.MISSED -> "MISSED"
    }
    Text(
        text = label,
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateCommitmentDialog(
    deadlineChoices: List<Pair<String, Int>>,
    onConfirm: (PillarType, String, Int, Int, Recurrence) -> Unit,
    onDismiss: () -> Unit
) {
    // Only Reading is wired to auto-verify today; others arrive with their pillars.
    val pillar = PillarType.READING
    var title by remember { mutableStateOf("") }
    var target by remember { mutableIntStateOf(5) }
    var deadlineIndex by remember { mutableIntStateOf(0) }
    var recIndex by remember { mutableIntStateOf(0) } // 0 Once, 1 Daily, 2 Weekly, 3 Custom
    var customDays by remember { mutableStateOf(emptySet<DayOfWeek>()) }

    val recurrence: Recurrence = when (recIndex) {
        1 -> Recurrence.Daily
        2 -> Recurrence.Weekly
        3 -> Recurrence.Custom(customDays)
        else -> Recurrence.Once
    }
    val periodWord = when (recIndex) { 1 -> "day"; 2 -> "week"; 3 -> "active day"; else -> "" }
    val canConfirm = recIndex != 3 || customDays.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        title = { Text("New reading pledge", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("Name it (e.g. Finish chapter 3)", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = GrassGreen,
                        unfocusedBorderColor = InkBorder,
                        cursorColor = GrassGreen
                    )
                )
                Spacer(Modifier.height(16.dp))

                Text("Repeat", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Once", "Daily", "Weekly", "Custom").forEachIndexed { i, label ->
                        ChoiceChip(label, selected = i == recIndex) { recIndex = i }
                    }
                }

                if (recIndex == 3) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DayOfWeek.values().forEach { d ->
                            val sel = d in customDays
                            DayChip(d.name.take(1), sel) {
                                customDays = if (sel) customDays - d else customDays + d
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (periodWord.isEmpty()) "Pages: $target" else "Pages per $periodWord: $target",
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stepper("-", enabled = target > 1) { target-- }
                    Stepper("+", enabled = target < 50) { target++ }
                }

                if (recIndex == 0) {
                    Spacer(Modifier.height(16.dp))
                    Text("Deadline", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        deadlineChoices.forEachIndexed { i, (label, _) ->
                            ChoiceChip(label, selected = i == deadlineIndex) { deadlineIndex = i }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Win +${target * RewardsManager.POINTS_PER_PAGE} pts   ·   " +
                            "Miss -${target.coerceIn(1, 10)} shorts" +
                            if (periodWord.isEmpty()) " today" else " / $periodWord",
                    color = AmberWarn,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pillar, title, target, deadlineChoices[deadlineIndex].second, recurrence) },
                enabled = canConfirm
            ) {
                Text(
                    "Pledge",
                    color = if (canConfirm) GrassGreen else TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Ink else TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) GrassGreen else Ink)
            .border(1.dp, if (selected) GrassGreen else InkBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (selected) GrassGreen else Ink)
            .border(1.dp, if (selected) GrassGreen else InkBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Ink else TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GitHubGoalCard(goal: GitHubGoalEntity, onRemove: () -> Unit) {
    val doneToday = goal.lastSuccessDate == LocalDate.now().toString()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${goal.owner}/${goal.repo}",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (doneToday) "TODAY ✓" else "TODAY —",
                color = if (doneToday) GrassGreen else AmberWarn,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background((if (doneToday) GrassGreen else AmberWarn).copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "🔥 ${goal.currentStreak}-day streak" +
                    if (goal.bestStreak > goal.currentStreak) "   ·   best ${goal.bestStreak}" else "",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Commit daily: +${goal.rewardPoints} pts   ·   Miss: -${goal.penaltyShorts} shorts",
                color = TextSecondary,
                fontSize = 11.sp
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Remove goal",
                    tint = DangerRed,
                    modifier = Modifier.size(18.dp)
                )
            }

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateGitHubGoalDialog(
    error: String?,
    onConfirm: (owner: String, repo: String, author: String, token: String, recurrence: Recurrence) -> Unit,
    onDismiss: () -> Unit
) {
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var recIndex by remember { mutableIntStateOf(0) } // 0 Daily, 1 Weekly, 2 Custom
    var customDays by remember { mutableStateOf(emptySet<DayOfWeek>()) }

    val recurrence: Recurrence = when (recIndex) {
        1 -> Recurrence.Weekly
        2 -> Recurrence.Custom(customDays)
        else -> Recurrence.Daily
    }
    val canTrack = owner.isNotBlank() && repo.isNotBlank() && (recIndex != 2 || customDays.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        title = { Text("Track a GitHub repo", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Commit to this repo to earn screen time; miss a period and lose some. Verified straight from GitHub.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                DialogField(owner, { owner = it }, "Owner (e.g. Heatburst0)")
                Spacer(Modifier.height(8.dp))
                DialogField(repo, { repo = it }, "Repository name")
                Spacer(Modifier.height(8.dp))
                DialogField(author, { author = it }, "Your GitHub username (optional)")
                Spacer(Modifier.height(8.dp))
                DialogField(token, { token = it }, "Token — only for private repos (optional)")

                Spacer(Modifier.height(12.dp))
                Text("Commit", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Daily", "Weekly", "Custom").forEachIndexed { i, label ->
                        ChoiceChip(label, selected = i == recIndex) { recIndex = i }
                    }
                }
                if (recIndex == 2) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DayOfWeek.values().forEach { d ->
                            val sel = d in customDays
                            DayChip(d.name.take(1), sel) {
                                customDays = if (sel) customDays - d else customDays + d
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Leave username blank to count any commit to the repo. A token is only needed for private repos.",
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(text = it, color = DangerRed, fontSize = 12.sp, lineHeight = 16.sp)
                }

            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(owner, repo, author, token, recurrence) },
                enabled = canTrack
            ) {
                Text(
                    "Track",
                    color = if (canTrack) GrassGreen else TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
private fun DialogField(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = TextSecondary) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = GrassGreen,
            unfocusedBorderColor = InkBorder,
            cursorColor = GrassGreen
        )
    )
}

@Composable
private fun Stepper(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (enabled) InkBorder else InkBorder.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = if (enabled) TextPrimary else TextSecondary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---- pure helpers (no wall-clock captured in composition) ----

private fun hoursUntilEndOfDay(): Int {
    val now = java.time.LocalDateTime.now()
    val endOfDay = now.toLocalDate().atTime(23, 59)
    val minutes = java.time.Duration.between(now, endOfDay).toMinutes()
    return (minutes / 60.0).roundToInt().coerceAtLeast(1)
}

private fun timeLeft(deadlineAt: Long): String {
    val ms = deadlineAt - System.currentTimeMillis()
    if (ms <= 0) return "due now"
    val hours = ms / 3_600_000L
    return when {
        hours >= 48 -> "${hours / 24}d left"
        hours >= 1 -> "${hours}h left"
        else -> "${ms / 60_000L}m left"
    }
}
