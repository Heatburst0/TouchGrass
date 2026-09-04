package com.example.touchgrass.features.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.data.db.FocusSessionEntity
import com.example.touchgrass.core.data.db.FocusStats
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import com.example.touchgrass.features.focus.AppInfo
import com.example.touchgrass.features.focus.FocusConfig
import com.example.touchgrass.features.focus.FocusOutcome
import com.example.touchgrass.features.focus.FocusPhase
import com.example.touchgrass.features.focus.FocusSessionManager
import com.example.touchgrass.features.focus.InstalledApps
import com.example.touchgrass.features.focus.focusPhaseAt
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class FocusViewModel @Inject constructor(
    private val manager: FocusSessionManager,
    private val settings: SettingsRepository,
    private val installedApps: InstalledApps
) : ViewModel() {
    val activeSession = manager.activeSession
    val recentSessions = manager.recentSessions
    val stats = manager.stats
    val rememberedBlocked = settings.focusBlockedPackages
    val violations: Int get() = manager.violations

    val apps = MutableStateFlow<List<AppInfo>>(emptyList())

    init {
        viewModelScope.launch { apps.value = installedApps.launchable() }
    }

    fun start(config: FocusConfig) = manager.start(config)
    fun endEarly() = manager.endEarly()
    fun clearToIdle() = manager.clearToIdle()
    fun settleIfComplete() = manager.settleIfComplete()
    fun saveBlocked(packages: Set<String>) {
        viewModelScope.launch { settings.setFocusBlockedPackages(packages) }
    }
    fun label(pkg: String): String = installedApps.label(pkg)
}

@Composable
fun FocusScreen(viewModel: FocusViewModel = hiltViewModel()) {
    val active by viewModel.activeSession.collectAsState()
    val stats by viewModel.stats.collectAsState(initial = FocusStats(0, 0, 0))
    val sessions by viewModel.recentSessions.collectAsState(initial = emptyList())
    val remembered by viewModel.rememberedBlocked.collectAsState(initial = emptySet())
    val apps by viewModel.apps.collectAsState()

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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Focus", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Block distractions in Pomodoro cycles. Stay on task, earn the streak.",
            color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        when (phase) {
            FocusPhase.Idle -> SetupCard(
                apps = apps,
                remembered = remembered,
                labelOf = viewModel::label,
                onStart = { viewModel.start(it) },
                onSaveBlocked = { viewModel.saveBlocked(it) }
            )
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

        Spacer(Modifier.height(24.dp))
        HistorySection(stats = stats, sessions = sessions)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupCard(
    apps: List<AppInfo>,
    remembered: Set<String>,
    labelOf: (String) -> String,
    onStart: (FocusConfig) -> Unit,
    onSaveBlocked: (Set<String>) -> Unit
) {
    var focus by remember { mutableIntStateOf(25) }
    var brk by remember { mutableIntStateOf(5) }
    var cycles by remember { mutableIntStateOf(4) }
    var strict by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    // Selected blocklist: remembered choice, else the default watched-apps seed.
    var selected by remember(remembered) {
        mutableStateOf(remembered.ifEmpty { ScreenTimeNudger.WATCHED_PACKAGES })
    }

    val cappedBreak = FocusConfig.capBreak(focus, brk)
    val totalMin = cycles * focus + (cycles - 1).coerceAtLeast(0) * cappedBreak

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        StepperRow("Focus block", "$focus min", { focus = (focus - 5).coerceAtLeast(FocusConfig.MIN_FOCUS) }) {
            focus = (focus + 5).coerceAtMost(FocusConfig.MAX_FOCUS)
        }
        Spacer(Modifier.height(14.dp))
        StepperRow("Break", "$cappedBreak min", { brk = (brk - 5).coerceAtLeast(FocusConfig.MIN_BREAK) }) {
            brk = (brk + 5)
        }
        Spacer(Modifier.height(14.dp))
        StepperRow("Cycles", "$cycles", { cycles = (cycles - 1).coerceAtLeast(1) }) {
            cycles = (cycles + 1).coerceAtMost(FocusConfig.MAX_CYCLES)
        }

        Spacer(Modifier.height(14.dp))
        // Blocked apps row → opens the picker.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { pickerOpen = true }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Blocked apps", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${selected.size} selected  ›",
                color = GrassGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(14.dp))
        // Strict (unkillable) toggle.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Strict mode", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Locks you in — no End button, ongoing reminder until the session finishes.",
                    color = TextSecondary, fontSize = 11.sp
                )
            }
            Spacer(Modifier.size(12.dp))
            Switch(
                checked = strict,
                onCheckedChange = { strict = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink,
                    checkedTrackColor = GrassGreen,
                    uncheckedTrackColor = InkBorder
                )
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("Total $totalMin min  ·  ${selected.size} apps blocked", color = AmberWarn, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                onStart(
                    FocusConfig(
                        focusBlockMin = focus,
                        breakMin = cappedBreak,
                        cycles = cycles,
                        blockedPackages = selected,
                        strict = strict
                    )
                )
            },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) {
            Text("Start focus session", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (pickerOpen) {
        AppPickerDialog(
            apps = apps,
            labelOf = labelOf,
            initiallySelected = selected,
            onDismiss = { pickerOpen = false },
            onConfirm = {
                selected = it
                onSaveBlocked(it)
                pickerOpen = false
            }
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppInfo>,
    labelOf: (String) -> String,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateOf(initiallySelected) }

    // Ensure already-selected packages that aren't launchable (or not yet loaded)
    // still appear so they can be unchecked.
    val known = remember(apps) { apps.associateBy { it.packageName } }
    val extra = initiallySelected.filter { it !in known }.map { AppInfo(it, labelOf(it)) }
    val all = (extra + apps)
    val filtered = all.filter { it.label.contains(query, ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(InkElevated)
                .border(1.dp, InkBorder, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Text("Block during focus", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("${chosen.value.size} selected", color = GrassGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val isChecked = app.packageName in chosen.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chosen.value = if (isChecked) chosen.value - app.packageName
                                else chosen.value + app.packageName
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { on ->
                                chosen.value = if (on) chosen.value + app.packageName
                                else chosen.value - app.packageName
                            },
                            colors = CheckboxDefaults.colors(checkedColor = GrassGreen, checkmarkColor = Ink)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(app.label, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Cancel",
                    color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onDismiss() }.padding(12.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Done",
                    color = GrassGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onConfirm(chosen.value) }.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun RunningCard(
    title: String,
    subtitle: String,
    bigTime: String,
    caption: String,
    accent: androidx.compose.ui.graphics.Color,
    violations: Int,
    strict: Boolean,
    onEnd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title.uppercase(), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(bigTime, color = TextPrimary, fontSize = 60.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(caption, color = TextSecondary, fontSize = 12.sp)
        if (violations > 0) {
            Spacer(Modifier.height(10.dp))
            Text("$violations distraction${if (violations == 1) "" else "s"} blocked", color = DangerRed, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        if (strict) {
            Text("🔒 Strict — locked in until the session ends", color = AmberWarn, fontSize = 12.sp, textAlign = TextAlign.Center)
        } else {
            Button(
                onClick = onEnd,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InkBorder, contentColor = TextPrimary)
            ) {
                Text("End session", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DoneCard(violations: Int, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, GrassGreen.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SESSION COMPLETE", color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (violations == 0) "Clean run — no distractions." else "$violations distraction${if (violations == 1) "" else "s"} blocked.",
            color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) {
            Text("New session", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistorySection(stats: FocusStats, sessions: List<FocusSessionEntity>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Text("Your record", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("${stats.completed}", "successful")
            Stat("${stats.total}", "started")
            Stat("${stats.focusedMinutes}", "focus min")
        }
        if (sessions.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("No sessions yet — start one above.", color = TextSecondary, fontSize = 12.sp)
        } else {
            Spacer(Modifier.height(16.dp))
            sessions.forEach { s -> SessionRow(s); Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = GrassGreen, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun SessionRow(s: FocusSessionEntity) {
    val completed = s.outcome == FocusOutcome.COMPLETED.name
    val accent = if (completed) GrassGreen else AmberWarn
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(dateFmt.format(Date(s.startedAt)), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${s.focusedMin}/${s.plannedFocusMin} min · ${s.cycles} cycles" +
                    (if (s.strict) " · strict" else "") +
                    (if (s.violations > 0) " · ${s.violations} blocked" else ""),
                color = TextSecondary, fontSize = 11.sp
            )
        }
        Text(
            if (completed) "✓ done" else "ended",
            color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

private val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundBtn("−", onMinus)
            Text(value, color = GrassGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            RoundBtn("+", onPlus)
        }
    }
}

@Composable
private fun RoundBtn(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(InkBorder)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

private fun mmss(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
