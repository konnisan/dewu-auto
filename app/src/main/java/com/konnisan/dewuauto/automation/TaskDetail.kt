package com.konnisan.dewuauto.automation

data class TaskDetail(
    val title: String?,
    val rawText: String,
)

object TaskDetailParser {
    fun parse(rawText: String): TaskDetail? {
        val normalized = rawText.trim()
        if (normalized.isBlank()) return null
        if (DewuSelectors.TASK_DETAIL_MARKERS.none { normalized.contains(it, ignoreCase = true) }) {
            return null
        }

        val segments = normalized
            .split(Regex("\\s*[|｜]\\s*"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        val title = segments.firstOrNull {
            it !in DewuSelectors.TASK_DETAIL_MARKERS &&
                DewuSelectors.DETAIL_SECTION_MARKERS.none { marker -> it == marker }
        }
        return TaskDetail(title = title, rawText = normalized)
    }
}
