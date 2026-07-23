package com.example.touchgrass.presentation.tools

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.data.SettingsRepository
import com.example.touchgrass.core.rewards.RewardsManager
import com.example.touchgrass.features.reading.ui.PointsChip
import com.example.touchgrass.hasUsageAccess
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Registry of productivity tools. Adding a tool = one entry here plus its route.
 * Keeps the hub screen dumb and the toolset scalable.
 */
data class ProductivityTool(
    val id: String,
    val title: String,
    val description: String,
    val route: String? = null,
    val comingSoon: Boolean = false
)

val PRODUCTIVITY_TOOLS = listOf(
    ProductivityTool(
        id = "reading",
        title = "Book reading",
        description = "Read verified pages, earn points, buy your shorts back",
        route = "library"
    ),
    ProductivityTool(
        id = "nudges",
        title = "Screen-time balance",
        description = "A reading nudge for every 3h of YouTube / Instagram / Netflix"
    ),
    ProductivityTool(
        id = "focus",
        title = "Focus sessions",
        description = "Deep-work timer that hard-blocks doomscroll apps",
        comingSoon = true
    ),
    ProductivityTool(
        id = "winddown",
        title = "Wind-down",
        description = "Evening cutoff with a gentler, grayscale phone",
        comingSoon = true
    )
)

@HiltViewModel
class ToolsHubViewModel @Inject constructor(
    rewards: RewardsManager,
    private val settings: SettingsRepository
) : ViewModel() {
    val points: StateFlow<Int> = rewards.pointsBalance

    val forceReadEnabled: StateFlow<Boolean> = settings.readingGateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val nudgeIntervalMinutes: StateFlow<Int> = settings.nudgeIntervalMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_NUDGE_MINUTES)

    fun setForceRead(enabled: Boolean) {
        viewModelScope.launch {
            settings.setReadingGateEnabled(enabled)
            // Turning it off also disarms any gate currently owed
            if (!enabled) settings.setReadingGatePending(false)
        }
    }

    fun setNudgeInterval(minutes: Int) {
        viewModelScope.launch { settings.setNudgeIntervalMinutes(minutes) }
    }
}

/** Interval choices offered in the UI: label -> minutes of watch time. */
private val NUDGE_INTERVAL_CHOICES = listOf("30m" to 30, "1h" to 60, "2h" to 120, "3h" to 180)

@Composable
fun ToolsHubScreen(
    onOpenRoute: (String) -> Unit,
    viewModel: ToolsHubViewModel = hiltViewModel()
) {
    val points by viewModel.points.collectAsState()
    val forceRead by viewModel.forceReadEnabled.collectAsState()
    val nudgeInterval by viewModel.nudgeIntervalMinutes.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var usageAccess by remember { mutableStateOf(hasUsageAccess(context)) }
    var notificationsOn by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageAccess = hasUsageAccess(context)
                notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsOn = granted }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Tools",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Trade scroll time for real life",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
                PointsChip(points)
            }
            Spacer(Modifier.height(8.dp))
        }

        items(PRODUCTIVITY_TOOLS, key = { it.id }) { tool ->
            ToolCard(
                tool = tool,
                onClick = { tool.route?.let(onOpenRoute) },
                extraContent = if (tool.id == "nudges") {
                    {
                        Spacer(Modifier.height(12.dp))
                        // How much watch time triggers a nudge / force-read
                        Text(
                            text = "Remind me after every",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NUDGE_INTERVAL_CHOICES.forEach { (label, minutes) ->
                                val selected = nudgeInterval == minutes
                                Text(
                                    text = label,
                                    color = if (selected) Ink else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selected) GrassGreen else Ink)
                                        .border(
                                            1.dp,
                                            if (selected) GrassGreen else InkBorder,
                                            RoundedCornerShape(50)
                                        )
                                        .clickable { viewModel.setNudgeInterval(minutes) }
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Force-read mode: PDF takes over the screen instead of a notification
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Ink)
                                .border(1.dp, InkBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Force-read mode",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Your PDF takes over the screen; watching stays blocked until you verify 1 page",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = forceRead,
                                onCheckedChange = viewModel::setForceRead,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Ink,
                                    checkedTrackColor = GrassGreen,
                                    uncheckedThumbColor = TextSecondary,
                                    uncheckedTrackColor = InkBorder
                                )
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        PermissionRow(
                            label = "Usage access",
                            granted = usageAccess,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        PermissionRow(
                            label = "Notifications",
                            granted = notificationsOn,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    )
                                }
                            }
                        )
                    }
                } else null
            )
        }
    }
}

@Composable
private fun ToolCard(
    tool: ProductivityTool,
    onClick: () -> Unit,
    extraContent: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (tool.comingSoon) InkElevated.copy(alpha = 0.55f) else InkElevated)
            .border(1.dp, InkBorder, shape)
            .clickable(enabled = tool.route != null, onClick = onClick)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tool.title,
                color = if (tool.comingSoon) TextSecondary else TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            when {
                tool.comingSoon -> Tag("SOON", TextSecondary)
                tool.route != null -> Text(text = ">", color = TextSecondary, fontSize = 18.sp)
                else -> Tag("ACTIVE", GrassGreen)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = tool.description,
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        extraContent?.invoke()
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    val color = if (granted) GrassGreen else DangerRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink)
            .border(1.dp, InkBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = !granted, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextPrimary, fontSize = 13.sp)
        Text(
            text = if (granted) "Granted" else "Grant",
            color = if (granted) color else AmberWarn,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
