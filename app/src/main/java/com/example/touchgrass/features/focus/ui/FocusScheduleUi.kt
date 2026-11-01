package com.example.touchgrass.features.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import com.example.touchgrass.core.screentime.ScreenTimeNudger
import com.example.touchgrass.features.focus.FocusConfig
import com.example.touchgrass.features.focus.FocusSchedule
import com.example.touchgrass.features.focus.FocusScheduleScheduler
import com.example.touchgrass.ui.components.StepperRow
import com.example.touchgrass.ui.components.ValueSlider
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import java.time.DayOfWeek

// ---- recurring schedules ----

internal const val FOCUS_STEP = 5

@Composable
internal fun ScheduleSection(
    schedules: List<FocusSchedule>,
    blocked: Set<String>,
    onAdd: (FocusSchedule) -> Unit,
    onDelete: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FocusSchedule?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Schedules", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                "+ Add",
                color = GrassGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { editing = null; showDialog = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        if (schedules.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("No schedules yet — auto-start focus at a set time, daily or on chosen days.",
                color = TextSecondary, fontSize = 12.sp)
        } else {
            schedules.forEach { s ->
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { editing = s; showDialog = true }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.timeLabel}  ·  ${s.daysLabel}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${s.totalMinutes}m (${s.cycles}×${s.focusBlockMin}m)" +
                                (if (s.reminders.isNotEmpty()) " · reminders on" else ""),
                            color = TextSecondary, fontSize = 11.sp
                        )
                    }
                    Text("Edit", color = GrassGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { editing = s; showDialog = true }.padding(6.dp))
                    Text("Remove", color = DangerRed, fontSize = 12.sp,
                        modifier = Modifier.clickable { onDelete(s.id) }.padding(6.dp))
                }
            }
        }
    }
    if (showDialog) {
        ScheduleDialog(
            editing = editing,
            blocked = blocked,
            onDismiss = { showDialog = false },
            onConfirm = { onAdd(it); showDialog = false }
        )
    }
}

@Composable
private fun ScheduleDialog(
    editing: FocusSchedule?,
    blocked: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (FocusSchedule) -> Unit
) {
    var hour by remember { mutableIntStateOf(editing?.hour ?: 9) }
    var minute by remember { mutableIntStateOf(editing?.minute ?: 0) }
    var everyDay by remember { mutableStateOf(editing?.days == null) }
    var days by remember { mutableStateOf(editing?.days ?: FocusSchedule.WEEKDAYS) }
    var focus by remember { mutableIntStateOf(editing?.focusBlockMin ?: 25) }
    var brk by remember { mutableIntStateOf(editing?.breakMin ?: 5) }
    var cycles by remember { mutableIntStateOf(editing?.cycles ?: 4) }
    var reminders by remember { mutableStateOf(editing?.reminders ?: setOf(5)) }

    val cappedBreak = FocusConfig.capBreak(focus, brk)
    val total = cycles * focus + (cycles - 1).coerceAtLeast(0) * cappedBreak
    val canConfirm = everyDay || days.isNotEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(InkElevated)
                .border(1.dp, InkBorder, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Text(if (editing == null) "New schedule" else "Edit schedule", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            StepperRow("Hour", "%02d".format(hour), { hour = (hour + 23) % 24 }) { hour = (hour + 1) % 24 }
            Spacer(Modifier.height(10.dp))
            StepperRow("Minute", "%02d".format(minute), { minute = (minute + 55) % 60 }) { minute = (minute + 5) % 60 }
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Every day", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = everyDay,
                    onCheckedChange = { everyDay = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = GrassGreen, uncheckedTrackColor = InkBorder)
                )
            }
            if (!everyDay) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DayOfWeek.values().forEach { d ->
                        val sel = d in days
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (sel) GrassGreen else Ink)
                                .border(1.dp, if (sel) GrassGreen else InkBorder, CircleShape)
                                .clickable { days = if (sel) days - d else days + d },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(d.name.take(1), color = if (sel) Ink else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            ValueSlider(
                label = "Focus block",
                value = focus,
                range = FocusConfig.MIN_FOCUS..FocusConfig.MAX_FOCUS,
                step = FOCUS_STEP,
                format = { "$it min" },
                onValueChange = { focus = it }
            )
            Spacer(Modifier.height(6.dp))
            ValueSlider(
                label = "Break",
                value = cappedBreak,
                // A break can't exceed the focus block (FocusConfig.capBreak).
                range = FocusConfig.MIN_BREAK..focus,
                step = FOCUS_STEP,
                format = { "$it min" },
                onValueChange = { brk = it }
            )
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(10.dp))
            StepperRow("Cycles", "$cycles", { cycles = (cycles - 1).coerceAtLeast(1) }) { cycles = (cycles + 1).coerceAtMost(FocusConfig.MAX_CYCLES) }

            Spacer(Modifier.height(10.dp))
            Text("Total $total min per run", color = AmberWarn, fontSize = 12.sp)

            Spacer(Modifier.height(14.dp))
            Text("Remind me before", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusScheduleScheduler.REMINDER_OFFSETS.forEach { m ->
                    val sel = m in reminders
                    val label = when (m) { 60 -> "1h"; else -> "${m}m" }
                    Text(
                        label,
                        color = if (sel) Ink else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (sel) GrassGreen else Ink)
                            .border(1.dp, if (sel) GrassGreen else InkBorder, RoundedCornerShape(50))
                            .clickable { reminders = if (sel) reminders - m else reminders + m }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Cancel", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onDismiss() }.padding(12.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (editing == null) "Add" else "Save", color = if (canConfirm) GrassGreen else TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = canConfirm) {
                        onConfirm(
                            FocusSchedule(
                                id = editing?.id ?: "",
                                title = "Focus",
                                days = if (everyDay) null else days,
                                hour = hour,
                                minute = minute,
                                focusBlockMin = focus,
                                breakMin = cappedBreak,
                                cycles = cycles,
                                blockedPackages = blocked.ifEmpty { ScreenTimeNudger.WATCHED_PACKAGES },
                                reminders = reminders,
                                enabled = true
                            )
                        )
                    }.padding(12.dp))
            }
        }
    }
}
