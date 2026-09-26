package com.example.touchgrass.presentation.laptop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.remote.DeviceAppsRepository
import com.example.touchgrass.core.remote.FocusPolicyRepository
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How a focus session treats a laptop app. Cycles in this order on tap. */
enum class AppRole { IGNORE, TRACK, BLOCK, FORCE_QUIT }

/** A laptop app plus its current role, merged from discovery + saved policy. */
data class LaptopApp(val name: String, val displayName: String, val role: AppRole)

@HiltViewModel
class LaptopRulesViewModel @Inject constructor(
    private val policyRepo: FocusPolicyRepository,
    private val deviceApps: DeviceAppsRepository
) : ViewModel() {

    val isConfigured: Boolean = policyRepo.isConfigured

    val policy = policyRepo.policy

    /** Discovered apps unioned with any app already in a policy list, tagged with its role. */
    val apps: StateFlow<List<LaptopApp>> =
        combine(policyRepo.policy, deviceApps.apps) { policy, discovered ->
            val names = LinkedHashSet<String>()
            discovered.forEach { names.add(it.name) }
            names.addAll(policy.allowedApps)
            names.addAll(policy.blockedApps)
            names.addAll(policy.forceQuitApps)
            val display = discovered.associate { it.name to it.displayName }
            names.map { n ->
                val role = when (n) {
                    in policy.forceQuitApps -> AppRole.FORCE_QUIT
                    in policy.blockedApps -> AppRole.BLOCK
                    in policy.allowedApps -> AppRole.TRACK
                    else -> AppRole.IGNORE
                }
                LaptopApp(n, display[n] ?: n, role)
            }.sortedBy { it.displayName.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (isConfigured) viewModelScope.launch {
            policyRepo.refresh()
            deviceApps.refresh()
        }
    }

    /** Advance an app to its next role and persist by rewriting the policy lists. */
    fun cycleRole(app: LaptopApp) {
        val next = when (app.role) {
            AppRole.IGNORE -> AppRole.TRACK
            AppRole.TRACK -> AppRole.BLOCK
            AppRole.BLOCK -> AppRole.FORCE_QUIT
            AppRole.FORCE_QUIT -> AppRole.IGNORE
        }
        val p = policyRepo.policy.value
        val allowed = p.allowedApps - app.name
        val blocked = p.blockedApps - app.name
        val forceQuit = p.forceQuitApps - app.name
        val updated = when (next) {
            AppRole.TRACK -> p.copy(allowedApps = allowed + app.name, blockedApps = blocked, forceQuitApps = forceQuit)
            AppRole.BLOCK -> p.copy(allowedApps = allowed, blockedApps = blocked + app.name, forceQuitApps = forceQuit)
            AppRole.FORCE_QUIT -> p.copy(allowedApps = allowed, blockedApps = blocked, forceQuitApps = forceQuit + app.name)
            AppRole.IGNORE -> p.copy(allowedApps = allowed, blockedApps = blocked, forceQuitApps = forceQuit)
        }
        policyRepo.update(updated)
    }

    fun addSite(raw: String) {
        val v = raw.trim().lowercase()
        val p = policyRepo.policy.value
        if (v.isNotEmpty() && v !in p.blockedSites) policyRepo.update(p.copy(blockedSites = p.blockedSites + v))
    }

    fun removeSite(site: String) {
        val p = policyRepo.policy.value
        policyRepo.update(p.copy(blockedSites = p.blockedSites - site))
    }
}

@Composable
fun LaptopRulesScreen(viewModel: LaptopRulesViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsState()
    val policy by viewModel.policy.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Laptop focus rules", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Apps your laptop agent has seen. Tap the pill to set how focus sessions treat each one.",
            color = TextSecondary, fontSize = 13.sp
        )

        if (!viewModel.isConfigured) {
            RuleCard {
                Text("Sign in first", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Sign in on Tools → Account & sync to manage laptop rules.", color = TextSecondary, fontSize = 12.sp)
            }
            return@Column
        }

        Text("Discovered apps (${apps.size})", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

        if (apps.isEmpty()) {
            RuleCard {
                Text("No apps yet", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Run the laptop agent (touchgrass-agent run) and switch between apps — they'll show up here within ~20s.",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
        } else {
            RuleCard {
                apps.forEachIndexed { i, app ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    AppRow(app) { viewModel.cycleRole(app) }
                }
            }
            RoleLegend()
        }

        Spacer(Modifier.height(8.dp))
        SitesSection(
            sites = policy.blockedSites,
            onAdd = viewModel::addSite,
            onRemove = viewModel::removeSite
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AppRow(app: LaptopApp, onCycle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Ink)
                .border(1.dp, InkBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.displayName.firstOrNull()?.uppercase() ?: "?",
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(app.displayName, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(8.dp))
        RolePill(app.role, onCycle)
    }
}

@Composable
private fun RolePill(role: AppRole, onClick: () -> Unit) {
    val (label, tint) = when (role) {
        AppRole.TRACK -> "Track" to GrassGreen
        AppRole.BLOCK -> "Block" to AmberWarn
        AppRole.FORCE_QUIT -> "Force-quit" to DangerRed
        AppRole.IGNORE -> "Ignore" to TextSecondary
    }
    val bg = if (role == AppRole.IGNORE) Color.Transparent else tint.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, tint.copy(alpha = if (role == AppRole.IGNORE) 0.5f else 0.6f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoleLegend() {
    Text(
        "Track = counts as focus · Block = minimized during focus · Force-quit = closed on sight · Ignore = untracked",
        color = TextSecondary, fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SitesSection(sites: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var entry by remember { mutableStateOf("") }
    RuleCard {
        Text("Blocked sites", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text("Null-routed in every browser during focus (agent must run as admin)", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        if (sites.isEmpty()) {
            Text("None", color = TextSecondary, fontSize = 12.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sites.forEach { site ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Ink)
                            .border(1.dp, InkBorder, RoundedCornerShape(50))
                            .clickable { onRemove(site) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(site, color = TextPrimary, fontSize = 13.sp)
                        Spacer(Modifier.size(6.dp))
                        Text("×", color = DangerRed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it },
                singleLine = true,
                placeholder = { Text("Add domain (e.g. reddit.com)", color = TextSecondary, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                keyboardActions = KeyboardActions(onDone = { onAdd(entry); entry = "" }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = GrassGreen,
                    unfocusedBorderColor = InkBorder,
                    cursorColor = GrassGreen
                )
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Add",
                color = if (entry.isBlank()) TextSecondary else Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (entry.isBlank()) InkBorder else GrassGreen)
                    .clickable(enabled = entry.isNotBlank()) { onAdd(entry); entry = "" }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun RuleCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content
    )
}
