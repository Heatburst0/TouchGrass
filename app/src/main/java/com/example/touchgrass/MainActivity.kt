package com.example.touchgrass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
                    // 2. Retrieve the ViewModel using Hilt's Compose integration
                    val viewModel: DashboardViewModel = hiltViewModel() // Hilt takes over

                    // 3. Render the Dashboard
                    DoomscrollDashboard(viewModel= viewModel)
                }
            }
        }
    }
}
