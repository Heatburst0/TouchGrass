package com.example.touchgrass.features.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.window.Dialog
import com.example.touchgrass.features.focus.AppInfo
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary

/**
 * Pick the apps focus blocks. [lockedPackages] are held by the locked weekly plan:
 * they stay checked (the plan would refuse removing them anyway).
 */
@Composable
internal fun AppPickerDialog(
    apps: List<AppInfo>,
    labelOf: (String) -> String,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    lockedPackages: Set<String> = emptySet()
) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(initiallySelected + lockedPackages) }

    // Ensure already-selected packages that aren't launchable (or not yet loaded)
    // still appear so they can be unchecked.
    val known = remember(apps) { apps.associateBy { it.packageName } }
    val extra = initiallySelected.filter { it !in known }.map { AppInfo(it, labelOf(it)) }
    val filtered = (extra + apps).filter { it.label.contains(query, ignoreCase = true) }
    val toggle = { pkg: String ->
        if (pkg !in lockedPackages) chosen = if (pkg in chosen) chosen - pkg else chosen + pkg
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(InkElevated)
                .border(1.dp, InkBorder, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Text("Block during focus", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("${chosen.size} selected", color = GrassGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (lockedPackages.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "🔒 Your weekly plan is locked — you can add apps, but 🔒 ones stay until the weekend.",
                    color = AmberWarn, fontSize = 11.sp, lineHeight = 14.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val locked = app.packageName in lockedPackages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !locked) { toggle(app.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = app.packageName in chosen,
                            onCheckedChange = { toggle(app.packageName) },
                            enabled = !locked,
                            colors = CheckboxDefaults.colors(
                                checkedColor = GrassGreen,
                                checkmarkColor = Ink,
                                disabledCheckedColor = GrassGreen.copy(alpha = 0.45f)
                            )
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (locked) "${app.label}  🔒" else app.label,
                            color = TextPrimary, fontSize = 14.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Cancel",
                    color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onDismiss() }.padding(12.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Done",
                    color = GrassGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onConfirm(chosen) }.padding(12.dp)
                )
            }
        }
    }
}
