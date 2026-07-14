package com.example.touchgrass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.touchgrass.presentation.navigation.TouchGrassAppRoot
import com.example.touchgrass.ui.theme.TouchGrassTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
    }

    companion object {
        const val EXTRA_START_ROUTE = "start_route"
    }
}
