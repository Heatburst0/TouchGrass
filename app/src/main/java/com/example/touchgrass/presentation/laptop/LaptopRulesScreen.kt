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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import com.example.touchgrass.core.remote.LaptopSessionsRepository
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How a focus session treats a laptop app. */
enum class AppRole(val label: String, val description: String) {
    TRACK("Track", "Counts as focus time"),
    BLOCK("Block", "Minimized when you open it during a focus block"),
    FORCE_QUIT("Force-quit", "Closed on sight during focus"),
    IGNORE("Ignore", "Not tracked — default")
}

/** A laptop app plus its current role, merged from discovery + saved policy. */
data class LaptopApp(val name: String, val displayName: String, val role: AppRole)

/** An untracked app the user spent meaningful time in last session. */
data class AppSuggestion(val name: String, val seconds: Int)

private const val SUGGEST_THRESHOLD_SEC = 120 // only suggest apps with ≥2 min off-task

@HiltViewModel
class LaptopRulesViewModel @Inject constructor(
    private val policyRepo: FocusPolicyRepository,
    private val deviceApps: DeviceAppsRepository,
    private val sessionsRepo: LaptopSessionsRepository
) : ViewModel() {

    val isConfigured: Boolean = policyRepo.isConfigured
    val policy = policyRepo.policy

    // Apps the user has dismissed from suggestions this screen-session.
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    /** Top untracked time-sinks from the most recent session, worth a rule. */
    val suggestions: StateFlow<List<AppSuggestion>> =
        combine(policyRepo.policy, sessionsRepo.sessions, dismissed) { policy, sessions, dismissedNames ->
            val latest = sessions.firstOrNull() ?: return@combine emptyList()
            val known = (policy.allowedApps + policy.blockedApps + policy.forceQuitApps).toSet()
            latest.offTask
                .filter { it.seconds >= SUGGEST_THRESHOLD_SEC && it.name !in known && it.name !in dismissedNames }
                .take(3)
                .map { AppSuggestion(it.name, it.seconds) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun dismissSuggestion(name: String) {
        dismissed.value = dismissed.value + name
    }

    // App names that have appeared this screen-session. Grows only, so an app set to
    // Ignore (removed from every policy list) stays visible instead of vanishing.
    private val knownNames = MutableStateFlow<Set<String>>(emptySet())

    val apps: StateFlow<List<LaptopApp>> =
        combine(policyRepo.policy, deviceApps.apps, knownNames) { policy, discovered, known ->
            val names = LinkedHashSet<String>()
            names.addAll(known)
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
        if (isConfigured) {
            viewModelScope.launch {
                policyRepo.refresh()
                deviceApps.refresh()
                sessionsRepo.refresh()
            }
            // Accumulate every name we ever see so roles can change without rows disappearing.
            viewModelScope.launch {
                combine(policyRepo.policy, deviceApps.apps) { policy, discovered ->
                    val s = LinkedHashSet<String>()
                    discovered.forEach { s.add(it.name) }
                    s.addAll(policy.allowedApps)
                    s.addAll(policy.blockedApps)
                    s.addAll(policy.forceQuitApps)
                    s
                }.collect { incoming -> knownNames.value = knownNames.value + incoming }
            }
        }
    }

    /** Assign an explicit role by rewriting the policy lists (idempotent). */
    fun setRole(name: String, role: AppRole) {
        val p = policyRepo.policy.value
        val allowed = p.allowedApps - name
        val blocked = p.blockedApps - name
        val forceQuit = p.forceQuitApps - name
        val updated = when (role) {
            AppRole.TRACK -> p.copy(allowedApps = allowed + name, blockedApps = blocked, forceQuitApps = forceQuit)
            AppRole.BLOCK -> p.copy(allowedApps = allowed, blockedApps = blocked + name, forceQuitApps = forceQuit)
            AppRole.FORCE_QUIT -> p.copy(allowedApps = allowed, blockedApps = blocked, forceQuitApps = forceQuit + name)
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

private fun roleColor(role: AppRole): Color = when (role) {
    AppRole.TRACK -> GrassGreen
    AppRole.BLOCK -> AmberWarn
    AppRole.FORCE_QUIT -> DangerRed
    AppRole.IGNORE -> TextSecondary
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaptopRulesScreen(viewModel: LaptopRulesViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsState()
    val policy by viewModel.policy.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    var sheetApp by remember { mutableStateOf<LaptopApp?>(null) }

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
        Text("Choose how focus sessions treat each laptop app.", color = TextSecondary, fontSize = 13.sp)

        if (!viewModel.isConfigured) {
            RuleCard {
                Text("Sign in first", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Sign in on Tools → Account & sync to manage laptop rules.", color = TextSecondary, fontSize = 12.sp)
            }
            return@Column
        }

        LegendCard()

        if (suggestions.isNotEmpty()) {
            SuggestionsCard(
                items = suggestions,
                onTrack = { viewModel.setRole(it, AppRole.TRACK) },
                onBlock = { viewModel.setRole(it, AppRole.BLOCK) },
                onDismiss = { viewModel.dismissSuggestion(it) }
            )
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
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    AppRow(app) { sheetApp = app }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SitesSection(sites = policy.blockedSites, onAdd = viewModel::addSite, onRemove = viewModel::removeSite)
        Spacer(Modifier.height(16.dp))
    }

    val current = sheetApp
    if (current != null) {
        ModalBottomSheet(onDismissRequest = { sheetApp = null }, containerColor = InkElevated) {
            RoleSheet(
                app = current,
                onPick = { role ->
                    viewModel.setRole(current.name, role)
                    sheetApp = null
                }
            )
        }
    }
}

@Composable
private fun SuggestionsCard(
    items: List<AppSuggestion>,
    onTrack: (String) -> Unit,
    onBlock: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    RuleCard {
        Text("Suggestions", color = GrassGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text("Apps you spent time in last session but haven't set a rule for.", color = TextSecondary, fontSize = 11.sp)
        items.forEach { s ->
            Spacer(Modifier.height(12.dp))
            Text(
                "${s.seconds / 60}m in ${s.name}",
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SuggestionButton("Track", GrassGreen) { onTrack(s.name) }
                SuggestionButton("Block", AmberWarn) { onBlock(s.name) }
                SuggestionButton("Dismiss", TextSecondary) { onDismiss(s.name) }
            }
        }
    }
}

@Composable
private fun SuggestionButton(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        color = tint,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun LegendCard() {
    RuleCard {
        AppRole.entries.forEachIndexed { i, role ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(roleColor(role)))
                Spacer(Modifier.size(10.dp))
                Text(role.label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(6.dp))
                Text("— ${role.description}", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AppRow(app: LaptopApp, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Ink)
                .border(1.dp, InkBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(app.displayName.firstOrNull()?.uppercase() ?: "?", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Text(app.displayName, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(8.dp))
        RoleBadge(app.role)
        Spacer(Modifier.size(6.dp))
        Text("›", color = TextSecondary, fontSize = 18.sp)
    }
}

@Composable
private fun RoleBadge(role: AppRole) {
    val tint = roleColor(role)
    val bg = if (role == AppRole.IGNORE) Color.Transparent else tint.copy(alpha = 0.15f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.size(6.dp))
        Text(role.label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoleSheet(app: LaptopApp, onPick: (AppRole) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text("How should focus treat", color = TextSecondary, fontSize = 13.sp)
        Text(app.displayName, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        AppRole.entries.forEach { role ->
            RoleOption(role = role, selected = role == app.role, onClick = { onPick(role) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun RoleOption(role: AppRole, selected: Boolean, onClick: () -> Unit) {
    val tint = roleColor(role)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) tint.copy(alpha = 0.12f) else Ink)
            .border(1.dp, if (selected) tint.copy(alpha = 0.6f) else InkBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(role.label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(role.description, color = TextSecondary, fontSize = 12.sp)
        }
        if (selected) {
            Spacer(Modifier.size(8.dp))
            Text("✓", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
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
