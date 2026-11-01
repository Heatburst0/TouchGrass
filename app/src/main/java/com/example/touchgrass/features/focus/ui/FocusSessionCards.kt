package com.example.touchgrass.features.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

// Cards for a session in progress / just finished, and the accessibility gate.

@Composable
internal fun RunningCard(
    title: String,
    subtitle: String,
    bigTime: String,
    caption: String,
    accent: androidx.compose.ui.graphics.Color,
    violations: Int,
    strict: Boolean,
    onEnd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title.uppercase(), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(bigTime, color = TextPrimary, fontSize = 60.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(caption, color = TextSecondary, fontSize = 12.sp)
        if (violations > 0) {
            Spacer(Modifier.height(10.dp))
            Text("$violations distraction${if (violations == 1) "" else "s"} blocked", color = DangerRed, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        if (strict) {
            Text("🔒 Strict — locked in until the session ends", color = AmberWarn, fontSize = 12.sp, textAlign = TextAlign.Center)
        } else {
            Button(
                onClick = onEnd,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InkBorder, contentColor = TextPrimary)
            ) {
                Text("End session", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun DoneCard(violations: Int, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, GrassGreen.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SESSION COMPLETE", color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (violations == 0) "Clean run — no distractions." else "$violations distraction${if (violations == 1) "" else "s"} blocked.",
            color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) {
            Text("New session", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun mmss(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---- accessibility gate ----

@Composable
internal fun AccessibilityWarning(onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DangerRed.copy(alpha = 0.12f))
            .border(1.dp, DangerRed.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("Accessibility is off", color = DangerRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Focus sessions can't block apps until TouchGrass's accessibility service is on. " +
                "Scheduled sessions still start, but nothing will be blocked.",
            color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onEnable,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Ink)
        ) { Text("Turn on accessibility", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}
