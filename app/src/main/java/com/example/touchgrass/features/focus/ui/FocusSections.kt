package com.example.touchgrass.features.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.core.remote.LaptopStatus
import com.example.touchgrass.features.worktargets.WorkTargetUsage
import com.example.touchgrass.features.worktargets.ui.TargetRow
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

private const val QUOTA_PREVIEW = 3

/** A labelled block of the Focus tab: small uppercase label, one plain-English line, content. */
@Composable
internal fun FocusSection(
    label: String,
    explanation: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(), color = GrassGreen, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(2.dp))
        Text(explanation, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(InkElevated)
            .border(1.dp, InkBorder, shape)
            .padding(18.dp),
        content = content
    )
}

/** Schedules + work targets: the two ways focus starts without you pressing Start. */
@Composable
internal fun AutomaticFocusTiles(
    scheduleCount: Int,
    schedulesOpen: Boolean,
    autoFocusTargets: Int,
    onToggleSchedules: () -> Unit,
    onOpenWorkTargets: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(
            title = "Schedules",
            caption = "Start at set times",
            count = if (scheduleCount == 0) "None yet" else "$scheduleCount active",
            action = if (schedulesOpen) "Hide ▴" else "View ▾",
            selected = schedulesOpen,
            onClick = onToggleSchedules,
            modifier = Modifier.weight(1f)
        )
        Tile(
            title = "Work targets",
            caption = "Start when you open a project",
            count = if (autoFocusTargets == 0) "None yet" else "$autoFocusTargets auto-focus",
            action = "Open ›",
            selected = false,
            onClick = onOpenWorkTargets,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Tile(
    title: String,
    caption: String,
    count: String,
    action: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(InkElevated)
            .border(1.dp, if (selected) GrassGreen.copy(alpha = 0.6f) else InkBorder, shape)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(caption, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(10.dp))
        Text(count, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(action, color = GrassGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** This week's progress on quota'd work targets. */
@Composable
internal fun WeeklyQuotasCard(rows: List<TargetRow>, onManage: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onManage)) {
        if (rows.isEmpty()) {
            Text("No quotas yet", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "e.g. at least 10 h a week in TouchGrass on VS Code.",
                color = TextSecondary, fontSize = 12.sp
            )
        }
        rows.take(QUOTA_PREVIEW).forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(row.target.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "${WorkTargetUsage.formatMinutes(row.weekMin)} / ${WorkTargetUsage.formatMinutes(row.quotaMin)}",
                    color = if (row.weekMin >= row.quotaMin) GrassGreen else TextSecondary, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (row.weekMin.toFloat() / row.quotaMin).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = GrassGreen,
                trackColor = InkBorder
            )
        }
        if (rows.size > QUOTA_PREVIEW) {
            Spacer(Modifier.height(8.dp))
            Text("+${rows.size - QUOTA_PREVIEW} more", color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (rows.isEmpty()) "Set a quota ›" else "Manage ›",
            color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

/** Agent status + the laptop rules link. */
@Composable
internal fun LaptopCard(status: LaptopStatus, onOpenLaptopRules: () -> Unit) {
    val (dot, text) = when (status) {
        LaptopStatus.Online -> GrassGreen to "Agent online"
        is LaptopStatus.LastSeen -> AmberWarn to "Agent last seen ${LaptopStatus.formatAgo(status.ago)} ago"
        LaptopStatus.NotConnected -> TextSecondary to "No laptop connected — set up the agent"
        LaptopStatus.Unknown -> TextSecondary to "Laptop status unavailable — sign in on Tools → Account & sync"
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Text(text, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (status == LaptopStatus.NotConnected) {
            Text(
                "Install and run touchgrass-agent on your computer, signed in to the same account.",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = InkBorder)
        LinkRow("Laptop rules", "Which laptop apps count as work or get blocked", onOpenLaptopRules)
    }
}

@Composable
private fun LinkRow(title: String, caption: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(caption, color = TextSecondary, fontSize = 12.sp)
        }
        Text("›", color = GrassGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
