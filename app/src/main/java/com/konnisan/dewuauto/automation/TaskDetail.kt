package com.konnisan.dewuauto.automation

data class TaskDetail(
    val productName: String,
    val cooperationMethod: String,
    val creatorRequirements: String,
    val shootingRequirements: String,
    val contentType: TaskContentType,
    val rawText: String,
)

enum class TaskContentType(val displayName: String) {
    IMAGE_ONLY("仅图文"),
    IMAGE_OR_VIDEO("图文/视频"),
    VIDEO_ONLY("仅视频"),
    UNKNOWN("未识别"),
}

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
            shootingRequirements = sectionContent(segments, "拍摄要求"),
            contentType = parseContentType(segments),
            rawText = normalized,
        )
    }

    internal fun parseContentType(segments: List<String>): TaskContentType {
        val values = segments.map { it.replace(Regex("\\s+"), "").trim() }
        return when {
            values.any { it == "仅视频" || it == "只限视频" || it == "仅支持视频" } ->
                TaskContentType.VIDEO_ONLY
            values.any { it == "仅图文" || it == "只限图文" || it == "仅支持图文" } ->
                TaskContentType.IMAGE_ONLY
            values.any {
                it in setOf("图文/视频", "图文／视频", "图文或视频", "图文或者视频", "图文视频二选一")
            } -> TaskContentType.IMAGE_OR_VIDEO
            else -> TaskContentType.UNKNOWN
        }
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
