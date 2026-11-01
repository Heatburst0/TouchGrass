package com.example.touchgrass.features.worktargets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.touchgrass.features.worktargets.WorkTargetSuggestions
import com.example.touchgrass.features.worktargets.WorkTargetUsage
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

private const val TOP_SUGGESTIONS = 8
private const val MAX_RESULTS = 30
private const val TOP_APPS = 10

/**
 * "What do you want to work on?": one searchable list of project rows
 * ("TouchGrass · VS Code", "Any project in Android Studio") plus an "Other app…"
 * expander with the laptop's real apps. Picking a row sets app AND project.
 */
@Composable
internal fun TargetPicker(
    source: PickerSource,
    appName: String,
    project: String?,
    onPick: (appName: String, appLabel: String, project: String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showApps by remember { mutableStateOf(false) }
    val searching = query.isNotBlank()
    val suggestions = remember(source, query) {
        WorkTargetSuggestions.suggestions(source.projects, source.usage, source.apps, query)
            .take(if (searching) MAX_RESULTS else TOP_SUGGESTIONS)
    }
    val apps = remember(source, query) {
        WorkTargetSuggestions.otherApps(source.apps, source.usage, query)
            .take(if (searching) MAX_RESULTS else TOP_APPS)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search projects or apps, e.g. vs code", color = TextSecondary) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (suggestions.isEmpty()) {
            Hint(
                if (searching) "No projects match \"$query\" — try Other app below."
                else "Projects show up here once the laptop agent has seen your IDE's recent projects."
            )
        }
        suggestions.forEach { s ->
            PickRow(
                title = s.title,
                detail = when {
                    s.minutes > 0 -> "${WorkTargetUsage.formatMinutes(s.minutes)} tracked recently"
                    s.project == null -> "Counts every project you open in ${s.appLabel}"
                    else -> "In ${s.appLabel}'s recent projects"
                },
                selected = s.isFor(appName, project),
                onClick = { onPick(s.appName, s.appLabel, s.project) }
            )
        }

        Text(
            if (showApps) "Other app ▴" else "Other app… ▾",
            color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showApps = !showApps }
                .padding(vertical = 6.dp)
        )
        if (showApps || (searching && suggestions.isEmpty())) {
            if (apps.isEmpty()) {
                Hint("No laptop apps yet — make sure the TouchGrass agent is running.")
            }
            apps.forEach { a ->
                PickRow(
                    title = a.label,
                    detail = if (a.minutes > 0) "${WorkTargetUsage.formatMinutes(a.minutes)} tracked recently · any project"
                    else "Any project",
                    selected = a.appName.equals(appName, ignoreCase = true) && project.isNullOrBlank(),
                    onClick = { onPick(a.appName, a.label, null) }
                )
            }
        }
    }
}

@Composable
private fun PickRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) GrassGreen.copy(alpha = 0.12f) else Ink)
            .border(1.dp, if (selected) GrassGreen else InkBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextSecondary, fontSize = 11.sp)
        }
        if (selected) Text("✓", color = GrassGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
