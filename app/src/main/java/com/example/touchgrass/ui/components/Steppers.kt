package com.example.touchgrass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

/** Round "−" / "+" button used by [ValueSlider] and [StepperRow]. */
@Composable
fun RoundStepButton(symbol: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) InkBorder else InkBorder.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.5f),
            fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
    }
}

/** Label on the left, "− value +" on the right — for small discrete values (hour, cycles). */
@Composable
fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundStepButton("−", onClick = onMinus)
            Text(
                value, color = GrassGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)
            )
            RoundStepButton("+", onClick = onPlus)
        }
    }
}
