package com.konnisan.dewuauto.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object NodeUtils {
    fun findFirstByTexts(
        root: AccessibilityNodeInfo?,
        texts: Collection<String>,
        exactPreferred: Boolean = true,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val normalized = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null

        if (exactPreferred) {
            breadthFirst(root) { node ->
                val value = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                normalized.any { it == value || it == desc }
            }?.let { return it }
        }

        return breadthFirst(root) { node ->
            val value = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            normalized.any { value.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true) }
        }
    }

    fun findAllByTexts(root: AccessibilityNodeInfo?, texts: Collection<String>): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val normalized = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<AccessibilityNodeInfo>()
        breadthFirst(root) { node ->
            val value = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val matched = normalized.any {
                it == value || it == desc || value.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true)
            }
            if (matched) result += node
            false
        }
        return result
    }

    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        repeat(7) {
            if (current == null) return false
            if (current!!.isClickable && current!!.isEnabled) {
                return current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current!!.parent
        }
        return false
    }

    fun collectText(node: AccessibilityNodeInfo?, maxNodes: Int = 120): String {
        if (node == null) return ""
        val values = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val current = queue.removeFirst()
            visited++
            current.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            current.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(values::add)
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let(queue::addLast)
            }
        }
        return values.joinToString(" | ")
    }

    fun ancestorText(node: AccessibilityNodeInfo?, levels: Int = 4): String {
        var current = node
        val chunks = mutableListOf<String>()
        repeat(levels.coerceAtLeast(1)) {
            if (current == null) return@repeat
            collectText(current, 80).takeIf { it.isNotBlank() }?.let(chunks::add)
            current = current?.parent
        }
        return chunks.distinct().joinToString(" | ")
    }

    fun bounds(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        return Rect().also(node::getBoundsInScreen)
    }

    fun hasAnyText(root: AccessibilityNodeInfo?, texts: Collection<String>): Boolean =
        findFirstByTexts(root, texts) != null

    fun dumpVisibleText(root: AccessibilityNodeInfo?, maxNodes: Int = 250): String = collectText(root, maxNodes)

    private fun breadthFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }
}
