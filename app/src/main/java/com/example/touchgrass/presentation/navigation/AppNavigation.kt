package com.example.touchgrass.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.touchgrass.core.data.db.BookEntity
import com.example.touchgrass.features.reading.ui.LibraryScreen
import com.example.touchgrass.features.reading.ui.QuizSessionScreen
import com.example.touchgrass.features.reading.ui.ReaderScreen
import com.example.touchgrass.presentation.dashboard.DoomscrollDashboard
import com.example.touchgrass.features.focus.ui.FocusScreen
import com.example.touchgrass.presentation.goals.GoalsScreen
import com.example.touchgrass.presentation.tools.ToolsHubScreen
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextSecondary

object Routes {
    const val GUARD = "guard"
    const val GOALS = "goals"
    const val FOCUS = "focus"
    const val TOOLS = "tools"
    const val LIBRARY = "library"
    const val READER = "reader/{bookId}"
    const val QUIZ = "quiz/{bookId}"
    fun reader(bookId: Long) = "reader/$bookId"
    fun quiz(bookId: Long) = "quiz/$bookId"
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val BOTTOM_TABS = listOf(
    BottomTab(Routes.GUARD, "Guard", Icons.Filled.Home),
    BottomTab(Routes.GOALS, "Goals", Icons.Filled.CheckCircle),
    BottomTab(Routes.FOCUS, "Focus", Icons.Filled.Lock),
    BottomTab(Routes.TOOLS, "Tools", Icons.Filled.Star)
)

@Composable
fun TouchGrassAppRoot(startRoute: String? = null) {
    val navController = rememberNavController()

    // Deep links from the blocker screen / nudge notifications
    LaunchedEffect(startRoute) {
        if (startRoute != null) navController.navigate(startRoute)
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.GUARD, Routes.GOALS, Routes.FOCUS, Routes.TOOLS)

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = InkElevated) {
                    BOTTOM_TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.GUARD) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = GrassGreen,
                                selectedTextColor = GrassGreen,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = InkBorder
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.GUARD,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.GUARD) {
                DoomscrollDashboard(viewModel = hiltViewModel())
            }
            composable(Routes.GOALS) {
                GoalsScreen()
            }
            composable(Routes.FOCUS) {
                FocusScreen()
            }
            composable(Routes.TOOLS) {
                ToolsHubScreen(onOpenRoute = { navController.navigate(it) })
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(onOpenBook = { item ->
                    navController.navigate(
                        if (item.book.type == BookEntity.TYPE_PHYSICAL) Routes.quiz(item.book.id)
                        else Routes.reader(item.book.id)
                    )
                })
            }
            composable(
                route = Routes.READER,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType })
            ) {
                ReaderScreen()
            }
            composable(
                route = Routes.QUIZ,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType })
            ) {
                QuizSessionScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}
