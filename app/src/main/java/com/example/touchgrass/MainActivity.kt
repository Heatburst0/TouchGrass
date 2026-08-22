package com.example.touchgrass

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.touchgrass.core.goals.GoalEngine
import com.example.touchgrass.presentation.navigation.TouchGrassAppRoot
import com.example.touchgrass.ui.theme.TouchGrassTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var goalEngine: GoalEngine

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        val startRoute = intent.getStringExtra(EXTRA_START_ROUTE)
        setContent {
            TouchGrassTheme {
                TouchGrassAppRoot(startRoute = startRoute)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the system flipped our service off and we hold WRITE_SECURE_SETTINGS
        // (adb grant), silently flip it back on.
        tryForceEnableAccessibility(this)
        // Settle anything that elapsed while we were away — one-shot deadlines and
        // recurring-goal period boundaries — so it's current the instant you open.
        lifecycleScope.launch {
            goalEngine.settleOverdue()
            goalEngine.settleRecurring()
        }
    }

    /** Android 13+ needs a runtime grant to post notifications; ask once. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_START_ROUTE = "start_route"
    }
}
