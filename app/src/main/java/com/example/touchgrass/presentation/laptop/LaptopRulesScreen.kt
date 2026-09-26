package com.example.touchgrass.presentation.laptop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.remote.FocusPolicy
import com.example.touchgrass.core.remote.FocusPolicyRepository
import com.example.touchgrass.ui.theme.DangerRed
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LaptopRulesViewModel @Inject constructor(
    private val repo: FocusPolicyRepository
) : ViewModel() {
    val policy = repo.policy
    val isConfigured: Boolean = repo.isConfigured

    init {
        if (isConfigured) viewModelScope.launch { repo.refresh() }
    }

    fun update(policy: FocusPolicy) = repo.update(policy)
}

@Composable
fun LaptopRulesScreen(viewModel: LaptopRulesViewModel = hiltViewModel()) {
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
            "Control what your laptop agent allows and blocks during a focus session. Apps match by name; sites by domain.",
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

        RuleSection(
            title = "Productive apps",
            helper = "Working in these counts as focus time",
            placeholder = "Add app (e.g. Code)",
            items = policy.allowedApps,
            onChange = { viewModel.update(policy.copy(allowedApps = it)) }
        )
        RuleSection(
            title = "Blocked apps",
            helper = "Minimized if you open them during a focus block",
            placeholder = "Add app (e.g. whatsapp)",
            items = policy.blockedApps,
            onChange = { viewModel.update(policy.copy(blockedApps = it)) }
        )
        RuleSection(
            title = "Force-quit apps",
            helper = "Closed on sight — use sparingly",
            placeholder = "Add app",
            items = policy.forceQuitApps,
            onChange = { viewModel.update(policy.copy(forceQuitApps = it)) }
        )
        RuleSection(
            title = "Blocked sites",
            helper = "Null-routed in every browser during focus (agent must run as admin)",
            placeholder = "Add domain (e.g. reddit.com)",
            items = policy.blockedSites,
            onChange = { viewModel.update(policy.copy(blockedSites = it)) }
        )
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleSection(
    title: String,
    helper: String,
    placeholder: String,
    items: List<String>,
    onChange: (List<String>) -> Unit
) {
    var entry by remember { mutableStateOf("") }
    RuleCard {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(helper, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        if (items.isEmpty()) {
            Text("None", color = TextSecondary, fontSize = 12.sp)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Ink)
                            .border(1.dp, InkBorder, RoundedCornerShape(50))
                            .clickable { onChange(items - item) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item, color = TextPrimary, fontSize = 13.sp)
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
                placeholder = { Text(placeholder, color = TextSecondary, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                keyboardActions = KeyboardActions(onDone = {
                    val v = entry.trim()
                    if (v.isNotEmpty() && v !in items) onChange(items + v)
                    entry = ""
                }),
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
                    .clickable(enabled = entry.isNotBlank()) {
                        val v = entry.trim()
                        if (v.isNotEmpty() && v !in items) onChange(items + v)
                        entry = ""
                    }
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
