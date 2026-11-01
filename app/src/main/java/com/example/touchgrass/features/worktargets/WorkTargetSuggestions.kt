package com.example.touchgrass.features.worktargets

import com.example.touchgrass.core.remote.DeviceApp
import java.time.Instant

/** One pickable row in the work-target sheet; [project] null = any project in the app. */
data class TargetSuggestion(
    val appName: String,     // lowercased match key
    val appLabel: String,    // "VS Code"
    val project: String?,
    val minutes: Int,        // recent tracked time (app_usage lookback)
    val lastSeen: Instant?   // newest device_projects sighting
) {
    val title: String get() = if (project == null) "Any project in $appLabel" else "$project · $appLabel"

    fun isFor(app: String, project: String?): Boolean =
        appName.equals(app.trim(), ignoreCase = true) &&
            this.project.orEmpty().equals(project.orEmpty().trim(), ignoreCase = true)
}

/** A laptop app offered under "Other app…". */
data class AppChoice(val appName: String, val label: String, val minutes: Int)

/**
 * Pure building of the work-target picker: merges IDE recent projects (device_projects),
 * tracked projects (app_usage) and "any project" rows for IDEs, hides system processes,
 * gives apps readable names and ranks by recent use.
 */
object WorkTargetSuggestions {

    /** Windows shell / background processes the agent sees but nobody works "in". */
    private val HIDDEN_EXACT = setOf(
        "explorer", "searchapp", "searchui", "lockapp", "dllhost", "rundll32", "consent",
        "systemsettings", "settings", "taskmgr", "task manager", "msiexec", "openwith",
        "werfault", "program manager", "credentialuibroker", "unknown"
    )
    private val HIDDEN_PARTS = listOf(
        "setup", "uninstall", "installer", "updater", "problem reporting", "crash", "touchgrass"
    )
    private val ALLOWED_WINDOWS_APPS = setOf("windows terminal")

    /** Process / window names → the name people actually call the app. */
    private val KNOWN_LABELS = mapOf(
        "visual studio code" to "VS Code", "code" to "VS Code",
        "visual studio code - insiders" to "VS Code Insiders", "code - insiders" to "VS Code Insiders",
        "android studio" to "Android Studio", "studio64" to "Android Studio",
        "intellij idea" to "IntelliJ IDEA", "idea64" to "IntelliJ IDEA", "idea" to "IntelliJ IDEA",
        "pycharm" to "PyCharm", "pycharm64" to "PyCharm", "webstorm" to "WebStorm", "webstorm64" to "WebStorm",
        "clion" to "CLion", "goland" to "GoLand", "rider" to "Rider", "rider64" to "Rider",
        "rustrover" to "RustRover", "phpstorm" to "PhpStorm", "datagrip" to "DataGrip",
        "devenv" to "Visual Studio", "msedge" to "Edge", "chrome" to "Chrome", "firefox" to "Firefox",
        "windowsterminal" to "Windows Terminal"
    )
    private val IDE_EXACT = setOf(
        "code", "idea", "idea64", "studio64", "rider64", "pycharm64", "webstorm64", "devenv",
        "cursor", "windsurf", "zed", "fleet", "eclipse"
    )
    private val IDE_PARTS = listOf(
        "visual studio", "android studio", "intellij", "pycharm", "webstorm", "clion", "goland",
        "rider", "rustrover", "phpstorm", "datagrip", "sublime text", "xcode", "netbeans"
    )

    private fun key(appName: String) = appName.trim().lowercase().removeSuffix(".exe")

    fun isHiddenApp(appName: String): Boolean {
        val n = key(appName)
        return n.isBlank() ||
            n in HIDDEN_EXACT ||
            HIDDEN_PARTS.any { it in n } ||
            (' ' !in n && n.endsWith("host")) || // searchhost, shellhost, dllhost, textinputhost…
            (n.startsWith("windows ") && n !in ALLOWED_WINDOWS_APPS)
    }

    fun isIde(appName: String): Boolean {
        val n = key(appName)
        return n in IDE_EXACT || IDE_PARTS.any { it in n }
    }

    /** Friendly name; keeps the agent's display name when it has real casing, else title-cases. */
    fun label(appName: String, displayName: String? = null): String {
        KNOWN_LABELS[key(appName)]?.let { return it }
        val shown = displayName?.trim().orEmpty().removeSuffix(".exe")
        if (shown.isNotEmpty() && shown != shown.lowercase()) return shown
        return key(appName).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }

    /**
     * Every project row (IDE recent lists + tracked usage), deduped by app + project
     * ignoring case and ranked by recent minutes then last seen; then one
     * "Any project in <IDE>" row per IDE, ranked the same way. Filtered by [query].
     */
    fun suggestions(
        projects: List<DeviceProject>,
        usage: List<AppUsageRow>,
        apps: List<DeviceApp>,
        query: String = ""
    ): List<TargetSuggestion> {
        val labelOf = labeller(apps)
        val specific = LinkedHashMap<String, Acc>()
        projects.filter { it.project.isNotBlank() && !isHiddenApp(it.appName) }.forEach { p ->
            val acc = specific.getOrPut(key(p.appName) + "|" + p.project.trim().lowercase()) {
                Acc(key(p.appName), p.project.trim())
            }
            acc.lastSeen = newest(acc.lastSeen, p.lastSeen)
        }
        usage.filter { it.project.isNotBlank() && !isHiddenApp(it.appName) }.forEach { u ->
            val acc = specific.getOrPut(key(u.appName) + "|" + u.project.trim().lowercase()) {
                Acc(key(u.appName), u.project.trim())
            }
            acc.seconds += u.activeSeconds
        }

        val ideApps = (apps.map { it.name }.filter(::isIde) + specific.values.map { it.appName })
            .map(::key).filter { !isHiddenApp(it) }.distinct()
        val anyRows = ideApps.map { app ->
            TargetSuggestion(
                appName = app,
                appLabel = labelOf(app),
                project = null,
                minutes = (usage.filter { key(it.appName) == app }.sumOf { it.activeSeconds.toLong() } / 60).toInt(),
                lastSeen = specific.values.filter { it.appName == app }.mapNotNull { it.lastSeen }.maxOrNull()
            )
        }
        val projectRows = specific.values.map {
            TargetSuggestion(it.appName, labelOf(it.appName), it.project, (it.seconds / 60).toInt(), it.lastSeen)
        }
        return (rank(projectRows) + rank(anyRows))
            .filter { matchesQuery(query, it.title, it.appName) }
    }

    /** Laptop apps for "Other app…": system processes hidden, most-used first. */
    fun otherApps(apps: List<DeviceApp>, usage: List<AppUsageRow>, query: String = ""): List<AppChoice> {
        val seconds = usage.groupBy { key(it.appName) }.mapValues { (_, r) -> r.sumOf { it.activeSeconds.toLong() } }
        return apps
            .filter { !isHiddenApp(it.name) }
            .distinctBy { key(it.name) }
            .map { AppChoice(key(it.name), label(it.name, it.displayName), ((seconds[key(it.name)] ?: 0L) / 60).toInt()) }
            .filter { matchesQuery(query, it.label, it.appName) }
            .sortedWith(compareByDescending<AppChoice> { it.minutes }.thenBy { it.label.lowercase() })
    }

    private class Acc(val appName: String, val project: String) {
        var seconds: Long = 0
        var lastSeen: Instant? = null
    }

    private fun labeller(apps: List<DeviceApp>): (String) -> String {
        val display = apps.associate { key(it.name) to it.displayName }
        return { app -> label(app, display[app]) }
    }

    private fun newest(a: Instant?, b: Instant?): Instant? = listOfNotNull(a, b).maxOrNull()

    private fun rank(rows: List<TargetSuggestion>): List<TargetSuggestion> = rows.sortedWith(
        compareByDescending<TargetSuggestion> { it.minutes }
            .thenByDescending { it.lastSeen?.toEpochMilli() ?: Long.MIN_VALUE }
            .thenBy { it.title.lowercase() }
    )

    /** Case- and space-insensitive, so "vscode" finds "VS Code". */
    private fun matchesQuery(query: String, vararg fields: String): Boolean {
        val q = compact(query)
        return q.isEmpty() || fields.any { q in compact(it) }
    }

    private fun compact(s: String) = s.lowercase().filterNot { it.isWhitespace() }
}
