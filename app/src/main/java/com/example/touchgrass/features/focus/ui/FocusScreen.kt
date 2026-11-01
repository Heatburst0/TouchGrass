package com.example.touchgrass.features.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.touchgrass.core.discipline.PlanField
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import com.example.touchgrass.features.focus.FocusPhase
import com.example.touchgrass.features.focus.focusPhaseAt
import com.example.touchgrass.features.weeklyplan.ui.LockMessageToast
import com.example.touchgrass.features.weeklyplan.ui.WeeklyLockBadge
import com.example.touchgrass.isAccessibilityEnabled
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * The Focus tab, in labelled sections: start a session now, the automatic starters
 * (schedules, work targets), weekly quotas, the laptop agent, time sinks and your record.
 */
@Composable
fun FocusScreen(
    onOpenLaptopRules: () -> Unit = {},
    onOpenWorkTargets: () -> Unit = {},
    onOpenWeeklyPlan: () -> Unit = {},
    viewModel: FocusViewModel = hiltViewModel()
) {
    val weeklyPlan by viewModel.weeklyPlan.collectAsState()
    val lockMessage by viewModel.lockMessage.collectAsState()
    LockMessageToast(lockMessage) { viewModel.lockMessage.value = null }
    val active by viewModel.activeSession.collectAsState()
    val syncToLaptop by viewModel.syncToLaptop.collectAsState(initial = false)
    val blockSites by viewModel.blockSitesOnLaptop.collectAsState(initial = false)
    val recordStats by viewModel.recordStats.collectAsState()
    val recordItems by viewModel.recordItems.collectAsState()
    val suggestions by viewModel.blockSuggestions.collectAsState()
    val quotaRows by viewModel.quotaRows.collectAsState()
    val autoFocusTargets by viewModel.autoFocusTargets.collectAsState()
    val laptopStatus by viewModel.laptopStatus.collectAsState()
    // Track the open row by id so the sheet shows fresh data when sync fills in detail.
    var openRecordId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openRecord = recordItems.firstOrNull { it.primary.id == openRecordId }
    var schedulesOpen by rememberSaveable { mutableStateOf(false) }
    val remembered by viewModel.rememberedBlocked.collectAsState(initial = emptySet())
    val apps by viewModel.apps.collectAsState()
    val schedules by viewModel.schedules.collectAsState()

    // Always the saved list (or the default seed): a refused removal can't leave the UI out of sync.
    val blocked = remembered.ifEmpty { ScreenTimeNudger.WATCHED_PACKAGES }
    val canRemoveBlocked = !weeklyPlan.locked || PlanField.BLOCKED_APPS in weeklyPlan.readyUnlocks

    // Accessibility must be on for any blocking to work; laptop status goes stale — refresh on resume.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = isAccessibilityEnabled(context)
                viewModel.refreshLaptop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 1-second tick so countdowns update live; stops once the session is Done.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active != null) {
            now = System.currentTimeMillis()
            if (focusPhaseAt(active, now) is FocusPhase.Done) break
            delay(1000)
        }
        now = System.currentTimeMillis()
    }
    val phase = focusPhaseAt(active, now)

    // When the session naturally completes while the screen is open, record it.
    LaunchedEffect(phase is FocusPhase.Done) {
        if (phase is FocusPhase.Done) viewModel.settleIfComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column {
            Spacer(Modifier.height(8.dp))
            Text("Focus", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text("Block distractions and get real work done.", color = TextSecondary, fontSize = 13.sp)
        }

        if (!accessibilityOn) {
            AccessibilityWarning(onEnable = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        when (phase) {
            FocusPhase.Idle -> FocusSection(
                label = "Start now",
                explanation = "Block your distracting apps for a set time."
            ) {
                StartNowCard(
                    blocked = blocked,
                    lockedBlocked = if (canRemoveBlocked) emptySet() else remembered,
                    apps = apps,
                    labelOf = viewModel::label,
                    syncToLaptop = syncToLaptop,
                    blockSites = blockSites,
                    onSetSyncToLaptop = viewModel::setSyncToLaptop,
                    onSetBlockSites = viewModel::setBlockSites,
                    onStart = { cfg, sync, block -> viewModel.start(cfg, sync, block) },
                    onSaveBlocked = viewModel::saveBlocked
                )
            }
            is FocusPhase.Focusing -> RunningCard(
                title = "Focusing",
                subtitle = "Cycle ${phase.cycle} of ${phase.totalCycles}  ·  distractions blocked",
                bigTime = mmss(phase.blockRemainingSec),
                caption = "${mmss(phase.sessionRemainingSec)} left in session",
                accent = GrassGreen,
                violations = viewModel.violations,
                strict = active?.config?.strict == true,
                onEnd = { viewModel.endEarly() }
            )
            is FocusPhase.OnBreak -> RunningCard(
                title = "Break",
                subtitle = "Cycle ${phase.cycle} of ${phase.totalCycles}  ·  apps unblocked",
                bigTime = mmss(phase.breakRemainingSec),
                caption = "Back to focus when the timer ends",
                accent = AmberWarn,
                violations = viewModel.violations,
                strict = active?.config?.strict == true,
                onEnd = { viewModel.endEarly() }
            )
            FocusPhase.Done -> DoneCard(violations = viewModel.violations, onClear = { viewModel.clearToIdle() })
        }

        FocusSection(
            label = "Automatic focus",
            explanation = "Let focus start by itself — at set times, or when you open a work project on your laptop."
        ) {
            AutomaticFocusTiles(
                scheduleCount = schedules.count { it.enabled },
                schedulesOpen = schedulesOpen,
                autoFocusTargets = autoFocusTargets,
                onToggleSchedules = { schedulesOpen = !schedulesOpen },
                onOpenWorkTargets = onOpenWorkTargets
            )
            if (schedulesOpen) {
                Spacer(Modifier.height(10.dp))
                ScheduleSection(
                    schedules = schedules,
                    blocked = remembered,
                    onAdd = { viewModel.addSchedule(it) },
                    onDelete = { viewModel.deleteSchedule(it) }
                )
            }
        }

        FocusSection(
            label = "Weekly quotas",
            explanation = "Minimum time you commit to spend on a laptop project each week.",
            trailing = { WeeklyLockBadge(weeklyPlan, onClick = onOpenWeeklyPlan) }
        ) {
            WeeklyQuotasCard(rows = quotaRows, onManage = onOpenWorkTargets)
        }

        FocusSection(
            label = "Laptop",
            explanation = "The TouchGrass agent on your computer tracks work time and blocks distractions there."
        ) {
            LaptopCard(status = laptopStatus, onOpenLaptopRules = onOpenLaptopRules)
        }

        if (phase == FocusPhase.Idle && suggestions.isNotEmpty()) {
            FocusSection(
                label = "Time sinks",
                explanation = "Social & entertainment apps you used most this week that focus doesn't block yet."
            ) {
                BlockSuggestionsCard(
                    items = suggestions,
                    onBlock = viewModel::blockSuggested,
                    onDismiss = viewModel::dismissSuggestions
                )
            }
        }

        FocusSection(
            label = "Your record",
            explanation = "Every focus session on your phone and laptop. Tap one for details."
        ) {
            RecordSection(stats = recordStats, items = recordItems, onOpen = { openRecordId = it.primary.id })
        }
    }

    openRecord?.let { item ->
        SessionDetailSheet(item = item, phoneLabel = viewModel::label, onDismiss = { openRecordId = null })
    }
}
