package com.example.touchgrass.features.worktargets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.touchgrass.features.weeklyplan.ui.LockMessageToast
import com.example.touchgrass.features.worktargets.WorkTarget
import com.example.touchgrass.features.worktargets.WorkTargetUsage
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

/** Laptop work targets: weekly quotas (plan-locked) and auto-focus triggers. */
@Composable
fun WorkTargetsScreen(viewModel: WorkTargetsViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsState()
    val usage by viewModel.usage.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val message by viewModel.message.collectAsState()
    // null = closed; a blank-id target = adding a new one
    var editing by remember { mutableStateOf<TargetRow?>(null) }
    LockMessageToast(message) { viewModel.message.value = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Work targets", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Time you want to spend — tracked by your laptop agent", color = TextSecondary, fontSize = 13.sp)

        if (!viewModel.isConfigured) {
            Card { Text("Sign in on Tools → Account & sync first.", color = TextSecondary, fontSize = 13.sp) }
            return@Column
        }
        if (usage.isEmpty()) {
            Card {
                Text("No laptop activity yet", color = AmberWarn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Make sure the TouchGrass agent is running on your laptop — it records time per app and project.",
                    color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }

        rows.forEach { row ->
            TargetCard(
                row = row,
                quotaLocked = plan.locked && row.quotaMin > 0,
                onToggleAutoFocus = { viewModel.setAutoFocus(row.target, it) },
                onEdit = { editing = row }
            )
        }

        Button(
            onClick = { editing = TargetRow(WorkTarget(id = "", label = "", appName = "", project = null), 0, 0) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) {
            Text("+ Add work target", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
    }

    editing?.let { row ->
        WorkTargetEditor(
            initial = row,
            apps = apps,
            recentProjects = viewModel::recentProjects,
            quotaLocked = plan.locked,
            onDismiss = { editing = null },
            onSave = { target, quota -> viewModel.save(target, quota); editing = null },
            onDelete = if (row.target.id.isBlank()) null else ({ viewModel.delete(row.target); editing = null })
        )
    }
}

@Composable
private fun TargetCard(
    row: TargetRow,
    quotaLocked: Boolean,
    onToggleAutoFocus: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    val t = row.target
    Card(modifier = Modifier.clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${t.appName} · ${t.project ?: "any project"}", color = TextSecondary, fontSize = 12.sp)
            }
            Text("Edit ›", color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))
        if (row.quotaMin > 0) {
            Text(
                "Weekly quota ${WorkTargetUsage.formatMinutes(row.quotaMin)}",
                color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (row.weekMin.toFloat() / row.quotaMin).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = GrassGreen,
                trackColor = InkBorder
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${WorkTargetUsage.formatMinutes(row.weekMin)} / ${WorkTargetUsage.formatMinutes(row.quotaMin)} this week",
                color = TextSecondary, fontSize = 11.sp
            )
            if (quotaLocked) {
                Text("🔒 Locked by weekly plan — raise only", color = AmberWarn, fontSize = 11.sp)
            }
        } else {
            Text(
                "${WorkTargetUsage.formatMinutes(row.weekMin)} this week · no quota",
                color = TextSecondary, fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-focus", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(autoFocusCaption(t), color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Switch(
                checked = t.autoFocus,
                onCheckedChange = onToggleAutoFocus,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink, checkedTrackColor = GrassGreen,
                    uncheckedThumbColor = TextSecondary, uncheckedTrackColor = InkBorder
                )
            )
        }
    }
}

internal fun autoFocusCaption(t: WorkTarget): String = buildList {
    add("Starts a ${t.focusMin}-min focus after ${t.autoFocusAfterMin} min of work")
    if (t.blockSites) add("blocks sites")
    if (t.blockPhone) add("blocks phone apps")
}.joinToString(" · ")

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(InkElevated)
            .border(1.dp, InkBorder, shape)
            .padding(18.dp)
    ) { content() }
}
