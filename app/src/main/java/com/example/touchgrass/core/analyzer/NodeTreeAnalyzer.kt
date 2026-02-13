package com.example.touchgrass.core.analyzer;

// core/analyzer/NodeTreeAnalyzer.kt
import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject

class NodeTreeAnalyzer @Inject constructor() {

    fun logNodeHierarchy(rootNode: AccessibilityNodeInfo, packageName: String): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("\n--- INSPECTION START [$packageName] ---\n")
        recursiveLog(rootNode, 0, stringBuilder)
        stringBuilder.append("--- INSPECTION END ---\n")
        return stringBuilder.toString()
    }

    private fun recursiveLog(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        // Indentation for tree structure
        val indent = " ".repeat(depth * 2)

        // Extract useful attributes for identification
        val id = node.viewIdResourceName?.substringAfter(":id/") ?: "no-id"
        val desc = node.contentDescription ?: "null"
        val text = node.text ?: "null"
        val className = node.className?.toString()?.substringAfterLast(".") ?: "View"

        // Only log nodes that have identifying traits to reduce noise
        if (id != "no-id" || desc != "null" || text != "null") {
            sb.append("$indent[$className] ID: $id | Desc: $desc | Text: $text\n")
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                    recursiveLog(child, depth + 1, sb)
                // Note: In a real app, we must be careful about recycling,
                // but for this inspector tool, the system handles the snapshot.
            }
        }
    }
}