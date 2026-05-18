package com.example.touchgrass.presentation.dashboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.touchgrass.isAccessibilityEnabled
import java.util.concurrent.TimeUnit

@Composable
fun DoomscrollDashboard(viewModel: DashboardViewModel) {
    val stats by viewModel.stats.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // States to hold permission status
    var isServiceActive by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Re-check permissions every time the user brings the app back to the foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceActive = isAccessibilityEnabled(context)
                canDrawOverlays = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Convert millis to readable formats
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(stats.totalTimeMillis)
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(stats.totalTimeMillis) % 60

    // Creative Data Context
    val stepsMissed = (totalMinutes * 100).toInt() // Approx 100 steps per min of walking
    val pagesNotRead = (totalMinutes * 0.5).toInt() // Approx 2 mins per book page

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "TODAY'S REALITY CHECK",
            color = Color(0xFF4CAF50),
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 40.dp, bottom = 24.dp)
        )

        // --- 1. ACCESSIBILITY PERMISSION CARD ---
        val accessibilityText = if (isServiceActive) "Tracking Active. You are being watched." else "Tracking Disabled. Tap to Enable."
        PermissionStatusCard(
            isActive = isServiceActive,
            text = accessibilityText,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        // --- 2. OVERLAY PERMISSION CARD ---
        if (!canDrawOverlays) {
            Spacer(modifier = Modifier.height(12.dp))
            PermissionStatusCard(
                isActive = false,
                text = "Missing Display Permission. Tap to Enable.",
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- 3. DAILY LIMIT SLIDER ---
        // (Defaulting to 5 here. Ideally, this hooks into a DataStore via your ViewModel)
        var limit by remember { mutableFloatStateOf(5f) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Daily Shorts Limit:", color = Color.Gray, fontSize = 14.sp)
                Text("${limit.toInt()}", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Slider(
                value = limit,
                onValueChange = { newLimit ->
                    limit = newLimit
                    // TODO: Add a function in your ViewModel to update this value in the TrackerManager
                    // viewModel.updateShortsLimit(newLimit.toInt())
                },
                valueRange = 1f..50f,
                steps = 48,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF4CAF50),
                    activeTrackColor = Color(0xFF4CAF50)
                )
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Main Stat Card
        StatCard(
            title = "Shorts Devoured",
            value = "${stats.totalCount}",
            highlightColor = Color(0xFFFF5252) // Warning Red
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                title = "Time Vanished",
                value = "${totalMinutes}m ${totalSeconds}s",
                modifier = Modifier.weight(1f),
                highlightColor = Color(0xFFFFB74D)
            )
            StatCard(
                title = "Avg. Per Short",
                value = "${stats.averageTimeSeconds}s",
                modifier = Modifier.weight(1f),
                highlightColor = Color(0xFF64B5F6)
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // The Creative "Could Have Done" Section
        if (totalMinutes > 0) {
            Text(
                text = "Instead of scrolling, you could have...",
                color = Color.LightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            AlternativeActionCard("Walked $stepsMissed steps \uD83D\uDC5F")
            Spacer(modifier = Modifier.height(8.dp))
            AlternativeActionCard("Read $pagesNotRead pages of a book \uD83D\uDCDA")
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    highlightColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E1E1E))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                color = highlightColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = title,
                color = Color.Gray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Updated to accept 'text' parameter
@Composable
fun PermissionStatusCard(isActive: Boolean, text: String, onClick: () -> Unit) {
    val backgroundColor = if (isActive) Color(0xFF1E3320) else Color(0xFF331E1E)
    val textColor = if (isActive) Color(0xFF81C784) else Color(0xFFE57373)

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
fun AlternativeActionCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2C2C2C))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}