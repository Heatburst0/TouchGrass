package com.example.touchgrass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        // Settle any commitments whose deadline passed while we were away.
        lifecycleScope.launch { goalEngine.settleOverdue() }
    }

    companion object {
        const val EXTRA_START_ROUTE = "start_route"
    }
}
