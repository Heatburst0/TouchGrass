package com.example.touchgrass.features.weeklyplan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.discipline.PlanField
import com.example.touchgrass.core.discipline.WeeklyLock
import com.example.touchgrass.core.discipline.WeeklyPlan
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.focus.AppInfo
import com.example.touchgrass.features.focus.InstalledApps
import com.example.touchgrass.features.focus.ui.AppPickerDialog
import com.example.touchgrass.features.reading.ui.PointsChip
import com.example.touchgrass.features.weeklyplan.WeeklyPlanRepository
import com.example.touchgrass.features.weeklyplan.WeeklyPlanState
import com.example.touchgrass.presentation.tools.NUDGE_INTERVAL_CHOICES
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SHORTS_MIN = 1
private const val SHORTS_MAX = 50
private const val PAGES_STEP = 10
private const val PAGES_MAX = 2000
private const val REFRESH_MS = 30_000L
private const val HOUR_MS = 3_600_000L

@HiltViewModel
class WeeklyPlanViewModel @Inject constructor(
    private val repo: WeeklyPlanRepository,
    rewards: RewardsManager,
    private val installedApps: InstalledApps
) : ViewModel() {
    val state: StateFlow<WeeklyPlanState> = repo.state
    val points: StateFlow<Int> = rewards.pointsBalance
    val readingProgress: StateFlow<Int?> = repo.readingProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { apps.value = installedApps.launchable() }
    }

    fun refresh() = repo.refresh()
    fun label(pkg: String): String = installedApps.label(pkg)

    /** One entry point for every card; a refused loosening explains itself. */
    fun edit(field: PlanField, transform: (WeeklyPlan) -> WeeklyPlan) {
        viewModelScope.launch {
            if (field in repo.update(transform).refused) {
                message.value = "Locked until the weekend — request an unlock to loosen this."
            }
        }
    }

    fun commit() {
        viewModelScope.launch { repo.commit() }
    }

    fun requestUnlock(field: PlanField) {
        viewModelScope.launch {
            message.value = when {
                repo.requestUnlock(field) -> "Unlock requested — you can loosen it in 24 h."
                points.value < WeeklyLock.UNLOCK_COST_POINTS ->
                    "You need ${WeeklyLock.UNLOCK_COST_POINTS} pts to unlock (you have ${points.value})."
                else -> "Couldn't request an unlock right now."
            }
        }
    }
}

/** Where every weekly-lockable setting lives together (each feature also edits its own). */
@Composable
fun WeeklyPlanScreen(viewModel: WeeklyPlanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val points by viewModel.points.collectAsState()
    val pagesRead by viewModel.readingProgress.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val message by viewModel.message.collectAsState()
    var confirmCommit by remember { mutableStateOf(false) }
    var unlockField by remember { mutableStateOf<PlanField?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    // Keep the countdown, the weekend switch and unlock readiness fresh.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(REFRESH_MS)
        }
    }
    LockMessageToast(message) { viewModel.message.value = null }

    val plan = state.plan
    val canLoosen = { field: PlanField -> !state.locked || field in state.readyUnlocks }
    val lockStatus: @Composable (PlanField) -> Unit = { field ->
        FieldLockStatus(state, field, onRequest = { unlockField = field })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Weekly plan", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("Set it once, stick to it Mon–Fri", color = TextSecondary, fontSize = 13.sp)
            }
            PointsChip(points)
        }

        StatusBanner(state)

        PlanCard("Force read", "Your book takes over after entertainment time", { lockStatus(PlanField.FORCE_READ_ENABLED) }) {
            SwitchRow(
                label = "Force-read mode",
                checked = plan.forceReadEnabled,
                enabled = !plan.forceReadEnabled || canLoosen(PlanField.FORCE_READ_ENABLED),
                onChange = { on -> viewModel.edit(PlanField.FORCE_READ_ENABLED) { it.copy(forceReadEnabled = on) } }
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Every", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                lockStatus(PlanField.FORCE_READ_INTERVAL)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NUDGE_INTERVAL_CHOICES.forEach { (label, minutes) ->
                    ChoiceChip(
                        label = label,
                        selected = plan.forceReadEveryMin == minutes,
                        enabled = minutes <= plan.forceReadEveryMin || canLoosen(PlanField.FORCE_READ_INTERVAL),
                        onClick = { viewModel.edit(PlanField.FORCE_READ_INTERVAL) { it.copy(forceReadEveryMin = minutes) } }
                    )
                }
            }
        }

        PlanCard("Shorts daily limit", "Blocks Shorts after this many videos", { lockStatus(PlanField.SHORTS_LIMIT) }) {
            Stepper(
                value = "${plan.shortsDailyLimit}",
                minusEnabled = plan.shortsDailyLimit > SHORTS_MIN,
                plusEnabled = plan.shortsDailyLimit < SHORTS_MAX && canLoosen(PlanField.SHORTS_LIMIT),
                onMinus = { viewModel.edit(PlanField.SHORTS_LIMIT) { it.copy(shortsDailyLimit = it.shortsDailyLimit - 1) } },
                onPlus = { viewModel.edit(PlanField.SHORTS_LIMIT) { it.copy(shortsDailyLimit = it.shortsDailyLimit + 1) } }
            )
        }

        PlanCard("Blocked during focus", "Apps every focus session blocks", { lockStatus(PlanField.BLOCKED_APPS) }) {
            BlockedApps(
                packages = plan.blockedPackages,
                labelOf = viewModel::label,
                removable = canLoosen(PlanField.BLOCKED_APPS),
                onRemove = { pkg -> viewModel.edit(PlanField.BLOCKED_APPS) { it.copy(blockedPackages = it.blockedPackages - pkg) } },
                onAdd = { pickerOpen = true }
            )
        }

        PlanCard("Reading", "Pages to read this week (0 = no target)", { lockStatus(PlanField.WEEKLY_PAGES) }) {
            Stepper(
                value = "${plan.weeklyReadingPages} pages",
                minusEnabled = plan.weeklyReadingPages > 0 && canLoosen(PlanField.WEEKLY_PAGES),
                plusEnabled = plan.weeklyReadingPages < PAGES_MAX,
                onMinus = {
                    viewModel.edit(PlanField.WEEKLY_PAGES) {
                        it.copy(weeklyReadingPages = (it.weeklyReadingPages - PAGES_STEP).coerceAtLeast(0))
                    }
                },
                onPlus = { viewModel.edit(PlanField.WEEKLY_PAGES) { it.copy(weeklyReadingPages = it.weeklyReadingPages + PAGES_STEP) } }
            )
            val read = pagesRead
            if (plan.weeklyReadingPages > 0 && read != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (read.toFloat() / plan.weeklyReadingPages).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color = GrassGreen,
                    trackColor = InkBorder
                )
                Spacer(Modifier.height(4.dp))
                Text("$read / ${plan.weeklyReadingPages} read this week", color = TextSecondary, fontSize = 11.sp)
            }
        }

        PlanCard(
            "Accessibility guard",
            "−10 pts every 30 min while TouchGrass tracking is switched off",
            { lockStatus(PlanField.ACCESSIBILITY_PENALTY) }
        ) {
            SwitchRow(
                label = "Charge for switching tracking off",
                checked = plan.accessibilityPenaltyEnabled,
                enabled = !plan.accessibilityPenaltyEnabled || canLoosen(PlanField.ACCESSIBILITY_PENALTY),
                onChange = { on -> viewModel.edit(PlanField.ACCESSIBILITY_PENALTY) { it.copy(accessibilityPenaltyEnabled = on) } }
            )
        }

        PlanCard("Focus quotas", "Minimum focused time per app, e.g. VS Code 20 min/week", { Tag("SOON", TextSecondary) }) {}

        if (!state.committed) {
            Button(
                onClick = { confirmCommit = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
            ) {
                Text("Commit & lock this plan", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "While locked you can always make things stricter. Loosening a setting costs " +
                "${WeeklyLock.UNLOCK_COST_POINTS} pts and takes effect after a 24 h wait.",
            color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }

    if (confirmCommit) {
        AlertDialog(
            onDismissRequest = { confirmCommit = false },
            title = { Text("Lock this plan?") },
            text = {
                Text(
                    "From Monday 00:00 to Saturday 00:00 every week, these settings can only get stricter. " +
                        "Loosening one costs ${WeeklyLock.UNLOCK_COST_POINTS} pts and a 24 h wait. Weekends are free to edit."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.commit(); confirmCommit = false }) { Text("Lock it", color = GrassGreen) }
            },
            dismissButton = { TextButton(onClick = { confirmCommit = false }) { Text("Not yet") } }
        )
    }

    unlockField?.let { field ->
        AlertDialog(
            onDismissRequest = { unlockField = null },
            title = { Text("Request an unlock?") },
            text = {
                Text(
                    "Spend ${WeeklyLock.UNLOCK_COST_POINTS} pts (you have $points). After 24 h you can loosen " +
                        "this setting once. Unused unlocks expire at the weekend."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.requestUnlock(field); unlockField = null }) {
                    Text("Spend ${WeeklyLock.UNLOCK_COST_POINTS} pts", color = AmberWarn)
                }
            },
            dismissButton = { TextButton(onClick = { unlockField = null }) { Text("Cancel") } }
        )
    }

    if (pickerOpen) {
        AppPickerDialog(
            apps = apps,
            labelOf = viewModel::label,
            initiallySelected = plan.blockedPackages,
            onDismiss = { pickerOpen = false },
            onConfirm = { chosen ->
                viewModel.edit(PlanField.BLOCKED_APPS) { it.copy(blockedPackages = chosen) }
                pickerOpen = false
            }
        )
    }
}

@Composable
private fun StatusBanner(state: WeeklyPlanState) {
    val (color, text) = when {
        !state.committed -> TextSecondary to "Draft — nothing is locked yet. Commit when you're happy with it."
        state.locked -> AmberWarn to ("🔒 Locked until " + (state.window.lockedUntil?.let(::formatLockTime) ?: "the weekend") +
            " · tighten anytime, loosening costs ${WeeklyLock.UNLOCK_COST_POINTS} pts + 24 h wait")
        else -> GrassGreen to ("Weekend — edit freely. Locks again " +
            (state.window.nextLockAt?.let(::formatLockTime) ?: "on Monday") + ".")
    }
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(14.dp)
    )
}

/** Per-field unlock state while locked: request, waiting, or ready to loosen once. */
@Composable
private fun FieldLockStatus(state: WeeklyPlanState, field: PlanField, onRequest: () -> Unit) {
    if (!state.locked) return
    val readyAt = state.pendingUnlocks[field]
    when {
        field in state.readyUnlocks -> Tag("UNLOCKED · 1 CHANGE", GrassGreen)
        readyAt != null -> {
            val hours = ((readyAt - state.asOfMs) / HOUR_MS + 1).coerceAtLeast(1)
            Tag("UNLOCK IN ${hours}H", AmberWarn)
        }
        else -> Tag("REQUEST UNLOCK", AmberWarn, onClick = onRequest)
    }
}

@Composable
private fun PlanCard(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(InkElevated)
            .border(1.dp, InkBorder, shape)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing()
        }
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = GrassGreen,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = InkBorder,
                disabledCheckedTrackColor = GrassGreen.copy(alpha = 0.45f),
                disabledCheckedThumbColor = Ink
            )
        )
    }
}

@Composable
private fun Stepper(
    value: String,
    minusEnabled: Boolean,
    plusEnabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundButton("−", minusEnabled, onMinus)
        Text(
            value, color = GrassGreen, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center, modifier = Modifier.width(140.dp)
        )
        RoundButton("+", plusEnabled, onPlus)
    }
}

@Composable
private fun RoundButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) InkBorder else InkBorder.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.5f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = label,
        color = when {
            selected -> Ink
            enabled -> TextPrimary
            else -> TextSecondary.copy(alpha = 0.5f)
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) GrassGreen else Ink)
            .border(1.dp, if (selected) GrassGreen else InkBorder, shape)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockedApps(
    packages: Set<String>,
    labelOf: (String) -> String,
    removable: Boolean,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit
) {
    if (packages.isEmpty()) {
        Text("Nothing yet — focus sessions use the default doomscroll apps.", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        packages.sortedBy { labelOf(it).lowercase() }.forEach { pkg ->
            val shape = RoundedCornerShape(50)
            Text(
                text = if (removable) "${labelOf(pkg)}  ×" else labelOf(pkg),
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(shape)
                    .background(Ink)
                    .border(1.dp, InkBorder, shape)
                    .clickable(enabled = removable) { onRemove(pkg) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
        val addShape = RoundedCornerShape(50)
        Text(
            text = "+ Add app",
            color = GrassGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(addShape)
                .border(1.dp, GrassGreen.copy(alpha = 0.5f), addShape)
                .clickable(onClick = onAdd)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun Tag(text: String, color: Color, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
