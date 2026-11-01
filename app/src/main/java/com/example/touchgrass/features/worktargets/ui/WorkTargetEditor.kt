package com.example.touchgrass.features.worktargets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.features.worktargets.WorkTarget
import com.example.touchgrass.features.worktargets.WorkTargetSuggestions
import com.example.touchgrass.features.worktargets.WorkTargetUsage
import com.example.touchgrass.ui.components.ValueSlider
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

private const val QUOTA_STEP_MIN = 15
private const val QUOTA_MAX_MIN = 40 * 60
private const val FOCUS_STEP_MIN = 5

/** Add / edit one work target: what to work on, weekly quota and auto-focus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkTargetEditor(
    initial: TargetRow,
    source: PickerSource,
    quotaLocked: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorkTarget, Int) -> Unit,
    onDelete: (() -> Unit)?
) {
    val start = initial.target
    var appName by remember { mutableStateOf(start.appName) }
    var appLabel by remember {
        mutableStateOf(
            if (start.appName.isBlank()) ""
            else WorkTargetSuggestions.label(start.appName, source.apps.firstOrNull { it.name == start.appName }?.displayName)
        )
    }
    var project by remember { mutableStateOf(start.project.orEmpty()) }
    var quota by remember { mutableIntStateOf(initial.quotaMin) }
    var autoFocus by remember { mutableStateOf(start.autoFocus) }
    var afterMin by remember { mutableIntStateOf(start.autoFocusAfterMin) }
    var focusMin by remember { mutableIntStateOf(start.focusMin) }
    var blockSites by remember { mutableStateOf(start.blockSites) }
    var blockPhone by remember { mutableStateOf(start.blockPhone) }
    var confirmDelete by remember { mutableStateOf(false) }
    val quotaHeld = quotaLocked && initial.quotaMin > 0
    // …and while a quota is locked, what it measures (app + project) is locked too.
    val criteriaLocked = quotaHeld && start.id.isNotBlank()
    val noLaptopData = source.apps.isEmpty() && source.projects.isEmpty() && source.usage.isEmpty()

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
            Label("What do you want to work on?")
            when {
                criteriaLocked -> {
                    Text(targetTitle(appLabel.ifBlank { appName }, project), color = TextPrimary, fontSize = 14.sp)
                    Hint("🔒 Locked with this target's quota until the weekend.", AmberWarn)
                }
                noLaptopData -> {
                    OutlinedTextField(
                        value = appName,
                        onValueChange = { appName = it.lowercase(); appLabel = WorkTargetSuggestions.label(it) },
                        placeholder = { Text("App name, e.g. visual studio code", color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Hint("Your laptop's projects and apps show up here once the agent has seen them.")
                }
                else -> TargetPicker(source, appName, project.ifBlank { null }) { app, label, p ->
                    appName = app; appLabel = label; project = p.orEmpty()
                }
            }
            if (!criteriaLocked && appName.isNotBlank()) {
                OutlinedTextField(
                    value = project,
                    onValueChange = { project = it },
                    label = { Text("Only this project (optional)") },
                    placeholder = { Text("Blank = any project in $appLabel", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(4.dp))
            ValueSlider(
                label = "Weekly quota",
                value = quota,
                range = 0..QUOTA_MAX_MIN,
                step = QUOTA_STEP_MIN,
                format = { if (it == 0) "None" else WorkTargetUsage.formatMinutes(it) },
                caption = { "Minimum time on this each week" },
                onValueChange = { quota = it },
                // While locked, the quota can't go below what's committed (the plan would refuse it).
                lockedMin = if (quotaHeld) initial.quotaMin else null,
                lockReason = "🔒 Weekly plan: can only go up until the weekend"
            )

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
                ValueSlider(
                    label = "Start after",
                    value = afterMin,
                    range = WorkTarget.AFTER_MIN_RANGE,
                    format = { "$it min" },
                    caption = { "of work on this" },
                    onValueChange = { afterMin = it }
                )
                ValueSlider(
                    label = "Session length",
                    value = focusMin,
                    range = WorkTarget.FOCUS_MIN_RANGE,
                    step = FOCUS_STEP_MIN,
                    format = { WorkTargetUsage.formatMinutes(it) },
                    onValueChange = { focusMin = it }
                )
                CheckRow("Block sites on laptop", blockSites) { blockSites = it }
                CheckRow("Block apps on phone", blockPhone) { blockPhone = it }
            }

            Spacer(Modifier.height(4.dp))
            val canSave = appName.isNotBlank()
            Button(
                onClick = {
                    val p = project.trim().ifBlank { null }
                    onSave(
                        start.copy(
                            label = p ?: appLabel.ifBlank { appName },
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
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
            ) {
                Text("Save work target", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            if (!canSave) {
                Text(
                    "Pick a project or app to continue",
                    color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (onDelete != null) {
                Text(
                    "Delete target",
                    color = DangerRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { confirmDelete = true }.padding(12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this work target?") },
            text = {
                Text(
                    "\"${targetTitle(appLabel.ifBlank { appName }, project)}\" and its auto-focus will be removed." +
                        if (quotaHeld) " Its quota is locked by your weekly plan, so this waits for the weekend." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private fun targetTitle(app: String, project: String): String =
    if (project.isBlank()) "Any project in $app" else "${project.trim()} · $app"

@Composable
private fun Label(text: String) {
    Text(text, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Hint(text: String, color: Color = TextSecondary) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp)
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
