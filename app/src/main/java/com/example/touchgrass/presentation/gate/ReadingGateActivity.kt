package com.example.touchgrass.presentation.gate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.reading.ui.ReaderScreen
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRedDeep
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import com.example.touchgrass.ui.theme.TouchGrassTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Force-read gate: launched by ScreenTimeNudger over Netflix/YouTube/Instagram
 * when the watch-time threshold hits and force-read mode is on. Hosts the real
 * PDF reader; the gate clears (and this closes) only when one page is
 * quiz-verified, which RewardsManager signals by flipping readingGatePending.
 *
 * The "bookId" intent extra feeds ReaderViewModel's SavedStateHandle, exactly
 * like the nav-args do on the normal reader route.
 */
@AndroidEntryPoint
class ReadingGateActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var rewards: RewardsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TouchGrassTheme {
                // No backing out to the feed
                BackHandler(enabled = true) {}

                val pending by settings.readingGatePending.collectAsState(initial = true)
                val points by rewards.pointsBalance.collectAsState()
                LaunchedEffect(pending) {
                    if (!pending) finish() // page verified (or skipped) — unlock
                }

                Column(modifier = Modifier.fillMaxSize().background(Ink)) {
                    // Lock banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DangerRedDeep)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Watching is locked",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Read 1 page (20s) and pass the quiz to continue",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        // Spend points to skip this forced read (if you can afford it)
                        val canAfford = points >= RewardsManager.SKIP_GATE_COST
                        Text(
                            text = "Skip · ${RewardsManager.SKIP_GATE_COST} pts",
                            color = if (canAfford) Ink else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (canAfford) AmberWarn else DangerRedDeep)
                                .border(1.dp, AmberWarn.copy(alpha = 0.4f), RoundedCornerShape(50))
                                .clickable(enabled = canAfford) { skipWithPoints() }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Leave",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { leaveToHome() }
                                .padding(8.dp)
                        )
                    }

                    // The actual reader (bookId arrives via intent extras)
                    Box(modifier = Modifier.weight(1f)) {
                        ReaderScreen()
                    }
                }
            }
        }
    }

    /** Spend points to dismiss this forced read; on success the gate flag flips
     *  and the LaunchedEffect closes this screen. */
    private fun skipWithPoints() {
        lifecycleScope.launch { rewards.redeemSkipReadingGate() }
    }

    /** Escape hatch: go home instead — but the gate stays armed, so watch
     *  apps will keep bouncing back here until a page is verified. */
    private fun leaveToHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        finish()
    }
}
