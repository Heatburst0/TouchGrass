package com.example.touchgrass.presentation.laptop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.touchgrass.core.remote.AppTime
import com.example.touchgrass.core.remote.LaptopSession
import com.example.touchgrass.core.remote.LaptopSessionsRepository
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LaptopStatsViewModel @Inject constructor(
    private val repo: LaptopSessionsRepository
) : ViewModel() {
    val isConfigured: Boolean = repo.isConfigured
    val sessions = repo.sessions

    init {
        if (isConfigured) viewModelScope.launch { repo.refresh() }
    }

    fun refresh() = viewModelScope.launch { repo.refresh() }
}

@Composable
fun LaptopStatsScreen(viewModel: LaptopStatsViewModel = hiltViewModel()) {
    val sessions by viewModel.sessions.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = sessions.firstOrNull { it.id == selectedId }

    BackHandler(enabled = selectedId != null) { selectedId = null }

    if (selected != null) {
        SessionDetail(selected) { selectedId = null }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Laptop stats", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Past laptop focus sessions. Tap one to see where your time went.", color = TextSecondary, fontSize = 13.sp)

        if (!viewModel.isConfigured) {
            StatCard { Text("Sign in on Tools → Account & sync to see laptop stats.", color = TextSecondary, fontSize = 12.sp) }
            return@Column
        }
        if (sessions.isEmpty()) {
            StatCard {
                Text("No sessions yet", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Run a focus session with the laptop agent and it'll show up here.", color = TextSecondary, fontSize = 12.sp)
            }
            return@Column
        }

        sessions.forEach { s ->
            SessionRow(s) { selectedId = s.id }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SessionRow(s: LaptopSession, onClick: () -> Unit) {
    StatCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatWhen(s.startedAt), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${fmtMin(s.productiveSec)} productive · ${fmtMin(s.offTaskSec)} off-task · ${s.cycles} cycle${if (s.cycles == 1) "" else "s"}",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
            OutcomeChip(s.outcome)
            Spacer(Modifier.size(6.dp))
            Text("›", color = TextSecondary, fontSize = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
        SplitBar(s.productiveSec, s.offTaskSec)
    }
}

@Composable
private fun SessionDetail(s: LaptopSession, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", color = GrassGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onBack() })
        }
        Text(formatWhen(s.startedAt), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "${s.cycles} cycle${if (s.cycles == 1) "" else "s"} · ${s.outcome.lowercase().replace('_', ' ')} · ${s.violations} off-task switches",
            color = TextSecondary, fontSize = 12.sp
        )

        StatCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Productive", fmtMin(s.productiveSec), GrassGreen)
                Metric("Off-task", fmtMin(s.offTaskSec), AmberWarn)
                val total = s.productiveSec + s.offTaskSec
                val pct = if (total > 0) (s.productiveSec * 100 / total) else 0
                Metric("Focus", "$pct%", TextPrimary)
            }
            Spacer(Modifier.height(12.dp))
            SplitBar(s.productiveSec, s.offTaskSec)
        }

        AppBreakdown("Productive apps", s.productive, GrassGreen)
        AppBreakdown("Off-task apps", s.offTask, AmberWarn)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AppBreakdown(title: String, apps: List<AppTime>, tint: androidx.compose.ui.graphics.Color) {
    StatCard {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (apps.isEmpty()) {
            Text("None", color = TextSecondary, fontSize = 12.sp)
            return@StatCard
        }
        val max = apps.maxOf { it.seconds }.coerceAtLeast(1)
        apps.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(fmtDuration(a.seconds), color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(InkBorder)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(a.seconds.toFloat() / max)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(tint)
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, tint: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = tint, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun SplitBar(productiveSec: Int, offTaskSec: Int) {
    val total = productiveSec + offTaskSec
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(InkBorder)
    ) {
        if (total == 0) return@Row // empty track only
        if (productiveSec > 0) {
            Box(
                Modifier
                    .fillMaxWidth(productiveSec.toFloat() / total)
                    .fillMaxHeight()
                    .background(GrassGreen)
            )
        }
        if (offTaskSec > 0) {
            Box(Modifier.fillMaxHeight().weight(1f).background(AmberWarn))
        }
    }
}

@Composable
private fun OutcomeChip(outcome: String) {
    val completed = outcome.equals("COMPLETED", ignoreCase = true)
    val tint = if (completed) GrassGreen else DangerRed
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.15f))
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(if (completed) "Completed" else "Ended early", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatCard(onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
        content = content
    )
}

private fun fmtMin(sec: Int): String = "${(sec + 30) / 60}m" // round to nearest minute

private fun fmtDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun formatWhen(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault()))
}.getOrDefault(iso)
