package com.example.touchgrass.features.weeklyplan.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.features.weeklyplan.WeeklyPlanState
import com.example.touchgrass.ui.theme.AmberWarn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE, HH:mm")

/** "Sat, 00:00" in the device zone. */
internal fun formatLockTime(instant: Instant): String = DAY_TIME.format(instant.atZone(ZoneId.systemDefault()))

/**
 * Small pill shown next to any setting the weekly plan controls; tapping opens the hub.
 * Renders nothing unless the plan is committed and currently locked.
 */
@Composable
fun WeeklyLockBadge(state: WeeklyPlanState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.locked) return
    val until = state.window.lockedUntil
        ?.let { " until ${DAY.format(it.atZone(ZoneId.systemDefault()))}" }.orEmpty()
    val shape = RoundedCornerShape(50)
    Text(
        text = "🔒 Weekly plan · locked$until ›",
        color = AmberWarn,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(shape)
            .background(AmberWarn.copy(alpha = 0.12f))
            .border(1.dp, AmberWarn.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/** Shows a refused-change message once, then clears it. */
@Composable
fun LockMessageToast(message: String?, onShown: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            onShown()
        }
    }
}
