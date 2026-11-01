package com.example.touchgrass.features.worktargets.ui

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.touchgrass.core.remote.DeviceApp
import com.example.touchgrass.features.worktargets.WorkTarget
import com.example.touchgrass.features.worktargets.WorkTargetUsage
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

private const val QUOTA_STEP_MIN = 30
private const val QUOTA_MAX_MIN = 40 * 60
private const val FOCUS_STEP_MIN = 5

/** Add / edit one work target: app, optional project, weekly quota and auto-focus. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkTargetEditor(
    initial: TargetRow,
    apps: List<DeviceApp>,
    recentProjects: (String?) -> List<String>,
    quotaLocked: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorkTarget, Int) -> Unit,
    onDelete: (() -> Unit)?
) {
    val start = initial.target
    var appName by remember { mutableStateOf(start.appName) }
    var project by remember { mutableStateOf(start.project.orEmpty()) }
    var quota by remember { mutableIntStateOf(initial.quotaMin) }
    var autoFocus by remember { mutableStateOf(start.autoFocus) }
    var afterMin by remember { mutableIntStateOf(start.autoFocusAfterMin) }
    var focusMin by remember { mutableIntStateOf(start.focusMin) }
    var blockSites by remember { mutableStateOf(start.blockSites) }
    var blockPhone by remember { mutableStateOf(start.blockPhone) }
    // While locked, the quota can't go below what's committed (the plan would refuse it).
    val quotaFloor = if (quotaLocked) initial.quotaMin else 0
    // …and while a quota is locked, what it measures (app + project) is locked too.
    val criteriaLocked = quotaLocked && initial.quotaMin > 0 && start.id.isNotBlank()
    val projects = remember(appName) { recentProjects(appName.ifBlank { null }).take(8) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = InkElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                if (start.id.isBlank()) "New work target" else "Edit work target",
                color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )

            if (criteriaLocked) {
                Hint("🔒 App and project are locked with this target's quota until the weekend.", AmberWarn)
            }
            Label("App")
            if (criteriaLocked) {
                Text(appName, color = TextPrimary, fontSize = 14.sp)
            } else if (apps.isEmpty()) {
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it.lowercase() },
                    placeholder = { Text("e.g. code, idea64", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Hint("Your laptop's apps show up here once the agent has seen them.")
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    apps.forEach { app ->
                        Chip(app.displayName, selected = appName == app.name) { appName = app.name; project = "" }
                    }
                }
            }

            Label("Project (optional)")
            if (criteriaLocked) {
                Text(project.ifBlank { "Any project" }, color = TextPrimary, fontSize = 14.sp)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Any project", selected = project.isBlank()) { project = "" }
                    projects.forEach { p -> Chip(p, selected = project.equals(p, ignoreCase = true)) { project = p } }
                }
                OutlinedTextField(
                    value = project,
                    onValueChange = { project = it },
                    placeholder = { Text("or type a project name", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Label("Weekly quota")
            Stepper(
                value = if (quota == 0) "None" else WorkTargetUsage.formatMinutes(quota),
                minusEnabled = quota > 0 && quota - QUOTA_STEP_MIN >= quotaFloor,
                plusEnabled = quota < QUOTA_MAX_MIN,
                onMinus = { quota = (quota - QUOTA_STEP_MIN).coerceAtLeast(quotaFloor) },
                onPlus = { quota = (quota + QUOTA_STEP_MIN).coerceAtMost(QUOTA_MAX_MIN) }
            )
            if (quotaLocked && initial.quotaMin > 0) {
                Hint("🔒 Locked by your weekly plan — you can raise it, not lower it.", AmberWarn)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-focus", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Hint("Start focus automatically when you get to work on this")
                }
                Switch(
                    checked = autoFocus,
                    onCheckedChange = { autoFocus = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = GrassGreen, uncheckedTrackColor = InkBorder)
                )
            }
            if (autoFocus) {
                Label("Start after")
                Stepper(
                    value = "$afterMin min of work",
                    minusEnabled = afterMin > WorkTarget.AFTER_MIN_RANGE.first,
                    plusEnabled = afterMin < WorkTarget.AFTER_MIN_RANGE.last,
                    onMinus = { afterMin-- },
                    onPlus = { afterMin++ }
                )
                Label("Session length")
                Stepper(
                    value = "$focusMin min",
                    minusEnabled = focusMin > WorkTarget.FOCUS_MIN_RANGE.first,
                    plusEnabled = focusMin < WorkTarget.FOCUS_MIN_RANGE.last,
                    onMinus = { focusMin = (focusMin - FOCUS_STEP_MIN).coerceAtLeast(WorkTarget.FOCUS_MIN_RANGE.first) },
                    onPlus = { focusMin = (focusMin + FOCUS_STEP_MIN).coerceAtMost(WorkTarget.FOCUS_MIN_RANGE.last) }
                )
                CheckRow("Block sites on laptop", blockSites) { blockSites = it }
                CheckRow("Block apps on phone", blockPhone) { blockPhone = it }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val p = project.trim().ifBlank { null }
                    onSave(
                        start.copy(
                            label = p ?: apps.firstOrNull { it.name == appName }?.displayName ?: appName,
                            appName = appName.trim().lowercase(),
                            project = p,
                            autoFocus = autoFocus,
                            autoFocusAfterMin = afterMin,
                            focusMin = focusMin,
                            blockSites = blockSites,
                            blockPhone = blockPhone
                        ),
                        quota
                    )
                },
                enabled = appName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
            ) {
                Text("Save work target", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            if (onDelete != null) {
                Text(
                    "Delete target",
                    color = DangerRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Hint(text: String, color: Color = TextSecondary) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = if (selected) Ink else TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) GrassGreen else Ink)
            .border(1.dp, if (selected) GrassGreen else InkBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = GrassGreen, checkmarkColor = Ink)
        )
        Text(label, color = TextPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun Stepper(value: String, minusEnabled: Boolean, plusEnabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundButton("−", minusEnabled, onMinus)
        Text(value, color = GrassGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
