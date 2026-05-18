package com.example.touchgrass.presentation.blockerView


import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.touchgrass.ui.theme.TouchGrassTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BlockerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TouchGrassTheme {
                // 1. HARD BLOCK: Intercept and ignore back button presses
                BackHandler(enabled = true) {
                    // Do nothing. They cannot swipe back to YouTube.
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🛑\nLIMIT REACHED",
                        fontSize = 48.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    Text(
                        text = "You've scrolled enough for now.\nTime to touch some grass.",
                        color = Color.LightGray,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 48.dp)
                    )

                    Button(
                        onClick = { forceHomeAndExit() },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("I'm going outside 🌳", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    private fun forceHomeAndExit() {
        // Fire an intent to the Android Launcher (Home Screen)
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)

        // Kill this blocker screen so they don't see it when they open other apps
        finish()
    }
}