package com.example.touchgrass.features.worktargets

import com.example.touchgrass.core.remote.DeviceApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WorkTargetSuggestionsTest {

    private val day = LocalDate.of(2026, 10, 28)
    private val vscode = "visual studio code"
    private val studio = "android studio"

    private fun app(name: String) = DeviceApp(name, name)
    private fun usage(app: String, project: String, minutes: Int) = AppUsageRow(day, app, project, minutes * 60)
    private fun seen(app: String, project: String, epochSec: Long) =
        DeviceProject(app, project, Instant.ofEpochSecond(epochSec))

    @Test
    fun `system and background processes are hidden from the app list`() {
        listOf(
            "searchhost", "shellhost", "ShellExperienceHost.exe", "setup", "Discord Setup",
            "uninstall", "touchgrass-agent", "windows problem reporting", "explorer"
        ).forEach { assertTrue(it, WorkTargetSuggestions.isHiddenApp(it)) }
        listOf(vscode, studio, "chrome", "windows terminal", "figma")
            .forEach { assertFalse(it, WorkTargetSuggestions.isHiddenApp(it)) }
    }

    @Test
    fun `apps get readable names`() {
        assertEquals("VS Code", WorkTargetSuggestions.label(vscode))
        assertEquals("Android Studio", WorkTargetSuggestions.label("studio64"))
        assertEquals("Microsoft Teams", WorkTargetSuggestions.label("microsoft teams", "microsoft teams"))
        assertEquals("Figma", WorkTargetSuggestions.label("figma", "Figma"))
    }

    @Test
    fun `projects from IDE lists and usage merge ignoring case and rank by minutes then last seen`() {
        val rows = WorkTargetSuggestions.suggestions(
            projects = listOf(
                seen(vscode, "TouchGrass", 100),
                seen(vscode, "portfolio", 300),
                seen(studio, "Notes", 200)
            ),
            usage = listOf(usage(vscode, "touchgrass", 90), usage(vscode, "touchgrass", 30)),
            apps = listOf(app(vscode), app(studio))
        )
        val projectRows = rows.filter { it.project != null }
        assertEquals(listOf("TouchGrass", "portfolio", "Notes"), projectRows.map { it.project })
        assertEquals(120, projectRows.first().minutes)
        assertEquals("TouchGrass · VS Code", projectRows.first().title)
    }

    @Test
    fun `IDEs get an any-project row after the specific projects`() {
        val rows = WorkTargetSuggestions.suggestions(
            projects = listOf(seen(vscode, "TouchGrass", 100)),
            usage = emptyList(),
            apps = listOf(app(vscode), app(studio), app("chrome"), app("searchhost"))
        )
        assertEquals(
            // VS Code has a recent project, so its any-project row ranks above Android Studio's.
            listOf("TouchGrass · VS Code", "Any project in VS Code", "Any project in Android Studio"),
            rows.map { it.title }
        )
    }

    @Test
    fun `search ignores case and spaces so vscode finds VS Code`() {
        val rows = WorkTargetSuggestions.suggestions(
            projects = listOf(seen(vscode, "TouchGrass", 100), seen(studio, "Notes", 100)),
            usage = emptyList(),
            apps = emptyList(),
            query = "vscode"
        )
        assertEquals(listOf("TouchGrass · VS Code", "Any project in VS Code"), rows.map { it.title })
    }

    @Test
    fun `other apps hide junk and rank by tracked time`() {
        val apps = listOf(app("chrome"), app(vscode), app("searchhost"), app("figma"))
        val choices = WorkTargetSuggestions.otherApps(apps, listOf(usage(vscode, "", 50), usage("figma", "", 10)))
        assertEquals(listOf("VS Code", "Figma", "Chrome"), choices.map { it.label })
        assertEquals(listOf("Chrome"), WorkTargetSuggestions.otherApps(apps, emptyList(), "CHR").map { it.label })
    }
}
