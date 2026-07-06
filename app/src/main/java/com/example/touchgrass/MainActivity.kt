package com.example.touchgrass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.touchgrass.presentation.dashboard.DashboardViewModel
import com.example.touchgrass.presentation.dashboard.DoomscrollDashboard
import com.example.touchgrass.ui.theme.TouchGrassTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TouchGrassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: DashboardViewModel = hiltViewModel()
                    DoomscrollDashboard(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the system flipped our service off and we hold WRITE_SECURE_SETTINGS
        // (adb grant), silently flip it back on.
        tryForceEnableAccessibility(this)
    }
}
