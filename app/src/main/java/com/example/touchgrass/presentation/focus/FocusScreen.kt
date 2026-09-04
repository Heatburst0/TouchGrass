package com.example.touchgrass.presentation.focus

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.example.touchgrass.core.focus.FocusConfig
import com.example.touchgrass.core.focus.FocusPhase
import com.example.touchgrass.core.focus.FocusSessionManager
import com.example.touchgrass.core.focus.focusPhaseAt
import com.example.touchgrass.core.screentime.ScreenTimeNudger
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
import javax.inject.Inject

@HiltViewModel
class FocusViewModel @Inject constructor(
    private val manager: FocusSessionManager
) : ViewModel() {
    val activeSession = manager.activeSession
    val violations: Int get() = manager.violations
    fun start(config: FocusConfig) = manager.start(config)
    fun stop() = manager.stop()
}

@Composable
fun FocusScreen(viewModel: FocusViewModel = hiltViewModel()) {
    val active by viewModel.activeSession.collectAsState()

    // 1-second tick so countdowns update live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val phase = focusPhaseAt(active, now)

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
            FocusPhase.Idle -> SetupCard(onStart = { viewModel.start(it) })
            is FocusPhase.Focusing -> RunningCard(
                title = "Focusing",
                subtitle = "Cycle ${phase.cycle} of ${phase.totalCycles}  ·  distractions blocked",
                bigTime = mmss(phase.blockRemainingSec),
                caption = "${mmss(phase.sessionRemainingSec)} left in session",
                accent = GrassGreen,
                violations = viewModel.violations,
                onEnd = { viewModel.stop() }
            )
            is FocusPhase.OnBreak -> RunningCard(
                title = "Break",
                subtitle = "Cycle ${phase.cycle} of ${phase.totalCycles}  ·  apps unblocked",
                bigTime = mmss(phase.breakRemainingSec),
                caption = "Back to focus when the timer ends",
                accent = AmberWarn,
                violations = viewModel.violations,
                onEnd = { viewModel.stop() }
            )
            FocusPhase.Done -> DoneCard(violations = viewModel.violations, onClear = { viewModel.stop() })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupCard(onStart: (FocusConfig) -> Unit) {
    var focus by remember { mutableIntStateOf(25) }
    var brk by remember { mutableIntStateOf(5) }
    var cycles by remember { mutableIntStateOf(4) }

    // Break can't exceed the focus block.
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

        Spacer(Modifier.height(16.dp))
        Text(
            "Total $totalMin min  ·  blocks YouTube, Instagram, Netflix",
            color = AmberWarn, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                onStart(
                    FocusConfig(
                        focusBlockMin = focus,
                        breakMin = cappedBreak,
                        cycles = cycles,
                        blockedPackages = ScreenTimeNudger.WATCHED_PACKAGES
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) {
            Text("Start focus session", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
