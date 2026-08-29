package com.konnisan.dewuauto.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.konnisan.dewuauto.automation.DewuSelectors
import java.util.ArrayDeque
import java.util.LinkedHashMap

object NodeUtils {
    fun findFirstByTexts(
        root: AccessibilityNodeInfo?,
        texts: Collection<String>,
        exactPreferred: Boolean = true,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val normalized = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null

        // “查看更多”在创作中心可能出现多次。优先选择离“品牌合作/商单”上下文最近的按钮，
        // 避免误点其它模块的“查看更多”。若无法确认上下文，再退回普通查找。
        if (normalized.any { it in DewuSelectors.MORE }) {
            val candidates = findAllByTexts(root, normalized)
            val contextual = candidates
                .mapNotNull { node ->
                    nearestAncestorContextDistance(node, DewuSelectors.BRAND_CONTEXT, maxLevels = 6)
                        ?.let { distance -> node to distance }
                }
                .minByOrNull { it.second }
                ?.first
            if (contextual != null) return contextual
        }

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
        val matches = findAll(root) { node ->
            val value = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            normalized.any {
                it == value || it == desc || value.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true)
            }
        }

        // React Native/部分 Android 实现可能把同一个可见文字节点以多个 AccessibilityNodeInfo
        // 引用暴露出来。真机上“查看更多”就出现过这种情况。按标签+实时屏幕边界去重，
        // 避免把一个按钮误判成多个不同入口；不使用厂商、分辨率或固定坐标特判。
        return deduplicateByVisibleBounds(matches)
    }

    fun findAll(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        breadthFirst(root) { node ->
            if (predicate(node)) result += node
            false
        }
        return result
    }

    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val label = node.text?.toString()?.trim().orEmpty().ifBlank {
            node.contentDescription?.toString()?.trim().orEmpty()
        }

        // 真机验证表明：React Native 的“查看更多”文字节点向上寻找 clickable 父容器时，
        // 可能命中过大的模块容器并触发旁边/其它模块。这里明确不沿父节点 ACTION_CLICK，
        // 让控制器使用该精确文字节点的实时 bounds 中心 dispatchGesture 点击。
        if (label in DewuSelectors.MORE) return false

        var current: AccessibilityNodeInfo? = node
        repeat(7) {
            if (current == null) return false
            if (current!!.isClickable && current!!.isEnabled) {
                return current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current!!.parent
        }
        return false
    }

    fun nearestClickableAncestor(
        node: AccessibilityNodeInfo?,
        maxLevels: Int = 6,
    ): AccessibilityNodeInfo? {
        var current = node
        repeat(maxLevels.coerceAtLeast(1)) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled) return candidate
            current = candidate.parent
        }
        return null
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

    private fun deduplicateByVisibleBounds(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        if (nodes.size <= 1) return nodes
        val unique = LinkedHashMap<String, AccessibilityNodeInfo>()
        for (node in nodes) {
            val value = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val rect = bounds(node)
            val key = if (rect != null && rect.width() > 0 && rect.height() > 0) {
                "$value|$desc|${rect.left},${rect.top},${rect.right},${rect.bottom}"
            } else {
                // 无有效边界时不激进合并，避免把真正不同但同名的不可见节点折叠掉。
                "$value|$desc|node:${System.identityHashCode(node)}"
            }
            unique.putIfAbsent(key, node)
        }
        return unique.values.toList()
    }

    private fun nearestAncestorContextDistance(
        node: AccessibilityNodeInfo?,
        contextTexts: Collection<String>,
        maxLevels: Int,
    ): Int? {
        var current = node?.parent
        for (level in 1..maxLevels.coerceAtLeast(1)) {
            val ancestor = current ?: break
            val context = collectText(ancestor, 60)
            if (contextTexts.any { context.contains(it, ignoreCase = true) }) return level
            current = ancestor.parent
        }
        return null
    }

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
