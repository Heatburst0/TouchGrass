package com.example.touchgrass.presentation.dashboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.touchgrass.formatDuration
import com.example.touchgrass.isAccessibilityEnabled
import com.example.touchgrass.isIgnoringBatteryOptimizations
import com.example.touchgrass.requestIgnoreBatteryOptimizations
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.DangerRedDeep
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun DoomscrollDashboard(viewModel: DashboardViewModel) {
    val stats by viewModel.stats.collectAsState()
    val limit by viewModel.shortsLimit.collectAsState()
    val effectiveLimit by viewModel.effectiveLimit.collectAsState()
    val extraShorts by viewModel.extraShortsToday.collectAsState()
    val penaltyShorts by viewModel.penaltyShortsToday.collectAsState()
    val points by viewModel.pointsBalance.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isServiceActive by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Re-check permissions every time the app returns to the foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceActive = isAccessibilityEnabled(context)
                canDrawOverlays = Settings.canDrawOverlays(context)
                batteryExempt = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(stats.totalTimeMillis)
    val limitReached = stats.totalCount >= effectiveLimit
    val setupIncomplete = !isServiceActive || !canDrawOverlays || !batteryExempt

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // ---- Header ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TouchGrass",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            StatusChip(active = isServiceActive) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Hero progress ring ----
        HeroRing(count = stats.totalCount, limit = effectiveLimit)

        Spacer(Modifier.height(4.dp))

        val remaining = (effectiveLimit - stats.totalCount).coerceAtLeast(0)
        Text(
            text = if (limitReached) "LIMIT REACHED - SHORTS ARE BLOCKED"
            else "$remaining left before lockout",
            color = if (limitReached) DangerRed else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (limitReached) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = if (limitReached) 1.sp else 0.sp
        )

        if (extraShorts > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "includes +$extraShorts earned by reading",
                color = GrassGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (penaltyShorts > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "-$penaltyShorts docked for a missed goal",
                color = DangerRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(24.dp))

        // ---- Stat tiles ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatTile(
                label = "Time wasted",
                value = formatDuration(stats.totalTimeMillis),
                accent = AmberWarn,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Avg / short",
                value = "${stats.averageTimeSeconds}s",
                accent = GrassGreen,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Points",
                value = "$points",
                accent = TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- Daily limit control (persisted via DataStore) ----
        LimitCard(
            limit = limit,
            onLimitChange = { viewModel.updateShortsLimit(it) }
        )

        // ---- Setup / permission cards ----
        if (setupIncomplete) {
            Spacer(Modifier.height(24.dp))
            SectionTitle("Finish setup")
            Spacer(Modifier.height(10.dp))

            if (!isServiceActive) {
                SetupCard(
                    badge = "1",
                    title = "Turn on tracking",
                    subtitle = "Enable the TouchGrass accessibility service",
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!canDrawOverlays) {
                SetupCard(
                    badge = "2",
                    title = "Allow the block screen",
                    subtitle = "Display over other apps permission",
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!batteryExempt) {
                SetupCard(
                    badge = "3",
                    title = "Stop Android from killing tracking",
                    subtitle = "Battery optimization is why tracking turns off randomly",
                    onClick = { requestIgnoreBatteryOptimizations(context) }
                )
            }
        }

        // ---- Reality check ----
        if (totalMinutes > 0) {
            Spacer(Modifier.height(24.dp))
            SectionTitle("Instead, you could have...")
            Spacer(Modifier.height(10.dp))
            RealityRow("Walked ${totalMinutes * 100} steps")
            Spacer(Modifier.height(8.dp))
            RealityRow("Read ${(totalMinutes * 0.5).roundToInt()} pages of a book")
            Spacer(Modifier.height(8.dp))
            RealityRow("Taken ${totalMinutes * 4} deep breaths outside")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Tip: if tracking still dies, open your phone's battery settings, set TouchGrass to \"Unrestricted\", and lock it in the recent-apps screen.",
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun HeroRing(count: Int, limit: Int) {
    val progress = if (limit > 0) (count.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "ringProgress"
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            progress >= 1f -> DangerRed
            progress >= 0.7f -> AmberWarn
            else -> GrassGreen
        },
        animationSpec = tween(500),
        label = "ringColor"
    )
    val animatedCount by animateIntAsState(
        targetValue = count,
        animationSpec = tween(500),
        label = "count"
    )

    Box(modifier = Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 20.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            // Track
            drawArc(
                color = InkBorder,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Progress
            if (animatedProgress > 0.005f) {
                drawArc(
                    color = ringColor,
                    startAngle = 135f,
                    sweepAngle = 270f * animatedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$animatedCount",
                color = TextPrimary,
                fontSize = 68.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "of $limit shorts",
                color = TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StatusChip(active: Boolean, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    val color = if (active) GrassGreen else DangerRed
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (active) pulse else 1f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (active) "Guarding" else "Off",
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(InkElevated)
            .border(1.dp, InkBorder, CardShape)
            .padding(18.dp)
    ) {
        Text(text = value, color = accent, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun LimitCard(limit: Int, onLimitChange: (Int) -> Unit) {
    // Draft state while dragging; committed (and persisted) on release
    var draft by remember(limit) { mutableFloatStateOf(limit.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(InkElevated)
            .border(1.dp, InkBorder, CardShape)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Daily limit",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Blocks Shorts after ${draft.roundToInt()} videos",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundStepButton("-", enabled = limit > 1) { onLimitChange(limit - 1) }
                Text(
                    text = "${draft.roundToInt()}",
                    color = GrassGreen,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(52.dp)
                )
                RoundStepButton("+", enabled = limit < 50) { onLimitChange(limit + 1) }
            }
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onLimitChange(draft.roundToInt()) },
            valueRange = 1f..50f,
            colors = SliderDefaults.colors(
                thumbColor = GrassGreen,
                activeTrackColor = GrassGreen,
                inactiveTrackColor = InkBorder
            )
        )
    }
}

@Composable
private fun RoundStepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) InkBorder else InkBorder.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = if (enabled) TextPrimary else TextSecondary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SetupCard(badge: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(DangerRedDeep)
            .border(1.dp, DangerRed.copy(alpha = 0.35f), CardShape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(DangerRed.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = badge, color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Text(text = ">", color = TextSecondary, fontSize = 20.sp)
    }
}

@Composable
private fun RealityRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(GrassGreen)
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
