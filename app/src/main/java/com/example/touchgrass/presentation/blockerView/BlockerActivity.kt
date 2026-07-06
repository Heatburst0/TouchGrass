package com.example.touchgrass.presentation.blockerView

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.core.manager.ShortsStats
import com.example.touchgrass.core.manager.ShortsTrackerManager
import com.example.touchgrass.formatDuration
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import com.example.touchgrass.ui.theme.TouchGrassTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BlockerActivity : ComponentActivity() {

    @Inject
    lateinit var trackerManager: ShortsTrackerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TouchGrassTheme {
                // Hard block: swallow back presses so they can't swipe back to Shorts
                BackHandler(enabled = true) {}

                val stats by trackerManager.stats.collectAsState()
                val limit by trackerManager.shortsLimit.collectAsState()

                BlockerScreen(
                    stats = stats,
                    limit = limit,
                    onExit = { forceHomeAndExit() }
                )
            }
        }
    }

    private fun forceHomeAndExit() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}

@Composable
private fun BlockerScreen(stats: ShortsStats, limit: Int, onExit: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF250808), Ink))
            )
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pulsing stop badge
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(DangerRed.copy(alpha = 0.15f))
                .border(3.dp, DangerRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "STOP", color = DangerRed, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text = "That's enough.",
            color = TextPrimary,
            fontSize = 36.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "You hit your limit of $limit shorts.\nThe feed can wait. Your life can't.",
            color = TextSecondary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(36.dp))

        // Damage report
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DamagePill(
                value = "${stats.totalCount}",
                label = "shorts watched",
                modifier = Modifier.weight(1f)
            )
            DamagePill(
                value = formatDuration(stats.totalTimeMillis),
                label = "of your day gone",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(44.dp))

        Button(
            onClick = onExit,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GrassGreen,
                contentColor = Ink
            )
        ) {
            Text(text = "Go touch grass", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Your limit resets at midnight.",
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun DamagePill(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value, color = DangerRed, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
    }
}
