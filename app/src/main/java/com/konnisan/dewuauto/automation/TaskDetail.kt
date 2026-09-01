package com.konnisan.dewuauto.automation

data class TaskDetail(
    val productName: String,
    val cooperationMethod: String,
    val creatorRequirements: String,
    val rawText: String,
)

object TaskDetailParser {
    fun parse(rawText: String, fallbackProductName: String? = null): TaskDetail? =
        parse(rawText.split(Regex("\\s*[|｜]\\s*")), fallbackProductName)

    fun parse(textSegments: List<String>, fallbackProductName: String? = null): TaskDetail? {
        val segments = textSegments.map(String::trim).filter(String::isNotEmpty)
        val normalized = segments.joinToString(" | ")
        if (normalized.isBlank()) return null
        if (DewuSelectors.TASK_DETAIL_MARKERS.none { normalized.contains(it, ignoreCase = true) }) {
            return null
        }

        val detailProductName = sectionValues(segments, "任务商品").firstOrNull()
        val inferredTitle = segments.firstOrNull {
            it !in DewuSelectors.TASK_DETAIL_MARKERS &&
                DewuSelectors.DETAIL_SECTION_BOUNDARIES.none { marker -> isSectionHeader(it, marker) }
        }
        return TaskDetail(
            productName = detailProductName
                ?.takeIf(String::isNotBlank)
                ?: fallbackProductName.orEmpty().trim()
                    .ifBlank { inferredTitle.orEmpty() },
            cooperationMethod = sectionContent(segments, "合作方式"),
            creatorRequirements = sectionContent(segments, "达人要求"),
            rawText = normalized,
        )
    }

    private fun sectionContent(segments: List<String>, label: String): String {
        return sectionValues(segments, label).joinToString(" ").trim()
    }

    private fun sectionValues(segments: List<String>, label: String): List<String> {
        val startIndex = segments.indexOfFirst { isSectionHeader(it, label) }
        if (startIndex < 0) return emptyList()

        val values = mutableListOf<String>()
        inlineContent(segments[startIndex], label).takeIf(String::isNotBlank)?.let(values::add)
        for (index in (startIndex + 1) until segments.size) {
            val value = segments[index]
            if (DewuSelectors.DETAIL_SECTION_BOUNDARIES.any { isSectionHeader(value, it) }) break
            if (DewuSelectors.REGISTER_BUTTONS.any { value == it } ||
                DewuSelectors.ENROLLMENT_SUCCESS_MARKERS.any { value.contains(it, ignoreCase = true) }
            ) {
                break
            }
            values += value
        }
        return values.distinct()
    }

    private fun inlineContent(value: String, label: String): String = value
        .removePrefix(label)
        .trimStart(' ', '：', ':')

    private fun isSectionHeader(value: String, label: String): Boolean =
        value == label || value.startsWith("$label：") || value.startsWith("$label:")
}
