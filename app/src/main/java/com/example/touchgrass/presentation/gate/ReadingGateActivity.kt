package com.example.touchgrass.presentation.gate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.features.reading.ui.ReaderScreen
import com.example.touchgrass.ui.theme.DangerRedDeep
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import com.example.touchgrass.ui.theme.TouchGrassTheme
import dagger.hilt.android.AndroidEntryPoint
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TouchGrassTheme {
                // No backing out to the feed
                BackHandler(enabled = true) {}

                val pending by settings.readingGatePending.collectAsState(initial = true)
                LaunchedEffect(pending) {
                    if (!pending) finish() // page verified — unlock
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
