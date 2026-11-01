package com.example.touchgrass.features.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.features.focus.AppInfo
import com.example.touchgrass.features.focus.FocusConfig
import com.example.touchgrass.features.worktargets.WorkTargetUsage
import com.example.touchgrass.ui.components.ValueSlider
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

private val PRESETS = listOf(25, 50, 90)
private const val NAMES_SHOWN = 3

/**
 * "Start now": length presets (or a custom slider), which apps get blocked, the
 * laptop toggle, tucked-away cycles/breaks/strict, and Start.
 */
@Composable
internal fun StartNowCard(
    blocked: Set<String>,
    lockedBlocked: Set<String>,
    apps: List<AppInfo>,
    labelOf: (String) -> String,
    syncToLaptop: Boolean,
    blockSites: Boolean,
    onSetSyncToLaptop: (Boolean) -> Unit,
    onSetBlockSites: (Boolean) -> Unit,
    onStart: (FocusConfig, Boolean, Boolean) -> Unit,
    onSaveBlocked: (Set<String>) -> Unit
) {
    var focus by rememberSaveable { mutableIntStateOf(PRESETS.first()) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var brk by rememberSaveable { mutableIntStateOf(5) }
    var cycles by rememberSaveable { mutableIntStateOf(1) }
    var strict by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var confirmStrict by rememberSaveable { mutableStateOf(false) }

    val cappedBreak = FocusConfig.capBreak(focus, brk)
    val totalMin = cycles * focus + (cycles - 1).coerceAtLeast(0) * cappedBreak
    val total = WorkTargetUsage.formatMinutes(totalMin)
    val start = {
        onStart(FocusConfig(focus, cappedBreak, cycles, blocked, strict), syncToLaptop, blockSites)
    }

    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { m ->
                Chip("$m min", selected = !custom && focus == m, modifier = Modifier.weight(1f)) { focus = m; custom = false }
            }
            Chip("Custom", selected = custom, modifier = Modifier.weight(1f)) { custom = true }
        }
        if (custom) {
            Spacer(Modifier.height(12.dp))
            ValueSlider(
                label = "Focus length",
                value = focus,
                range = FocusConfig.MIN_FOCUS..FocusConfig.MAX_FOCUS,
                step = FOCUS_STEP,
                format = { "$it min" },
                onValueChange = { focus = it }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (cycles == 1) "One $focus-min block" else "$cycles × $focus min with $cappedBreak-min breaks · $total total",
            color = AmberWarn, fontSize = 12.sp
        )

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = InkBorder)
        Row(
            modifier = Modifier.fillMaxWidth().clickable { pickerOpen = true }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Blocking ${blocked.size} app${if (blocked.size == 1) "" else "s"}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(blockedNames(blocked, labelOf), color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
            Text("Edit", color = GrassGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = InkBorder)

        Spacer(Modifier.height(12.dp))
        ToggleRow("Also focus on laptop", "Blocks laptop apps & sites too", syncToLaptop, onSetSyncToLaptop)
        if (syncToLaptop) {
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                "Block websites too",
                "Makes the sites on your laptop block list (e.g. YouTube) unreachable during the session.",
                blockSites, onSetBlockSites
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (moreOpen) "More options ▴" else "More options ▾  ·  cycles, breaks, strict",
            color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { moreOpen = !moreOpen }.padding(vertical = 6.dp)
        )
        if (moreOpen) {
            Spacer(Modifier.height(8.dp))
            ValueSlider(
                label = "Cycles",
                value = cycles,
                range = 1..FocusConfig.MAX_CYCLES,
                caption = { "Focus blocks in a row" },
                onValueChange = { cycles = it }
            )
            if (cycles > 1) {
                ValueSlider(
                    label = "Break",
                    value = cappedBreak,
                    // A break can't exceed the focus block (FocusConfig.capBreak).
                    range = FocusConfig.MIN_BREAK..focus,
                    step = FOCUS_STEP,
                    format = { "$it min" },
                    caption = { "Apps unblock between blocks" },
                    onValueChange = { brk = it }
                )
            }
            Spacer(Modifier.height(6.dp))
            ToggleRow("Strict mode", "No End button — you're locked in until the session finishes.", strict) { strict = it }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (strict) confirmStrict = true else start() },
            enabled = blocked.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) {
            Text("Start $total focus${if (strict) " · strict" else ""}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (blocked.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick at least one app to block to start",
                color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (pickerOpen) {
        AppPickerDialog(
            apps = apps,
            labelOf = labelOf,
            initiallySelected = blocked,
            lockedPackages = lockedBlocked,
            onDismiss = { pickerOpen = false },
            onConfirm = { onSaveBlocked(it); pickerOpen = false }
        )
    }
    if (confirmStrict) {
        AlertDialog(
            onDismissRequest = { confirmStrict = false },
            title = { Text("Start a strict session?") },
            text = {
                Text(
                    "You'll be locked in for $total. There's no End button — " +
                        "a strict session can't be stopped early."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmStrict = false; start() }) { Text("Start strict", color = AmberWarn) }
            },
            dismissButton = { TextButton(onClick = { confirmStrict = false }) { Text("Cancel") } }
        )
    }
}

/** "Instagram, YouTube, TikTok +2". */
private fun blockedNames(blocked: Set<String>, labelOf: (String) -> String): String {
    if (blocked.isEmpty()) return "Nothing selected"
    val names = blocked.map(labelOf).sortedBy { it.lowercase() }
    val more = names.size - NAMES_SHOWN
    return names.take(NAMES_SHOWN).joinToString(", ") + if (more > 0) " +$more" else ""
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = if (selected) Ink else TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (selected) GrassGreen else Ink)
            .border(1.dp, if (selected) GrassGreen else InkBorder, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp)
    )
}

@Composable
private fun ToggleRow(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(caption, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = GrassGreen, uncheckedTrackColor = InkBorder)
        )
    }
}
