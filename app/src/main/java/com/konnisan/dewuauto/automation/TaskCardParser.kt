package com.konnisan.dewuauto.automation

object TaskCardParser {
    private val rewardPattern = Regex("[¥￥]\\s*(\\d+(?:\\.\\d+)?)")
    private val capacityPattern = Regex("(?:(?:已)?报名[：:]?\\s*)?(\\d+)\\s*/\\s*(\\d+)\\s*人")
    private val deadlinePattern = Regex(
        "秒杀剩\\s*\\d+\\s*(?:秒|分钟|小时|天)|" +
            "\\d+\\s*(?:秒|分钟|小时|天)\\s*(?:后截止|后结束|剩余)|" +
            "已截止|报名结束|不可报名",
    )

    private val nonTitlePatterns = listOf(
        Regex("^报名$"),
        Regex("^立即报名$"),
        Regex("^已报名"),
        Regex("^报名[：:]?\\s*\\d+\\s*/"),
        Regex("^\\d+\\s*/\\s*\\d+人$"),
        Regex("^现金奖励"),
        Regex("^[¥￥]"),
        Regex("后截止|已截止|秒杀剩|报名结束|不可报名"),
    )

    fun parse(rawText: String): TaskCard? {
        val normalized = rawText
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '|', ',')
        if (normalized.isBlank()) return null

        val title = normalized
            .split('|', ',', '，')
            .map { it.trim() }
            .firstOrNull { part ->
                part.length >= 4 && nonTitlePatterns.none { it.containsMatchIn(part) }
            }
            ?: return null

        val reward = rewardPattern.find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val capacityMatch = capacityPattern.find(normalized)
        val registered = capacityMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val capacity = capacityMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        val deadline = deadlinePattern.find(normalized)?.value?.trim()
        val signatureSource = listOf(title, reward, registered, capacity, deadline).joinToString("|")

        return TaskCard(
            signature = signatureSource.hashCode().toString(),
            title = title,
            rewardAmount = reward,
            registeredCount = registered,
            capacity = capacity,
            deadlineText = deadline,
            rawText = normalized,
        )
    }
}
