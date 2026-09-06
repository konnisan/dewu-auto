package com.konnisan.dewuauto.automation

import com.konnisan.dewuauto.config.AutomationConfig

object TaskEligibilityEvaluator {
    private val stoppedMarkers = listOf("已截止", "报名结束", "不可报名")

    private data class BlockField(val displayName: String, val value: String)

    fun evaluate(task: TaskCard, config: AutomationConfig): TaskEligibility {
        if (
            task.capacity != null &&
            task.registeredCount != null &&
            task.capacity > 0 &&
            task.registeredCount >= task.capacity
        ) {
            return TaskEligibility(false, "名额已满")
        }

        if (stoppedMarkers.any { task.rawText.contains(it, ignoreCase = true) }) {
            return TaskEligibility(false, "任务已截止")
        }

        findBlockedWord(
            fields = listOf(BlockField("商品名字", task.title)),
            words = config.excludedWords,
        )?.let { return it }

        val reward = task.rewardAmount
        if (reward == null && (config.minPrice > 0.0 || config.maxPrice < 9_999_999.0)) {
            return TaskEligibility(false, "未识别到现金奖励，设置范围 ${formatRange(config)}，跳过")
        }
        if (reward != null && reward !in config.minPrice..config.maxPrice) {
            return TaskEligibility(
                false,
                "现金奖励 ${formatReward(reward)}，不在 ${formatRange(config)}，跳过",
            )
        }

        return TaskEligibility(
            true,
            reward?.let { "现金奖励 ${formatReward(it)}，位于 ${formatRange(config)}，通过" }
                ?: "未设置现金奖励范围，通过",
        )
    }

    fun evaluateDetail(detail: TaskDetail, config: AutomationConfig): TaskEligibility {
        evaluateDetailOverview(detail, config).takeUnless(TaskEligibility::eligible)?.let { return it }
        return evaluateShootingRequirements(detail, config)
    }

    fun evaluateDetailOverview(detail: TaskDetail, config: AutomationConfig): TaskEligibility {
        when (detail.contentType) {
            TaskContentType.VIDEO_ONLY -> return TaskEligibility(
                false,
                "内容类型为仅视频",
                matchedField = "内容类型",
                matchedWord = "仅视频",
            )
            TaskContentType.UNKNOWN -> return TaskEligibility(
                false,
                "内容类型未识别",
                matchedField = "内容类型",
            )
            TaskContentType.IMAGE_ONLY,
            TaskContentType.IMAGE_OR_VIDEO -> Unit
        }

        findBlockedWord(
            fields = listOf(
                BlockField("商品名字", detail.productName),
                BlockField("合作方式", detail.cooperationMethod),
            ),
            words = config.excludedWords,
        )?.let { return it }

        findBlockedWord(
            fields = listOf(BlockField("达人要求", detail.creatorRequirements)),
            words = requirementWords(config.excludedWords),
        )?.let { return it }
        return FaceRequirementPolicy.evaluate("达人要求", detail.creatorRequirements)
    }

    fun evaluateShootingRequirements(detail: TaskDetail, config: AutomationConfig): TaskEligibility {
        findBlockedWord(
            fields = listOf(BlockField("拍摄要求", detail.shootingRequirements)),
            words = requirementWords(config.excludedWords),
        )?.let { return it }
        return FaceRequirementPolicy.evaluate("拍摄要求", detail.shootingRequirements)
    }

    private fun findBlockedWord(fields: List<BlockField>, words: List<String>): TaskEligibility? {
        fields.forEach { field ->
            words.firstOrNull { word ->
                word.isNotBlank() && field.value.contains(word.trim(), ignoreCase = true)
            }?.let { word ->
                return TaskEligibility(
                    eligible = false,
                    reason = "${field.displayName}命中屏蔽词：${word.trim()}",
                    matchedField = field.displayName,
                    matchedWord = word.trim(),
                )
            }
        }
        return null
    }

    private fun requirementWords(words: List<String>): List<String> =
        words.filterNot { it.trim().contains("露脸", ignoreCase = true) }

    internal fun splitTerms(value: String): List<String> = value
        .split(Regex("(?:##|[,，、;；\\s]+)"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    internal fun formatReward(value: Double): String =
        "¥" + java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun formatRange(config: AutomationConfig): String =
        "${formatReward(config.minPrice)}-${formatReward(config.maxPrice)}"
}
