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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.core.data.db.FocusStats
import com.example.touchgrass.features.focus.insights.RecordItem
import com.example.touchgrass.features.focus.insights.SessionAppUsage
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A social / entertainment app worth blocking during focus, with its last-7-days use. */
data class BlockSuggestion(val packageName: String, val label: String, val weekMillis: Long)

private const val MAX_APP_ROWS = 6
private val recordDateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

// ---------------------------------------------------------------- suggestions

/** "Time sinks this week": distracting apps you used most that focus doesn't block yet. */
@Composable
fun BlockSuggestionsCard(
    items: List<BlockSuggestion>,
    onBlock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    InsightCard {
        Text("SUGGESTED BLOCKS", color = AmberWarn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Time sinks this week", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Social & entertainment apps you used most that focus doesn't block yet.",
            color = TextSecondary, fontSize = 12.sp
        )
        items.forEach { s ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Monogram(s.label)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${fmtDuration(s.weekMillis / 1000)} this week", color = TextSecondary, fontSize = 12.sp)
                }
                Text(
                    "Block",
                    color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, GrassGreen.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .clickable { onBlock(s.packageName) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Not now", color = TextSecondary, fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 4.dp)
        )
    }
}

// ---------------------------------------------------------------- record list

/** "Your record": merged phone + laptop sessions; tap one for its breakdown. */
@Composable
fun RecordSection(stats: FocusStats, items: List<RecordItem>, onOpen: (RecordItem) -> Unit) {
    InsightCard {
        Text("Your record", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RecordStat("${stats.completed}", "successful")
            RecordStat("${stats.total}", "started")
            RecordStat("${stats.focusedMinutes}", "focus min")
        }
        if (items.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("No sessions yet — start one above.", color = TextSecondary, fontSize = 12.sp)
        } else {
            Spacer(Modifier.height(12.dp))
            items.forEach { item ->
                RecordRow(item) { onOpen(item) }
            }
        }
    }
}

@Composable
private fun RecordStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = GrassGreen, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun RecordRow(item: RecordItem, onClick: () -> Unit) {
    val p = item.primary
    val breakdown = Breakdown.of(item)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(recordDateFmt.format(Date(p.startedAt)), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(8.dp))
                    DeviceBadge(item)
                }
                Text(
                    "${p.focusedMin}/${p.plannedFocusMin} min · ${p.cycles} cycle${if (p.cycles == 1) "" else "s"}" +
                        (if (p.strict) " · strict" else "") +
                        (if (p.violations > 0) " · ${p.violations} blocked" else ""),
                    color = TextSecondary, fontSize = 11.sp
                )
            }
            Text(
                if (item.completed) "✓ done" else "ended",
                color = if (item.completed) GrassGreen else AmberWarn,
                fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(6.dp))
            Text("›", color = TextSecondary, fontSize = 18.sp)
        }
        if (breakdown.focusedSec + breakdown.wastedSec > 0) {
            Spacer(Modifier.height(6.dp))
            SplitBar(breakdown.focusedSec, breakdown.wastedSec)
        }
    }
}

@Composable
private fun DeviceBadge(item: RecordItem) {
    val label = when {
        item.phone != null && item.laptop != null -> "Phone + Laptop"
        item.laptop != null -> "Laptop"
        else -> "Phone"
    }
    Text(
        label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Ink)
            .border(1.dp, InkBorder, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun SplitBar(focusedSec: Long, wastedSec: Long) {
    val total = focusedSec + wastedSec
    Row(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(InkBorder)
    ) {
        if (total <= 0) return@Row
        if (focusedSec > 0) {
            Box(Modifier.fillMaxWidth(focusedSec.toFloat() / total).fillMaxHeight().background(GrassGreen))
        }
        if (wastedSec > 0) {
            Box(Modifier.weight(1f).fillMaxHeight().background(AmberWarn))
        }
    }
}

// ---------------------------------------------------------------- detail sheet

/** Breakdown of one tapped record: laptop productive/off-task + phone wasted/other. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailSheet(item: RecordItem, phoneLabel: (String) -> String, onDismiss: () -> Unit) {
    val b = remember(item) { Breakdown.of(item) }
    // Resolve each app label once per sheet, not on every recomposition.
    val labels = remember(item) { mutableMapOf<String, String>() }
    val cachedPhoneLabel: (String) -> String = { pkg -> labels.getOrPut(pkg) { phoneLabel(pkg) } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = InkElevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(recordDateFmt.format(Date(item.startedAt)), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(10.dp))
                DeviceBadge(item)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("${b.focusedSec / 60}m", "focused", GrassGreen)
                Metric(fmtDuration(b.wastedSec), "wasted", AmberWarn)
                Metric("${b.focusPercent}%", "focus", TextPrimary)
            }
            item.laptop?.let {
                SectionTitle("Laptop")
                if (b.laptop.isEmpty) {
                    Muted("No app breakdown was recorded for this laptop session.")
                } else {
                    AppList("Productive", b.laptop.apps, GrassGreen, ::prettyProcessName)
                    AppList("Off-task", b.laptop.offTask, AmberWarn, ::prettyProcessName)
                }
            }
            item.phone?.let {
                SectionTitle("Phone")
                if (b.phone.isEmpty) {
                    Muted("No phone breakdown — turn on Usage access for TouchGrass to see where phone time went.")
                } else {
                    AppList("Time wasted", b.phone.offTask, DangerRed, cachedPhoneLabel)
                    AppList("Other apps", b.phone.apps, TextSecondary, cachedPhoneLabel)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(text, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Muted(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, color = TextSecondary, fontSize = 12.sp)
}

@Composable
private fun Metric(value: String, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = tint, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

/** Up to [MAX_APP_ROWS] apps, longest first, each with a bar relative to the longest. */
@Composable
private fun AppList(title: String, apps: Map<String, Int>, tint: Color, labelOf: (String) -> String) {
    if (apps.isEmpty()) return
    val top = apps.entries.sortedByDescending { it.value }.take(MAX_APP_ROWS)
    val max = top.first().value.coerceAtLeast(1)
    Spacer(Modifier.height(10.dp))
    Text(title, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    top.forEach { (name, secs) ->
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(labelOf(name), color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(fmtDuration(secs.toLong()), color = TextSecondary, fontSize = 12.sp)
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
                    .fillMaxWidth(secs.toFloat() / max)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(tint)
            )
        }
    }
}

// ---------------------------------------------------------------- shared bits

/** Derived numbers for a record row / sheet. */
private data class Breakdown(
    val phone: SessionAppUsage,
    val laptop: SessionAppUsage,
    val focusedSec: Long,
    val wastedSec: Long
) {
    val focusPercent: Int
        get() = if (focusedSec + wastedSec > 0) (focusedSec * 100 / (focusedSec + wastedSec)).toInt() else 100

    companion object {
        fun of(item: RecordItem): Breakdown {
            val phone = item.phone?.let { SessionAppUsage.fromJson(it.appUsageJson) } ?: SessionAppUsage.EMPTY
            val laptop = item.laptop?.let { SessionAppUsage.fromJson(it.appUsageJson) } ?: SessionAppUsage.EMPTY
            val wasted = (phone.offTaskSec + laptop.offTaskSec).toLong()
            // A phone session's focus minutes are served wall-clock time that already
            // contains any time wasted, so subtract it; a laptop-only session's focus
            // minutes are pure on-task time, separate from its off-task time.
            val served = item.primary.focusedMin * 60L
            val focused = if (item.phone != null) (served - wasted).coerceAtLeast(0) else served
            return Breakdown(phone = phone, laptop = laptop, focusedSec = focused, wastedSec = wasted)
        }
    }
}

@Composable
private fun InsightCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(20.dp))
            .padding(20.dp),
        content = content
    )
}

@Composable
private fun Monogram(label: String) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Ink)
            .border(1.dp, InkBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(label.firstOrNull()?.uppercase() ?: "?", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Laptop app names are lowercased process names ("code", "chrome") — make them readable. */
private fun prettyProcessName(name: String): String =
    name.removeSuffix(".exe").replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** 45s · 12m · 1h 4m */
private fun fmtDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${totalSec}s"
    }
}
